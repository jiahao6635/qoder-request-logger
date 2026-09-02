#!/bin/sh
# Qoder request logger - jq fallback collector.
#
# Used only when no Node.js runtime can be resolved by logger.sh. It keeps the
# audit trail flowing at reduced fidelity:
#
#   WHO / WHEN / REQUEST / RESPONSE - captured from the hook stdin payload
#   CREDITS AND TOKENS               - best effort: the most recent assistant
#                                      `usage` block found at the tail of the
#                                      transcript, written as `billing_latest`.
#                                      Unlike the Node collector this does NOT
#                                      track a read offset, so the same cost
#                                      figure can repeat across records and must
#                                      not be summed. Use it for triage only.
#
# Always exits 0 and never writes to stdout.

LOG_DIR=${QODER_LOG_DIR:-"$HOME/.qoder/logs"}
STATE_DIR="$LOG_DIR/.request-logger"
TS_VAL=$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ 2>/dev/null || date -u +%Y-%m-%dT%H:%M:%SZ)
DAY_VAL=$(date -u +%Y-%m-%d)

INPUT=$(cat 2>/dev/null)
[ -z "$INPUT" ] && exit 0
printf '%s' "$INPUT" | jq -e . >/dev/null 2>&1 || exit 0

# Attribution requirement: a record without enterprise identity (payload
# extra.user) can never be queried back to a person, so drop it instead of
# saving an unattributable record. The jq fallback keeps no session cache, so
# the payload itself is the only identity source.
printf '%s' "$INPUT" | jq -e '(.extra.user // empty) | type == "object" and length > 0' >/dev/null 2>&1 || exit 0

# Best-effort cost snapshot from the transcript tail. `fromjson?` skips the
# partial line a running session may be appending right now.
TP=$(printf '%s' "$INPUT" | jq -r '.transcript_path // empty' 2>/dev/null)
USAGE_JSON=""
if [ -n "$TP" ] && [ -r "$TP" ]; then
  USAGE_JSON=$(tail -n 300 "$TP" 2>/dev/null \
    | jq -Rrc 'fromjson? | select(type=="object") | (.message.usage // empty)
               | {credits, original_credits, billable, request_id, input_tokens,
                  output_tokens, cache_read_input_tokens, cache_creation_input_tokens,
                  context_usage_ratio}' 2>/dev/null \
    | tail -n 1)
fi
[ -n "$USAGE_JSON" ] || USAGE_JSON="null"

mkdir -p "$STATE_DIR" 2>/dev/null
LOG_FILE="$LOG_DIR/requests_$DAY_VAL.jsonl"

REDACT=${QODER_LOG_REDACT:-1}

printf '%s' "$INPUT" \
  | jq -c \
      --arg ts "$TS_VAL" \
      --argjson usage "$USAGE_JSON" \
      --arg redact "$REDACT" \
    '
    def mask(s):
      if $redact == "0" then s
      else
        # No back-references: jq renders "$1" literally, so the credential key is
        # replaced along with its value. Portable across jq 1.6 and 1.7.
        # The \u0027 escape below stands for a single quote: the literal
        # character would terminate this shell single-quoted jq program.
        s
        | gsub("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"; "[redacted private key]")
        | gsub("(?i)(password|passwd|secret|api[_-]?key|access[_-]?token|authorization|bearer)(\"?[:=]\"?)[^\\s\"\u0027,;]{6,}"; "[redacted]")
        | gsub("(ghp_|gho_|ghu_|ghs_|ghr_|xox[baprs]-|AKIA|ASIA|lark_)[A-Za-z0-9_-]{12,}"; "[redacted]")
      end;
    def tostr: if . == null then null elif type == "string" then . else tostring end;
    (.extra // {}) as $extra
    | {
        log_schema: "1.0.1-fallback",
        record_kind: "hook_event",
        collector: "jq-fallback",
        # Enterprise identity flattened to the top level (the attribution gate
        # above guarantees extra.user is a non-empty object); the legacy
        # machine-level identity fields are no longer saved.
        email: $extra.user.email,
        name: $extra.user.name,
        org_id: $extra.user.org_id,
        org_name: $extra.user.org_name,
        uid: $extra.user.uid,
        timestamp: $ts,
        event: (.hook_event_name // "UnknownEvent"),
        session_id: (.session_id // "unknown"),
        agent_id: .agent_id,
        agent_type: .agent_type,
        model: .model,
        permission_mode: .permission_mode,
        cwd: .cwd,
        transcript_path: .transcript_path,
        repo: $extra.repo,
        git_branch: $extra.branch,
        request_time: $extra.request_time,
        response_time: $extra.response_time,
        request: {
          prompt: (.prompt | tostr),
          tool_name: .tool_name,
          tool_input: (.tool_input | tostr)
        },
        response: {
          tool_response: ((.tool_response // .tool_output) | tostr),
          error: (.error | tostr),
          error_type: .error_type,
          last_assistant_message: (.last_assistant_message | tostr),
          stop_reason: .stop_reason,
          message: (.message | tostr)
        },
        billing_latest: ($usage | if . == null then null else . + {
          caveat: "jq fallback: not offset-tracked, do not sum across records"
        } end),
        _raw: (tojson | tostr)
      }
    | .request = (.request | with_entries(select(.value != null)))
    | .response = (.response | with_entries(select(.value != null)))
    | with_entries(select(.value != null))
    | if $redact == "0" then .
      else .request = (.request | map_values(if type == "string" then mask(.) else . end))
           | .response = (.response | map_values(if type == "string" then mask(.) else . end))
           | ._raw = mask(._raw)
      end
    ' >>"$LOG_FILE" 2>>"$STATE_DIR/logger-error.log"

exit 0
