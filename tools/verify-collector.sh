#!/bin/sh
# Collector verification: prove the audit trail is really being written.
#
# The logger swallows every internal error by design (exit 0, no stdout) so a
# broken collector is invisible: the agent keeps working and the log file simply
# stays empty. This script replays one synthetic session through the shipped
# wrapper and fails loudly when any guarantee is missing:
#
#   1. the Node self test passes (schema, dedup, redaction, summary)
#   2. tool requests and responses reach requests_YYYY-MM-DD.jsonl
#   3. Credits / tokens survive transcript harvesting
#   4. credentials never land in the log in plaintext
#   5. audit-report.js --strict finds no coverage ERROR
#
# Usage: sh tools/verify-collector.sh   (exit 0 = collection healthy)

set -u

TOOL_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PLUGIN_DIR=$(CDPATH= cd -- "$TOOL_DIR/../plugin" && pwd)
HOOK_DIR="$PLUGIN_DIR/hooks"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/qoder-collector-verify-XXXXXX")
export QODER_LOG_DIR="$WORK/logs"
DAY=$(date -u +%Y-%m-%d)
LOG_FILE="$QODER_LOG_DIR/requests_$DAY.jsonl"
TRANSCRIPT="$WORK/transcript.jsonl"
SECRET="AKIAIOSFODNN7EXAMPLE"
FAILURES=0
# Section 7 (upload e2e) resources: declared here, before the trap below, so
# an abort anywhere in that section cannot leak the mock process or its temp
# directory. Empty means "not started"; cleanup() re-checks before acting.
MOCK_PID=""
UPWORK=""

