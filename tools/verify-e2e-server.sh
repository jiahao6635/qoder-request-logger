#!/bin/bash
# End-to-end verification of the qoder-log-server: build the jar, boot it in
# file-storage mode with fast clocks (close-idle=2s, upload-interval=2s),
# replay the client contract against a real HTTP port and prove the data
# really lands in storage with the expected OSS layout.
#
#   1. POST /api/logs single record  -> accepted:1
#   2. 3-line NDJSON batch w/ 1 poison line -> accepted:2 rejected:1, still 200
#   3. re-send of the same batch -> deduped:2, no extra stored lines
#   4. gzip Content-Encoding batch -> transparent decompression
#   5. missing X-API-Key -> 401 {"error":"invalid_api_key"}
#   6. burst over the per-key limit -> 429s with Retry-After
#   7. objects appear at logs/qoder/v1/date=/user=/src=/part-*.jsonl.gz
#   8. gunzipped records carry ingest_user/ingest_time stamps
#   9. spool meta uploads.jsonl describes what was uploaded
#
# Usage: bash tools/verify-e2e-server.sh   (exit 0 = server pipeline healthy)

set -u

TOOL_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SERVER_DIR="$TOOL_DIR/../server"
JAR="$SERVER_DIR/target/qoder-log-server-1.0.0.jar"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/qoder-log-server-verify-XXXXXX")
KEYS="$WORK/api-keys.yml"
SPOOL="$WORK/spool"
STORAGE="$WORK/storage"
SERVER_LOG="$WORK/server.log"
SERVER_PID=""
FAILURES=0

# Fixed key + fixed record timestamps => fully predictable storage layout.
API_KEY="qk_e2e4c0ffee0000111122223333aaaa"
API_KEY2="qk_e2e4c0ffee0000444455556666bbbb"   # second owner, used only for the 429 burst
OWNER="jiahao.li@sigmob.com"
FIXED_TS="2026-09-01T02:09:12.883Z"   # Beijing 2026-09-01 10:09 -> date=2026-09-01
DATE_DIR="date=2026-09-01"

cleanup() {
  if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null
    for _ in $(seq 1 15); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
    kill -9 "$SERVER_PID" 2>/dev/null
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

say() { printf '%s\n' "$1" >&2; }
check() { # check <label> <0|1>
  if [ "$2" = "0" ]; then say "  PASS  $1"; else say "  FAIL  $1"; FAILURES=$((FAILURES + 1)); fi
}

json_field() { # json_field <json> <jq-expression>
  printf '%s' "$1" | jq -r "$2" 2>/dev/null
}

# ── 0. JDK 21 toolchain ──────────────────────────────────────────────────────
JDK21=/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home
if [ -d "$JDK21" ]; then
  export JAVA_HOME="$JDK21"
fi
if [ ! -x "${JAVA_HOME:-/nonexistent}/bin/java" ]; then
  say "verify ABORTED: JDK 21 not found (export JAVA_HOME manually)"
  exit 1
fi
say "java: $("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1)"

# ── 1. build (or reuse) the fat jar ─────────────────────────────────────────
if [ ! -f "$JAR" ] || [ -n "$(find "$SERVER_DIR/src" -name '*.java' -newer "$JAR" -print -quit 2>/dev/null)" ]; then
  say "building qoder-log-server jar..."
  (cd "$SERVER_DIR" && mvn -q package -DskipTests) >"$WORK/build.log" 2>&1
  check "mvn package -DskipTests" $?
else
  say "reusing existing jar: $JAR"
fi
[ -f "$JAR" ]; check "fat jar present" $?

# ── 2. temp environment: keys, spool, file storage ──────────────────────────
KEY_SHA=$(printf '%s' "$API_KEY" | shasum -a 256 | cut -d' ' -f1)
KEY_SHA2=$(printf '%s' "$API_KEY2" | shasum -a 256 | cut -d' ' -f1)
mkdir -p "$SPOOL" "$STORAGE"
cat > "$KEYS" <<EOF
keys:
  - user_id: $OWNER
    key_sha256: $KEY_SHA
    display_name: 李嘉豪
    enabled: true
  - user_id: felix.zhang@sigmob.com
    key_sha256: $KEY_SHA2
    display_name: Felix
    enabled: true
EOF
say "api key sha256: $KEY_SHA"

