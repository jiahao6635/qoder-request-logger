#!/usr/bin/env python3
"""Generate plugin/hooks/hooks.json for the qoder-request-logger plugin.

A bare run (no arguments) reproduces the shipped configuration exactly: the
13-event internal-audit scope (SHIPPED_EVENTS, delivery order) with the
delivered description. Byte equality is the acceptance bar, checkable with:

    python3 tools/gen-hooks.py --output /tmp/check.json
    diff /tmp/check.json plugin/hooks/hooks.json    # must report no differences

`--all-events` switches to the full 26-event superset instead: the collector
is registered on every documented Qoder lifecycle event so that no agent
action goes unrecorded. Event names come from:
  - https://docs.qoder.com/extensions/hooks   (IDE / JetBrains, 12 events)
  - https://docs.qoder.com/cli/hooks          (CLI superset, 23 events)

Events an entry point does not implement simply never fire, so registering the
superset is safe and keeps one config working across IDE and CLI.

The upload channel is parameterised: --server-url/--api-key/--user-id feed the
collector identity, --upload-mode selects off | legacy | cursor (default
legacy = the historical per-record push), and --ca-certs points
NODE_EXTRA_CA_CERTS at a private CA bundle. Unset options keep their defaults,
so a bare run reproduces the shipped configuration byte for byte; the other
flags are distribution layers on top of the shipped 13-event scope (or the
26-event superset with --all-events).

Distribution model (recommended: shared-key fleet package):

- Pass --server-url and the company-wide shared --api-key to bake both into
  every event's env block. One identical package for everyone. The key only
  authenticates the upload endpoint; attribution comes from the enterprise
  email already carried inside each record, so --user-id stays unset.
- Optional per-machine override: when the env identity values are empty the
  collector falls back to the per-machine credentials file
  (~/.qoder/log-credentials.json, override with QODER_LOG_CREDENTIALS_FILE)
  that IT provisions on that device only (e.g. a temporary per-host key).
"""

import argparse
import json
from pathlib import Path

# Core, always-recorded lifecycle events, with the per-event timeout.
# Transcript harvesting (Credits / tokens) rides on the round-ending events,
# which need more headroom than a plain fire-and-forget record.
EVENTS = {
    # --- session lifecycle ---
    "SessionStart":      {"async": True,  "timeout": 15},
    "SessionEnd":        {"async": False, "timeout": 25},
    "CwdChanged":        {"async": True,  "timeout": 15},
    # --- user requests ---
    "UserPromptSubmit":  {"async": False, "timeout": 15},
    "Elicitation":       {"async": False, "timeout": 15},
    "ElicitationResult": {"async": True,  "timeout": 15},
    # --- tool calls: request / response / failure ---
    "PreToolUse":        {"async": True,  "timeout": 15},
    "PostToolUse":       {"async": False, "timeout": 25},
    "PostToolUseFailure": {"async": False, "timeout": 25},
    # --- authorization ---
    "PermissionRequest":  {"async": False, "timeout": 15},
    "PermissionDenied":   {"async": True,  "timeout": 15},
    # --- agent / subagent rounds ---
    "Stop":          {"async": False, "timeout": 30},
    "StopFailure":   {"async": True,  "timeout": 25},
    "SubagentStart": {"async": True,  "timeout": 15},
    "SubagentStop":  {"async": False, "timeout": 30},
    # --- tasks (QoderWork / long running) ---
    "TaskCreated":   {"async": True,  "timeout": 15},
    "TaskCompleted": {"async": False, "timeout": 25},
    # --- context compaction ---
    "PreCompact":    {"async": False, "timeout": 15},
    "PostCompact":   {"async": False, "timeout": 25},
    # --- notifications & config drift ---
    "Notification":      {"async": True,  "timeout": 15},
    "ConfigChange":      {"async": True,  "timeout": 15},
    "InstructionsLoaded": {"async": True, "timeout": 15},
    "FileChanged":       {"async": True,  "timeout": 15},
    # --- worktrees / collaborators ---
    "WorktreeCreate":    {"async": True,  "timeout": 15},
    "WorktreeRemove":    {"async": True,  "timeout": 15},
    # Observed firing in real Qoder configurations although absent from the
    # published event table, so it is recorded defensively.
    "TeammateIdle":      {"async": True,  "timeout": 15},
}

# The shipped configuration is the internal-audit scope: these 13 events, in
# this delivery order (the list order defines the generated JSON key order).
# Per-event async/timeout values come from EVENTS, so the shipped settings
# stay in sync with the table above.
SHIPPED_EVENTS = [
    "SessionStart",
    "SessionEnd",
    "UserPromptSubmit",
    "Stop",
    "PreToolUse",
    "PostToolUse",
    "PostToolUseFailure",
    "PermissionRequest",
    "PermissionDenied",
    "SubagentStart",
    "SubagentStop",
    "TaskCreated",
    "TaskCompleted",
]