cleanup() {
  rm -rf "$WORK"
  [ -n "${UIWORK:-}" ] && rm -rf "$UIWORK"
  [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" 2>/dev/null || true
  [ -n "${UPWORK:-}" ] && rm -rf "$UPWORK"
}
trap cleanup EXIT INT TERM

say() { printf '%s\n' "$1" >&2; }
check() { # check <label> <0|1>
  if [ "$2" = "0" ]; then say "  PASS  $1"; else say "  FAIL  $1"; FAILURES=$((FAILURES + 1)); fi
}

find_node() {
  if command -v node >/dev/null 2>&1; then command -v node; return 0; fi
  for candidate in /opt/homebrew/bin/node /usr/local/bin/node "$HOME/.volta/bin/node"; do
    [ -x "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
  done
  if [ -d "$HOME/.nvm/versions/node" ]; then
    ls -d "$HOME"/.nvm/versions/node/*/bin/node 2>/dev/null | sort -Vr | head -n 1
    return 0
  fi
  return 1
}

NODE_BIN=$(find_node)
if [ -z "$NODE_BIN" ]; then
  say "verify ABORTED: node runtime not found (the jq fallback cannot be self-tested)"
  exit 1
fi

say "node: $NODE_BIN"
say "log directory under test: $QODER_LOG_DIR"

# ── 1. shipped self test ─────────────────────────────────────────────────────
"$NODE_BIN" "$HOOK_DIR/log-request.js" --self-test >/dev/null 2>"$WORK/self-test.log"
check "self test passes" $?
tail -n 1 "$WORK/self-test.log" >&2

# ── 2. replay one synthetic session through the real wrapper ─────────────────
cat > "$TRANSCRIPT" <<EOF
{"type":"runtime-config","sessionId":"verify-1","model":"ultimate","contextWindow":200000,"timestamp":1}
{"type":"user","uuid":"u1","timestamp":"2026-01-01T00:00:21.000Z","origin":{"kind":"human"},"message":{"role":"user","content":[{"type":"text","text":"run the tests"}]},"promptId":"p1"}
{"type":"assistant","uuid":"a1","timestamp":"2026-01-01T00:01:18.000Z","message":{"id":"c1","role":"assistant","model":"ultimate","stop_reason":"tool_use","content":[{"type":"tool_use","id":"call_1","name":"Bash","input":{"command":"mvn -q test -Dapp.secret=$SECRET"}}],"usage":{"input_tokens":52000,"output_tokens":830,"cache_read_input_tokens":48000,"credits":1.42,"original_credits":1.42,"billable":true,"request_id":"req-a1","context_usage_ratio":0.31}}}
{"type":"user","uuid":"u2","timestamp":"2026-01-01T00:01:30.000Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"Tests run: 4, Failures: 1","is_error":true}]}}
EOF

emit() { # emit <hook_event_name> <extra fields, inserted inside the envelope>
  # extra.user carries the enterprise identity on purpose: without it the
  # attribution gate drops every record, so the envelope mirrors what real IDE
  # hook payloads deliver.
  printf '{"hook_event_name":%s,"session_id":"verify-1","transcript_path":"%s","cwd":"%s","extra":{"email":"dev@example.com","repo":"sigmob/data-platform","branch":"verify","user":{"email":"dev@example.com","name":"Verify User","org_id":"019cbcf2-verify","org_name":"sigmob","uid":"019efd72-verify"}}%s}' \
    "$1" "$TRANSCRIPT" "$PLUGIN_DIR" "$2" | sh "$HOOK_DIR/logger.sh"
}

emit '"SessionStart"' ',"source":"startup"'
emit '"UserPromptSubmit"' ',"prompt":"run the tests","permission_mode":"acceptEdits"'
emit '"PreToolUse"' ',"tool_name":"Bash","tool_input":{"command":"mvn -q test -Dapp.secret='$SECRET'"},"tool_use_id":"call_1"'
emit '"PostToolUseFailure"' ',"tool_name":"Bash","error":"Tests run: 4, Failures: 1","error_type":"non_zero_exit","tool_use_id":"call_1"'
emit '"Stop"' ',"model":"ultimate","last_assistant_message":"1 failure"'
emit '"SessionEnd"' ',"reason":"exit"'

[ -f "$LOG_FILE" ]
check "log file created" $?

count_of() {
  # `grep -c` prints 0 *and* exits 1 on no match; keep the number, drop the status.
  c=$(grep -c "\"type\":\"$1\"" "$LOG_FILE" 2>/dev/null)
  printf '%s' "${c:-0}"
}
RECORDS=$(wc -l < "$LOG_FILE" 2>/dev/null | tr -d ' ' || echo 0)
say "  records: $RECORDS -> TOOL_REQUEST=$(count_of TOOL_REQUEST) TOOL_RESPONSE_FAILURE=$(count_of TOOL_RESPONSE_FAILURE) LLM_USAGE=$(count_of LLM_USAGE) TOOL_RESULT=$(count_of TOOL_RESULT)"

[ "$(count_of TOOL_REQUEST)" -ge 1 ]; check "tool request captured" $?
[ "$(count_of TOOL_RESPONSE_FAILURE)" -ge 1 ]; check "tool failure captured" $?
[ "$(count_of LLM_USAGE)" -ge 1 ]; check "Credits / tokens captured from the transcript" $?
[ "$(count_of TOOL_RESULT)" -ge 1 ]; check "tool output captured" $?
grep -q '"credits":1.42' "$LOG_FILE"; check "credits value survives verbatim" $?
grep -q '\[redacted\]' "$LOG_FILE"; check "credential was masked" $?
grep -q "$SECRET" "$LOG_FILE"; [ $? -ne 0 ]; check "no plaintext credential on disk" $?
[ -s "$QODER_LOG_DIR/.request-logger/logger-error.log" ]; [ $? -ne 0 ]; check "collector reported no internal error" $?

# ── 5. the report tool must agree the trail is complete ──────────────────────
"$NODE_BIN" "$TOOL_DIR/audit-report.js" --dir "$QODER_LOG_DIR" --day "$DAY" --strict >/dev/null 2>&1
check "audit-report --strict sees no coverage ERROR" $?

# ── 6. live end-to-end test against a real qodercli session ──────────────────
# Optional: needs the Qoder CLI and an interactive-less run is fine. The plugin
# hook scripts must be executable - Qoder execs them directly (exit 126 = missing +x).
unset QODER_LOG_DIR   # the synthetic test redirected it; live hooks must use the default dir
LIVE=0
QODERCLI=""
# The CLI binary lives either directly in ~/.qoder/bin/qodercli/ (current
# layout: qodercli-<version>) or one level deeper in a version directory.
for candidate in "$HOME/.qoder/bin/qodercli"/qodercli-* "$HOME/.qoder/bin/qodercli"/*/qodercli*; do
  [ -x "$candidate" ] && [ -f "$candidate" ] && QODERCLI="$candidate"
done
if [ -n "$QODERCLI" ] && command -v date >/dev/null 2>&1; then
  LIVE_DIR="$HOME/.qoder/logs"
  LIVE_FILE="$LIVE_DIR/requests_$(date -u +%Y-%m-%d).jsonl"
  LIVE_BEFORE=$(wc -l < "$LIVE_FILE" 2>/dev/null | tr -d ' ' || echo 0)
  "$QODERCLI" -p "请用 Bash 工具执行这一条命令：echo hook-e2e-probe 。执行后只回复 done。" \
    --setting-sources user --permission-mode dont_ask --allowed-tools Bash \
    -m lite >/dev/null 2>&1 || say "  note: live qodercli run exited non-zero, checking records anyway"
  LIVE_AFTER=$(wc -l < "$LIVE_FILE" 2>/dev/null | tr -d ' ' || echo 0)
  say "  live session records: before=$LIVE_BEFORE after=$LIVE_AFTER"
  if [ "$LIVE_AFTER" -gt "$LIVE_BEFORE" ]; then
    check "live qodercli session wrote audit records" 0
    LIVE=1
  else
    check "live qodercli session wrote audit records" 1
    say "        hint: run this script OUTSIDE any sandbox so hooks can write $LIVE_DIR"
    say "        hint: hook scripts need +x (Qoder execs them directly, 126 = permission denied)"
  fi
else
  say "  SKIP  live qodercli test (qodercli binary not found)"
fi

# ── 7. upload channels e2e against a local mock server (opt-in) ──────────
# Gated by QODER_UPLOAD_E2E=1 and skipped entirely by default, so the checks
# above (and this script's default output and exit code) never change. When
# enabled, it exercises both upload channels against tools/mock-log-server.js:
#   a) legacy: one synthetic hook through the real wrapper -> per-record POST
#   b) cursor: a pre-seeded daily file shipped as a gzip batch, whose cursor
#      offset must end up equal to the file size
if [ "${QODER_UPLOAD_E2E:-0}" = "1" ]; then
  UPWORK=$(mktemp -d "${TMPDIR:-/tmp}/qoder-upload-e2e-XXXXXX")
  MOCK_PORT=$((18000 + ($$ % 2000)))
  MOCK_OUT="$UPWORK/mock-received.jsonl"
  "$NODE_BIN" "$TOOL_DIR/mock-log-server.js" --port "$MOCK_PORT" --out "$MOCK_OUT" --require-key >"$UPWORK/mock-server.log" 2>&1 &
  MOCK_PID=$!

  mock_healthy() {
    "$NODE_BIN" -e 'const h=require("http");h.get("http://127.0.0.1:"+process.argv[1]+"/api/health",r=>{process.exit(r.statusCode===200?0:1)}).on("error",()=>process.exit(1));' "$MOCK_PORT" 2>/dev/null
  }
  lines_in() { c=$(wc -l < "$1" 2>/dev/null | tr -d ' '); printf '%s' "${c:-0}"; }
  wait_until_lines() { # wait_until_lines <file> <wanted> <max-quarter-seconds>
    n=0
    while [ "$n" -lt "${3:-60}" ]; do
      [ "$(lines_in "$1")" -ge "$2" ] && return 0
      sleep 0.25
      n=$((n + 1))
    done
    [ "$(lines_in "$1")" -ge "$2" ]
  }

  MOCK_OK=1
  tries=0
  while [ "$tries" -lt 60 ]; do
    if mock_healthy; then MOCK_OK=0; break; fi
    tries=$((tries + 1))
    sleep 0.25
  done
  check "upload e2e: mock server healthy on :$MOCK_PORT" $MOCK_OK

  if [ "$MOCK_OK" = "0" ]; then
    # a) legacy channel: synthetic hook through the real wrapper -> POST /api/logs
    #    (take the baseline BEFORE the hook runs: its POST can land within
    #    milliseconds of the wrapper exiting)
    LEG_BASE=$(lines_in "$MOCK_OUT")
    LEG_DIR="$UPWORK/legacy-logs"
    printf '%s' '{"hook_event_name":"SessionStart","session_id":"upload-e2e-legacy","cwd":"/tmp","source":"startup","extra":{"user":{"email":"dev@example.com","uid":"019efd72-verify"}}}' \
      | env QODER_LOG_SERVER_URL="http://127.0.0.1:$MOCK_PORT" QODER_LOG_API_KEY="qk_test123" \
          QODER_LOG_DIR="$LEG_DIR" QODER_LOG_UPLOAD_MODE="legacy" \
          sh "$HOOK_DIR/logger.sh" >"$UPWORK/legacy.stdout" 2>"$UPWORK/legacy.stderr"
    check "upload e2e legacy: wrapper exits 0" $?
    [ ! -s "$UPWORK/legacy.stdout" ]; check "upload e2e legacy: no stdout" $?
    [ -s "$LEG_DIR/requests_$DAY.jsonl" ]; check "upload e2e legacy: local file still written" $?
    [ -s "$LEG_DIR/.request-logger/logger-error.log" ]; [ $? -ne 0 ]; check "upload e2e legacy: no collector error" $?
    wait_until_lines "$MOCK_OUT" $(( ${LEG_BASE:-0} + 1 ))
    check "upload e2e legacy: mock received >=1 record" $?

    # b) cursor channel: pre-seeded daily file -> gzip batch POST /api/logs/batch.
    #    SERVER_URL carries a path on purpose: the batch endpoint must be derived
    #    from the origin only.
    CUR_DIR="$UPWORK/cursor-logs"
    mkdir -p "$CUR_DIR"
    cat > "$CUR_DIR/requests_$DAY.jsonl" <<EOF
{"log_schema":"1.1.0","record_kind":"hook_event","type":"USER_REQUEST","session_id":"cursor-e2e","prompt":"cursor line 1"}
{"log_schema":"1.1.0","record_kind":"hook_event","type":"TOOL_REQUEST","session_id":"cursor-e2e","prompt":"cursor line 2"}
{"log_schema":"1.1.0","record_kind":"hook_event","type":"TOOL_RESPONSE","session_id":"cursor-e2e","prompt":"cursor line 3"}
EOF
    CUR_BASE=$(lines_in "$MOCK_OUT")
    printf '%s' '{"hook_event_name":"Stop","session_id":"cursor-e2e","cwd":"/tmp"}' \
      | env QODER_LOG_SERVER_URL="http://127.0.0.1:$MOCK_PORT/api/logs" QODER_LOG_API_KEY="qk_test123" \
          QODER_LOG_DIR="$CUR_DIR" QODER_LOG_UPLOAD_MODE="cursor" \
          "$NODE_BIN" "$HOOK_DIR/log-request.js" >"$UPWORK/cursor.stdout" 2>"$UPWORK/cursor.stderr"
    check "upload e2e cursor: collector exits 0" $?
    [ ! -s "$UPWORK/cursor.stdout" ]; check "upload e2e cursor: no stdout" $?
    wait_until_lines "$MOCK_OUT" $(( ${CUR_BASE:-0} + 3 ))
    check "upload e2e cursor: mock received >=3 records via batch" $?
    CUR_SIZE=$(wc -c < "$CUR_DIR/requests_$DAY.jsonl" | tr -d ' ')
    CUR_OFFSET=$("$NODE_BIN" -e 'try{const s=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));console.log(s.files[process.argv[2]].offset)}catch(e){console.log(-1)}' \
      "$CUR_DIR/.request-logger/upload-state.json" "requests_$DAY.jsonl")
    [ "$CUR_OFFSET" = "$CUR_SIZE" ]; check "upload e2e cursor: offset advanced to file size ($CUR_OFFSET/$CUR_SIZE)" $?
    [ -s "$CUR_DIR/.request-logger/logger-error.log" ]; [ $? -ne 0 ]; check "upload e2e cursor: no collector error" $?
  fi

  kill -s INT "$MOCK_PID" 2>/dev/null
  sleep 1
  kill "$MOCK_PID" 2>/dev/null
  wait "$MOCK_PID" 2>/dev/null
  rm -rf "$UPWORK"
  if [ "$FAILURES" -gt 0 ]; then
    say "UPLOAD E2E FAILED - see the FAIL lines above"
  else
    say "UPLOAD E2E OK - legacy and cursor channels both reached the mock server"
  fi
fi

# ── 8. enterprise identity: flattened, cached & backfilled (synthetic) ──────
# payload extra.user (enterprise identity) is cached per session, flattened
# onto every saved record as top-level email / name / org_id / org_name / uid,
# and transcript-derived records inherit it from the per-session cache in
# state.json. Sessions that never see extra.user produce NO records at all
# (attribution gate). The collector is driven directly via node so the payload
# shape is fully controlled: real IDE events always carry extra.user, qodercli
# CLI sessions never do, so only synthetic injection can prove both sides.
UIWORK=$(mktemp -d "${TMPDIR:-/tmp}/qoder-userinfo-verify-XXXXXX")
UI_LOG_DIR="$UIWORK/logs"
UI_LOG_FILE="$UI_LOG_DIR/requests_$DAY.jsonl"
UI_STATE_FILE="$UI_LOG_DIR/.request-logger/state.json"
UI_SESSION="userinfo-verify-main"
UI_CONTROL="userinfo-verify-control"
UI_PARTIAL="userinfo-verify-partial"
UI_SECRET="userinfo-verify-secret"
UI_LONG="userinfo-verify-long"
UI_LONGSECRET="userinfo-verify-longsecret"
UI_MAIN_TS="$UIWORK/main-transcript.jsonl"
UI_CTRL_TS="$UIWORK/control-transcript.jsonl"
UI_PARTIAL_TS="$UIWORK/partial-transcript.jsonl"

# Minimal fixture transcripts in the real format the harvester parses
# (runtime-config / user prompt / assistant with tool_use + usage / tool_result).
cat > "$UI_MAIN_TS" <<'EOF'
{"type":"runtime-config","sessionId":"userinfo-verify-main","model":"ultimate","contextWindow":200000,"timestamp":1}
{"type":"user","uuid":"ui-u1","timestamp":"2026-01-01T00:00:21.000Z","origin":{"kind":"human"},"message":{"role":"user","content":[{"type":"text","text":"synthetic identity check prompt"}]},"promptId":"ui-p1"}
{"type":"assistant","uuid":"ui-a1","timestamp":"2026-01-01T00:01:18.000Z","message":{"id":"ui-c1","role":"assistant","model":"ultimate","stop_reason":"tool_use","content":[{"type":"tool_use","id":"ui-call-1","name":"Bash","input":{"command":"echo identity-check"}}],"usage":{"input_tokens":100,"output_tokens":10,"cache_read_input_tokens":50,"credits":0.01,"billable":true,"request_id":"ui-req-1"}}}
{"type":"user","uuid":"ui-u2","timestamp":"2026-01-01T00:01:30.000Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"ui-call-1","content":"identity-check ok","is_error":false}]}}
EOF
sed "s/userinfo-verify-main/userinfo-verify-control/g; s/ui-/ctl-/g" "$UI_MAIN_TS" > "$UI_CTRL_TS"

# The partial-key session replays its transcript in two rounds: round 1 is
# harvested under the base 5-key identity, round 2 lines are appended only
# after a partial-key event and must come back with the merged identity -
# proving merge-by-key caching end to end.
cat > "$UI_PARTIAL_TS" <<'EOF'
{"type":"user","uuid":"pt-u1","timestamp":"2026-01-01T00:00:21.000Z","origin":{"kind":"human"},"message":{"role":"user","content":[{"type":"text","text":"partial base prompt"}]},"promptId":"pt-p1"}
{"type":"assistant","uuid":"pt-a1","timestamp":"2026-01-01T00:01:18.000Z","message":{"id":"pt-c1","role":"assistant","model":"ultimate","stop_reason":"tool_use","content":[{"type":"tool_use","id":"pt-call-1","name":"Bash","input":{"command":"echo partial-base"}}],"usage":{"input_tokens":100,"output_tokens":10,"cache_read_input_tokens":50,"credits":0.01,"billable":true,"request_id":"pt-req-1"}}}
{"type":"user","uuid":"pt-u2","timestamp":"2026-01-01T00:01:30.000Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"pt-call-1","content":"partial base ok","is_error":false}]}}
EOF
UI_PARTIAL_TAIL='{"type":"assistant","uuid":"pt-a2","timestamp":"2026-01-01T00:02:18.000Z","message":{"id":"pt-c2","role":"assistant","model":"ultimate","stop_reason":"tool_use","content":[{"type":"tool_use","id":"pt-call-2","name":"Read","input":{"file_path":"/tmp/partial"}}],"usage":{"input_tokens":120,"output_tokens":12,"cache_read_input_tokens":60,"credits":0.02,"billable":true,"request_id":"pt-req-2"}}}
{"type":"user","uuid":"pt-u3","timestamp":"2026-01-01T00:02:30.000Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"pt-call-2","content":"partial merged ok","is_error":false}]}}'

ui_emit() { # ui_emit <payload json> -- synthetic stdin straight into the collector
  printf '%s' "$1" | env QODER_LOG_DIR="$UI_LOG_DIR" QODER_LOG_SERVER_URL="" \
    "$NODE_BIN" "$HOOK_DIR/log-request.js" >/dev/null 2>&1
}

# a) main session: seed the per-session cache with a full 5-key identity,
#    then refresh it (latest wins per key) from a second event of the same
#    session, then drive transcript harvesting with a CLI-style payload (no
#    extra key at all) whose derived records must attribute from the cache.
ui_emit '{"hook_event_name":"SessionStart","session_id":"'"$UI_SESSION"'","cwd":"/tmp","source":"startup","extra":{"user":{"email":"qa-one@sigmob.com","name":"测试一号","org_id":"019cbcf2-0000-0000-0000-000000000001","org_name":"sigmob","uid":"019efd72-0000-0000-0000-000000000001"}}}'
ui_emit '{"hook_event_name":"SessionEnd","session_id":"'"$UI_SESSION"'","cwd":"/tmp","reason":"exit","extra":{"user":{"email":"qa-two@sigmob.com","name":"测试二号","org_id":"019cbcf2-0000-0000-0000-000000000002","org_name":"sigmob","uid":"019efd72-0000-0000-0000-000000000002"}}}'
ui_emit '{"hook_event_name":"UserPromptSubmit","session_id":"'"$UI_SESSION"'","transcript_path":"'"$UI_MAIN_TS"'","cwd":"/tmp","prompt":"synthetic identity check prompt"}'
# b) control session: never sees extra.user, no cache, its own transcript -
#    the attribution gate must drop every record it generates.
ui_emit '{"hook_event_name":"UserPromptSubmit","session_id":"'"$UI_CONTROL"'","transcript_path":"'"$UI_CTRL_TS"'","cwd":"/tmp","prompt":"control prompt"}'
# c) partial-key session: full identity first, then an event carrying only
#    2 keys (the merge must not drop the cached keys), then transcript lines
#    appended afterwards whose derived records must carry the merged set.
ui_emit '{"hook_event_name":"SessionStart","session_id":"'"$UI_PARTIAL"'","transcript_path":"'"$UI_PARTIAL_TS"'","cwd":"/tmp","source":"startup","extra":{"user":{"email":"partial-base@sigmob.com","name":"合并基座","org_id":"019cbcf2-p000-0000-0000-00000000000b","org_name":"sigmob","uid":"019efd72-p000-0000-0000-00000000000b"}}}'
ui_emit '{"hook_event_name":"Notification","session_id":"'"$UI_PARTIAL"'","cwd":"/tmp","notification_type":"info","title":"partial identity","message":"partial key merge check","extra":{"user":{"email":"partial-override@sigmob.com","uid":"u-partial-override"}}}'
printf '%s\n' "$UI_PARTIAL_TAIL" >> "$UI_PARTIAL_TS"
ui_emit '{"hook_event_name":"UserPromptSubmit","session_id":"'"$UI_PARTIAL"'","transcript_path":"'"$UI_PARTIAL_TS"'","cwd":"/tmp","prompt":"partial merged prompt"}'
# d) redaction / truncation probes on independent sessions: a JWT-shaped uid
#    must be masked value by value in the flattened identity fields, and an
#    oversized email must be cut() bounded. TRUNCATE is lowered for the
#    long-email event only, so the bound is really hit without touching the
#    other sessions.
#
#    Truncation and redact-before-cut assertions live here in verify §8, not
#    in the collector's self-test: QODER_LOG_TRUNCATE is a process-level
#    constant, so a per-event bound can only be exercised by launching a
#    dedicated collector process with its own env - hence every probe below
#    is a separate session and a separate process. The coverage asymmetry
#    (self-test: redaction; §8: truncation + ordering) is deliberate.
ui_emit '{"hook_event_name":"Notification","session_id":"'"$UI_SECRET"'","cwd":"/tmp","notification_type":"info","title":"secret","message":"jwt shaped uid probe","extra":{"user":{"uid":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk"}}}'
"$NODE_BIN" -e 'process.stdout.write(JSON.stringify({ hook_event_name: "Notification", session_id: process.argv[1], cwd: "/tmp", notification_type: "info", title: "long", message: "oversized email probe", extra: { user: { email: "l".repeat(5000) + "@example.com" } } }))' "$UI_LONG" \
  | env QODER_LOG_DIR="$UI_LOG_DIR" QODER_LOG_SERVER_URL="" QODER_LOG_TRUNCATE="300" \
      "$NODE_BIN" "$HOOK_DIR/log-request.js" >/dev/null 2>&1
# Oversized secret-shaped uid (443-char JWT, far past the 300 bound): if
# masking ever ran after the cut, the truncated tail would break the JWT
# pattern and leak ~300 chars of plaintext head. It must instead land as the
# whole marker - locking the redact-before-cut ordering promise.
"$NODE_BIN" -e 'process.stdout.write(JSON.stringify({ hook_event_name: "Notification", session_id: process.argv[1], cwd: "/tmp", notification_type: "info", title: "long-secret", message: "oversized jwt-shaped uid probe", extra: { user: { uid: "eyJhbGciOiJIUzI1NiJ9." + "A".repeat(400) + "." + "B".repeat(20) } } }))' "$UI_LONGSECRET" \
  | env QODER_LOG_DIR="$UI_LOG_DIR" QODER_LOG_SERVER_URL="" QODER_LOG_TRUNCATE="300" \
      "$NODE_BIN" "$HOOK_DIR/log-request.js" >/dev/null 2>&1

# e) assertions, one "status|label" line each; the reason appended on failure
#    makes the diff visible without re-running anything by hand. The ok|fail
#    line count is asserted separately below, so a crashed or half-executed
#    verifier script can never pass this section silently.
UI_CHECKS="$UIWORK/checks.tsv"
"$NODE_BIN" -e '
const fs = require("fs");
const records = [];
try {
  const text = fs.readFileSync(process.argv[1], "utf8");
  for (const line of text.split("\n")) {
    if (line.trim()) records.push(JSON.parse(line));
  }
} catch (err) {
  process.stdout.write("fail|collector wrote no parsable records (" + err.message + ")\n");
  process.exit(0);
}
const state = (function () {
  try { return JSON.parse(fs.readFileSync(process.argv[2], "utf8")); } catch (err) { return null; }
})();
const stateRaw = (function () {
  try { return fs.readFileSync(process.argv[2], "utf8"); } catch (err) { return ""; }
})();
const mainId = process.argv[3];
const controlId = process.argv[4];
const partialId = process.argv[5];
const secretId = process.argv[6];
const longId = process.argv[7];
const longSecretId = process.argv[8];
const main = records.filter((r) => r.session_id === mainId);
const control = records.filter((r) => r.session_id === controlId);
const partial = records.filter((r) => r.session_id === partialId);
const KEYS = ["email", "name", "org_id", "org_name", "uid"];
const LEGACY = ["user_info", "client_id", "hostname", "os_user", "user"];
const FIRST = { email: "qa-one@sigmob.com", name: "测试一号", org_id: "019cbcf2-0000-0000-0000-000000000001", org_name: "sigmob", uid: "019efd72-0000-0000-0000-000000000001" };
const SECOND = { email: "qa-two@sigmob.com", name: "测试二号", org_id: "019cbcf2-0000-0000-0000-000000000002", org_name: "sigmob", uid: "019efd72-0000-0000-0000-000000000002" };
const PBASE = { email: "partial-base@sigmob.com", name: "合并基座", org_id: "019cbcf2-p000-0000-0000-00000000000b", org_name: "sigmob", uid: "019efd72-p000-0000-0000-00000000000b" };
const PMERGE = { email: "partial-override@sigmob.com", uid: "u-partial-override", name: PBASE.name, org_id: PBASE.org_id, org_name: PBASE.org_name };
const derived = ["LLM_TOOL_CALL", "TOOL_RESULT", "LLM_USAGE"];
const rows = [];
const check = (pass, label, reason) => rows.push((pass ? "ok" : "fail") + "|" + label + (pass ? "" : "  << " + reason));
// Saved-record shape: identity flattened to the top level (absent keys stay
// absent), and none of the legacy identity fields may survive.
const idMatches = (r, want) => !!r && LEGACY.every((k) => !(k in r)) && KEYS.every((k) => want[k] === undefined ? !(k in r) : r[k] === want[k]);
// state.json session cache: identity keys only (the session object itself
// legitimately keeps its own bookkeeping fields).
const cacheMatches = (s, want) => !!s && !!s.user_info && KEYS.every((k) => want[k] === undefined ? !(k in s.user_info) : s.user_info[k] === want[k]);
const brief = (r) => (r ? JSON.stringify(KEYS.reduce((o, k) => { if (k in r) o[k] = r[k]; return o; }, {})) : "record missing");

const cached = state && state.__sessions ? state.__sessions[mainId] : null;
check(cacheMatches(cached, SECOND), "identity: state.json caches extra.user under the session (latest wins)", "cached=" + JSON.stringify(cached && cached.user_info));

const firstHook = main.filter((r) => r.type === "SESSION_START")[0];
check(idMatches(firstHook, FIRST), "identity: hook record flattens payload extra.user to top level (5 keys)", "got=" + brief(firstHook));

const secondHook = main.filter((r) => r.type === "SESSION_END")[0];
check(idMatches(secondHook, SECOND), "identity: hook record refreshes when extra.user reappears", "got=" + brief(secondHook));

const plainHook = main.filter((r) => r.type === "USER_REQUEST")[0];
check(idMatches(plainHook, SECOND), "identity: hook record without extra.user is backfilled from the session cache", "got=" + brief(plainHook));

for (const t of derived) {
  const list = main.filter((r) => r.type === t);
  check(list.length >= 1 && list.every((r) => idMatches(r, SECOND)), "identity: transcript " + t + " backfilled from session cache (" + list.length + " found)", list.map((r) => brief(r)).join(", ") || "no derived record");
}

check(control.length === 0, "identity: control session without extra.user produces no records at all (attribution gate)", "leftovers=" + JSON.stringify(control.map((r) => r.type)));

// partial-key session: exactly those 2 keys on the hook record, merge-by-key
// in the cache, and both transcript rounds carry the right identity.
const partialHook = partial.filter((r) => r.type === "NOTIFICATION")[0];
check(!!partialHook && idMatches(partialHook, { email: "partial-override@sigmob.com", uid: "u-partial-override" }), "identity: partial-key event carries exactly its 2 keys at top level, no null values", "got=" + brief(partialHook));

const partialCached = state && state.__sessions ? state.__sessions[partialId] : null;
check(cacheMatches(partialCached, PMERGE), "identity: partial keys merge into the session cache (merge-by-key, no key dropped)", "cached=" + JSON.stringify(partialCached && partialCached.user_info));

const round1 = partial.filter((r) => ((r.type === "LLM_TOOL_CALL" || r.type === "TOOL_RESULT") && r.tool_call_id === "pt-call-1") || (r.type === "LLM_USAGE" && r.transcript_uuid === "pt-a1"));
check(round1.length === 3 && round1.every((r) => idMatches(r, PBASE)), "identity: partial-session transcript round 1 carries the base identity", "got=" + round1.map((r) => brief(r)).join(", "));
const round2 = partial.filter((r) => ((r.type === "LLM_TOOL_CALL" || r.type === "TOOL_RESULT") && r.tool_call_id === "pt-call-2") || (r.type === "LLM_USAGE" && r.transcript_uuid === "pt-a2"));
check(round2.length === 3 && round2.every((r) => idMatches(r, PMERGE)), "identity: partial-session transcript round 2 carries the merged key set", "got=" + round2.map((r) => brief(r)).join(", "));

// redaction / truncation probes: value-level filtering on the flattened fields.
const secretHook = records.filter((r) => r.session_id === secretId)[0];
check(!!secretHook && secretHook.uid === "[redacted jwt]" && !("user_info" in secretHook), "identity: JWT-shaped uid is masked (value-level redaction)", "got=" + brief(secretHook));

// The state.json session cache is persisted verbatim (saveStateLocked never
// runs applyRedaction), so the uid must already be masked at the
// extractUserInfo source for the cache - and the whole state file - to stay
// secret-free.
const secretCached = state && state.__sessions ? state.__sessions[secretId] : null;
check(!!secretCached && !!secretCached.user_info && secretCached.user_info.uid === "[redacted jwt]" && stateRaw.indexOf("eyJhbGciOiJIUzI1NiJ9") < 0, "identity: state.json cache stores the masked uid, no plaintext JWT in state", "cached=" + JSON.stringify(secretCached && secretCached.user_info) + ", plaintextJwtInState=" + (stateRaw.indexOf("eyJhbGciOiJIUzI1NiJ9") >= 0));

const longHook = records.filter((r) => r.session_id === longId)[0];
check(!!longHook && typeof longHook.email === "string" && longHook.email.length <= 400 && longHook.email.indexOf("[truncated") >= 0, "identity: oversized email is truncated in the flattened field", "len=" + (longHook && longHook.email ? longHook.email.length : "n/a") + " sample=" + JSON.stringify(longHook && longHook.email ? longHook.email.slice(0, 60) : null));

// Oversized secret-shaped uid under the same 300-char bound: redaction must
// run before the cut, so the whole credential collapses into the marker
// instead of leaking its plaintext head through a truncated tail.
const longSecretHook = records.filter((r) => r.session_id === longSecretId)[0];
check(!!longSecretHook && longSecretHook.uid === "[redacted jwt]" && longSecretHook.uid.indexOf("eyJ") < 0 && longSecretHook.uid.length < 400, "identity: oversized JWT-shaped uid is masked whole, no plaintext head past the truncate bound", "got=" + brief(longSecretHook) + " len=" + (longSecretHook && longSecretHook.uid ? longSecretHook.uid.length : "n/a"));

process.stdout.write(rows.join("\n") + "\n");
' "$UI_LOG_FILE" "$UI_STATE_FILE" "$UI_SESSION" "$UI_CONTROL" "$UI_PARTIAL" "$UI_SECRET" "$UI_LONG" "$UI_LONGSECRET" > "$UI_CHECKS" 2>&1

[ -s "$UI_CHECKS" ]; check "identity: verifier produced assertions" $?
# Anti-false-positive guard: a crashed or half-executed verifier script (syntax
# error, missing file, stray stdout) would leave the while loop below nothing
# to iterate, silently skipping every assertion. Counting the ok|fail lines and
# demanding the full expected total closes that hole; update UI_EXPECT when
# assertions are added above.
UI_EXPECT=16
# Character class [|] instead of an escaped \| : inside POSIX ERE the escape is
# unspecified and some greps treat it literally, while [|] is unambiguous.
UI_N=$(grep -cE '^(ok|fail)[|]' "$UI_CHECKS" 2>/dev/null || true); UI_N=${UI_N:-0}
[ "$UI_N" -ge "$UI_EXPECT" ]; check "identity: all $UI_EXPECT assertions really executed ($UI_N ok|fail lines)" $?
while IFS="|" read -r status label; do
  [ -n "$label" ] || continue
  if [ "$status" = "ok" ]; then check "$label" 0; else check "$label" 1; fi
done < "$UI_CHECKS"

if [ "$FAILURES" -gt 0 ]; then
  say "VERIFY FAILED ($FAILURES) - the plugin is installed but its audit trail is not usable"
  exit 1
fi
say "VERIFY OK - collection is end to end functional"
exit 0