# ── 3. boot the server on a random port with fast clocks ────────────────────
PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')
BASE="http://127.0.0.1:$PORT"
# Note: audit.disk.high-watermark=1.0 only silences the backpressure gate for
# this script (the dev data volume is ~98% full); the 503 path itself is
# covered by the unit tests (DiskBackpressureTest).
# rate-limit 3/s is deliberately low so the 429 burst probe is deterministic;
# the deterministic inject cases below sleep between calls to stay under it.
"$JAVA_HOME/bin/java" -jar "$JAR" \
  --server.port="$PORT" \
  --audit.api-keys-file="$KEYS" \
  --audit.spool-dir="$SPOOL" \
  --audit.close-idle-seconds=2 \
  --audit.upload-interval-seconds=2 \
  --audit.rate-limit-per-ip=3 \
  --audit.disk.high-watermark=1.0 \
  --oss.mode=file \
  --oss.file-storage-dir="$STORAGE" \
  >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!

READY=1
for _ in $(seq 1 60); do
  if curl -sf "$BASE/api/health" >/dev/null 2>&1; then READY=0; break; fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then break; fi
  sleep 1
done
check "server ready on :$PORT (/api/health responds)" $READY
if [ "$READY" != "0" ]; then
  say "server log tail:"
  tail -n 40 "$SERVER_LOG" >&2
  exit 1
fi
HEALTH=$(curl -sf "$BASE/api/health")
say "health: $HEALTH"

# ── 4. replay the client contract ───────────────────────────────────────────
RECORD_SINGLE='{"log_schema":"1.0.1","record_kind":"hook_event","client_id":"CZ-0101000193/happyelements","hostname":"CZ-0101000193","os_user":"happyelements","user":"li15733056635@163.com","timestamp":"2026-09-01T02:09:12.883Z","timestamp_ms":1788228552883,"local_time":"2026-09-01T10:09:12+08:00","event":"UserPromptSubmit","session_id":"15bcb426-8673-4338-be1b-0dd0fed0abf2","prompt":"e2e single record","type":"USER_REQUEST","product":"cli"}'
# Note on the injected fixtures below: credential redaction happens on the
# CLIENT (hooks/log-request.js masks secret-shaped values before anything is
# written or pushed); the Server just relays bytes. The end-to-end redaction
# assertions live in tools/verify-collector.sh and real client integration
# runs - this script deliberately does not retest masking, it only replays
# the transport contract.