# Description text of the shipped configuration, reproduced verbatim so the
# bare run stays byte-equal with the delivered plugin/hooks/hooks.json.
SHIPPED_DESCRIPTION = (
    "Qoder request logger (internal-audit scope) - 13 events covering session "
    "lifecycle, user prompts, agent final output, tool calls and permission "
    "decisions. Transcript harvesting (Credits / tokens) enabled; credential "
    "redaction on. HTTP upload off while QODER_LOG_SERVER_URL is empty; "
    "QODER_LOG_UPLOAD_MODE=legacy keeps the per-record push with outbox retry, "
    "cursor switches to offset-tracked gzip batch upload."
)

# Description used for the --all-events full 26-event superset.
FULL_SCOPE_DESCRIPTION = (
    "Qoder request logger - records who ran which prompt and tool call, "
    "when, what came back, and the Credits / token cost of every agent "
    "round. Local JSONL always on; HTTP upload off while "
    "QODER_LOG_SERVER_URL is empty (QODER_LOG_UPLOAD_MODE: legacy = "
    "per-record push with outbox retry, cursor = offset-tracked gzip "
    "batch upload)."
)

# Single hook definition shared by every event. The wrapper script resolves a
# usable Node.js runtime, falls back to a jq-based logger, and always exits 0
# so that logging can never block or break the agent.
COMMAND = '"${QODER_PLUGIN_ROOT}"/hooks/logger.sh'

# Default env block shared by every event. Callers override the five
# deployment-specific keys (server url / api key / user id / upload mode /
# CA bundle) via CLI flags; everything else is fixed policy:
#   QODER_LOG_REDACT=1               credential masking stays on
#   QODER_LOG_INCLUDE_TRANSCRIPT=1   Credits / token harvesting stays on
#   QODER_LOG_RAW="0"                verbatim stdin copy stays off
#   QODER_LOG_UPLOAD_INTERVAL_SEC    min seconds between cursor attempts
#   QODER_LOG_LOCAL_RETENTION_DAYS   0 = never delete local files
HOOK_CONFIG = {
    "QODER_LOG_SERVER_URL": "",
    "QODER_LOG_API_KEY": "",
    "QODER_LOG_USER_ID": "",
    "QODER_LOG_REDACT": "1",
    "QODER_LOG_INCLUDE_TRANSCRIPT": "1",
    "QODER_LOG_RAW": "0",
    "QODER_LOG_UPLOAD_MODE": "legacy",
    "QODER_LOG_UPLOAD_INTERVAL_SEC": "60",
    "QODER_LOG_LOCAL_RETENTION_DAYS": "0",
    "NODE_EXTRA_CA_CERTS": "",
}


def build(server_url="", api_key="", user_id="", ca_certs="", upload_mode="legacy", all_events=False):
    env = dict(HOOK_CONFIG)
    env.update({
        "QODER_LOG_SERVER_URL": server_url,
        "QODER_LOG_API_KEY": api_key,
        "QODER_LOG_USER_ID": user_id,
        "QODER_LOG_UPLOAD_MODE": upload_mode,
        "NODE_EXTRA_CA_CERTS": ca_certs,
    })
    # Default: the shipped 13-event internal-audit scope in delivery order;
    # --all-events: the full 26-event superset in EVENTS order.
    event_names = list(EVENTS) if all_events else SHIPPED_EVENTS
    hooks = {}
    for event in event_names:
        opts = EVENTS[event]
        group = {
            "matcher": "",
            "hooks": [
                {
                    "type": "command",
                    "command": COMMAND,
                    "name": f"request-logger-{event.lower()}",
                    "timeout": opts["timeout"],
                    "async": opts["async"],
                    "env": dict(env),
                }
            ],
        }
        hooks[event] = [group]
    return {
        "description": FULL_SCOPE_DESCRIPTION if all_events else SHIPPED_DESCRIPTION,
        "hooks": hooks,
    }


def parse_args():
    parser = argparse.ArgumentParser(
        description="Generate plugin/hooks/hooks.json for the qoder-request-logger plugin.")
    parser.add_argument("--server-url", default="",
                        help="QODER_LOG_SERVER_URL value (empty disables upload)")
    parser.add_argument("--api-key", default="",
                        help="QODER_LOG_API_KEY value, sent as X-API-Key; "
                             "pass the company-wide shared key for the "
                             "fleet-wide package (leave unset only for "
                             "local-only or credentials-file deployments)")
    parser.add_argument("--user-id", default="",
                        help="QODER_LOG_USER_ID member identity override; "
                             "same credentials-file fallback as --api-key")
    parser.add_argument("--ca-certs", default="",
                        help="NODE_EXTRA_CA_CERTS path to a private CA bundle")
    parser.add_argument("--upload-mode", default="legacy", choices=["off", "legacy", "cursor"],
                        help="upload channel mode (default: legacy, the historical per-record push)")
    parser.add_argument("--all-events", action="store_true",
                        help="emit the full 26-event superset instead of the shipped 13-event internal-audit scope")
    parser.add_argument("--output", default=None,
                        help="output file path (default: plugin/hooks/hooks.json in the repo root)")
    return parser.parse_args()


def main():
    args = parse_args()
    target = Path(args.output) if args.output else Path(__file__).resolve().parent.parent / "plugin" / "hooks" / "hooks.json"
    payload = build(args.server_url, args.api_key, args.user_id, args.ca_certs, args.upload_mode, all_events=args.all_events)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {target} ({len(payload['hooks'])} events)")


if __name__ == "__main__":
    main()