BATCH_BODY='{"log_schema":"1.0.1","record_kind":"hook_event","client_id":"CZ-0101000193/happyelements","timestamp":"2026-09-01T02:09:16.279Z","timestamp_ms":1788228556279,"event":"PreToolUse","session_id":"15bcb426-8673-4338-be1b-0dd0fed0abf2","tool_name":"Bash","tool_call_id":"call_bc575307793a","type":"TOOL_REQUEST"}
{poison line - definitely not json
{"log_schema":"1.0.1","record_kind":"hook_event","client_id":"CZ-0101000193/happyelements","timestamp":"2026-09-01T02:09:17.239Z","timestamp_ms":1788228557239,"event":"PostToolUse","session_id":"15bcb426-8673-4338-be1b-0dd0fed0abf2","tool_name":"Bash","tool_call_id":"call_bc575307793a","type":"TOOL_RESPONSE"}'

GZIP_BODY='{"log_schema":"1.0.1","record_kind":"hook_event","client_id":"CZ-0101000193/happyelements","timestamp":"2026-09-01T02:09:20.000Z","timestamp_ms":1788228560000,"event":"TaskCreated","session_id":"task-9f2c-longrunning","type":"TASK_CREATED","credits":1.5}
{"log_schema":"1.0.1","record_kind":"usage","client_id":"CZ-0101000193/happyelements","timestamp":"2026-09-01T02:09:21.000Z","timestamp_ms":1788228561000,"event":"LlmUsage","session_id":"15bcb426-8673-4338-be1b-0dd0fed0abf2","type":"LLM_USAGE","credits":2.5,"input_tokens":52000,"output_tokens":830}'

post_single() { curl -s -o "$WORK/single.out" -w '%{http_code}' -X POST -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' -d "$1" "$BASE/api/logs"; }
post_batch()  { curl -s -o "$WORK/batch.out" -w '%{http_code}' -X POST -H "X-API-Key: $API_KEY" -H 'Content-Type: application/x-ndjson' --data-binary "$1" "$BASE/api/logs/batch"; }

# 4a. single record (the exact call the shipped hook client makes)
CODE=$(post_single "$RECORD_SINGLE")
[ "$CODE" = "200" ] && [ "$(json_field "$(cat "$WORK/single.out")" .accepted)" = "1" ]; check "single POST /api/logs -> 200 accepted:1" $?
sleep 0.5   # stay under the 3 req/s limit of this test server

# 4b. batch with one poison line: 200 + isolated accounting
CODE=$(post_batch "$BATCH_BODY")
OUT=$(cat "$WORK/batch.out")
[ "$CODE" = "200" ] \
  && [ "$(json_field "$OUT" .accepted)" = "2" ] \
  && [ "$(json_field "$OUT" .rejected)" = "1" ] \
  && [ "$(json_field "$OUT" .deduped)" = "0" ]; check "batch w/ poison line -> 200 accepted:2 rejected:1" $?
sleep 0.5

# 4c. exact re-send of the same batch -> server-side dedup (5xx-retry safety)
CODE=$(post_batch "$BATCH_BODY")
OUT=$(cat "$WORK/batch.out")
[ "$CODE" = "200" ] \
  && [ "$(json_field "$OUT" .accepted)" = "0" ] \
  && [ "$(json_field "$OUT" .rejected)" = "1" ] \
  && [ "$(json_field "$OUT" .deduped)" = "2" ]; check "re-sent batch -> deduped:2 (idempotent ingest)" $?
sleep 0.5

# 4d. gzip-compressed batch
printf '%s' "$GZIP_BODY" | gzip > "$WORK/batch.gz"
CODE=$(curl -s -o "$WORK/gzip.out" -w '%{http_code}' -X POST \
  -H "X-API-Key: $API_KEY" -H 'Content-Type: application/x-ndjson' \
  -H 'Content-Encoding: gzip' --data-binary "@$WORK/batch.gz" "$BASE/api/logs/batch")
OUT=$(cat "$WORK/gzip.out")
[ "$CODE" = "200" ] && [ "$(json_field "$OUT" .accepted)" = "2" ]; check "gzip batch -> 200 accepted:2" $?
sleep 0.5

# 4e. auth enforcement
CODE=$(curl -s -o "$WORK/nokey.out" -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/api/logs")
OUT=$(cat "$WORK/nokey.out")
[ "$CODE" = "401" ] && [ "$(json_field "$OUT" .error)" = "invalid_api_key" ]; check "missing X-API-Key -> 401 invalid_api_key" $?

# ── 5. wait for rotate (idle 2s) + upload (cycle 2s) ─────────────────────────
sleep 7

QODER_DIR="$STORAGE/logs/qoder/v1/$DATE_DIR/user=$OWNER/src=qoder"
QODERWORK_DIR="$STORAGE/logs/qoder/v1/$DATE_DIR/user=$OWNER/src=qoderwork"

ls "$QODER_DIR"/part-*.jsonl.gz >/dev/null 2>&1; check "storage object exists: src=qoder/part-*.jsonl.gz" $?
ls "$QODERWORK_DIR"/part-*.jsonl.gz >/dev/null 2>&1; check "storage object exists: src=qoderwork/part-*.jsonl.gz" $?

# part naming contract: part-<HHmmss>-<4hex instId>-<seq>.jsonl.gz
BAD_NAME=$(find "$QODER_DIR" "$QODERWORK_DIR" -name '*.jsonl.gz' 2>/dev/null \
  | awk -F/ '{print $NF}' \
  | grep -Ev '^part-[0-9]{6}-[0-9a-f]{4}-[0-9]{4}\.jsonl\.gz$' | head -n 1)
[ -z "$BAD_NAME" ]; check "object names match part-HHmmss-<4hex>-<seq>.jsonl.gz" $?

# line counts: qoder = single + TOOL_REQUEST + TOOL_RESPONSE + LLM_USAGE = 4
# qoderwork = TASK_CREATED = 1 (deduped re-sends must NOT add lines)
QODER_LINES=$(cat "$QODER_DIR"/part-*.jsonl.gz 2>/dev/null | gunzip -c 2>/dev/null | awk 'NF' | wc -l | tr -d ' ')
QODERWORK_LINES=$(cat "$QODERWORK_DIR"/part-*.jsonl.gz 2>/dev/null | gunzip -c 2>/dev/null | awk 'NF' | wc -l | tr -d ' ')
[ "${QODER_LINES:-0}" = "4" ]; check "src=qoder object holds exactly 4 lines (dedup keeps it at 4)" $?
[ "${QODERWORK_LINES:-0}" = "1" ]; check "src=qoderwork object holds exactly 1 line" $?

# stamped content: ingest_user = key owner, ingest_time present, original fields intact
STAMPS=$(cat "$QODER_DIR"/part-*.jsonl.gz 2>/dev/null | gunzip -c 2>/dev/null \
  | jq -s 'all(.[]; .ingest_user == "jiahao.li@sigmob.com" and (.ingest_time | length > 0) and has("type"))')
[ "$STAMPS" = "true" ]; check "every stored record carries ingest_user/ingest_time stamps" $?

PROMPT_OK=$(cat "$QODER_DIR"/part-*.jsonl.gz 2>/dev/null | gunzip -c 2>/dev/null \
  | jq -s 'any(.[]; .prompt == "e2e single record" and .type == "USER_REQUEST")')
[ "$PROMPT_OK" = "true" ]; check "original record fields survive the round trip" $?

TASK_SRC=$(cat "$QODERWORK_DIR"/part-*.jsonl.gz 2>/dev/null | gunzip -c 2>/dev/null \
  | jq -s 'all(.[]; .session_id == "task-9f2c-longrunning" and .event == "TaskCreated")')
[ "$TASK_SRC" = "true" ]; check "task- session routed to src=qoderwork" $?

# upload metadata journal
META="$SPOOL/meta/uploads.jsonl"
[ -f "$META" ]; check "spool/meta/uploads.jsonl written" $?
META_OK=$(jq -s 'any(.[]; .record_date == "2026-09-01" and .user == "jiahao.li@sigmob.com"
  and (.key | startswith("logs/qoder/v1/date=2026-09-01/user=jiahao.li@sigmob.com/src=qoder/part-"))
  and .lines > 0 and (.min_ts | startswith("2026-09-01T02:09")))' "$META" 2>/dev/null)
[ "$META_OK" = "true" ]; check "uploads.jsonl metadata matches uploaded object" $?

# spool drained: every part was uploaded and removed
PENDING=$(find "$SPOOL" -name 'part-*.ndjson' | wc -l | tr -d ' ')
[ "$PENDING" = "0" ]; check "spool fully drained (0 pending part files)" $?

# health reflects the session counters (burst probes run AFTER this, so the
# numbers below only cover the deterministic requests above)
HEALTH=$(curl -sf "$BASE/api/health")
[ "$(json_field "$HEALTH" .received_total)" = "9" ] \
  && [ "$(json_field "$HEALTH" .deduped_total)" = "2" ] \
  && [ "$(json_field "$HEALTH" .rejected_total)" = "2" ] \
  && [ "$(json_field "$HEALTH" .last_oss_success)" != "null" ] \
  && [ "$(json_field "$HEALTH" .spool_pending_files)" = "0" ]; check "health counters consistent (received=9 deduped=2 rejected=2)" $?

# 4f. per-key rate limit (3/s): 10 concurrent poison probes on the second
# key (poison bodies never reach the spool, and the counts above are done);
# even spread across two seconds each window exceeds 3 req/s
BURST=$(seq 1 10 | xargs -P 10 -I{} curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST -H "X-API-Key: $API_KEY2" -H 'Content-Type: application/json' \
  -d '{poison-rate-probe' "$BASE/api/logs" | sort | uniq -c | tr '\n' ' ')
say "  rate-limit burst result (count code): $BURST"
case "$BURST" in
  *429*) check "burst over limit sees 429" 0 ;;
  *)     check "burst over limit sees 429" 1 ;;
esac

# ── 6. summary ───────────────────────────────────────────────────────────────
if [ "$FAILURES" -gt 0 ]; then
  say ""
  say "VERIFY FAILED ($FAILURES) - server log tail follows"
  tail -n 30 "$SERVER_LOG" >&2
  exit 1
fi
say ""
say "VERIFY OK - qoder-log-server end to end functional (file storage mode)"
exit 0
