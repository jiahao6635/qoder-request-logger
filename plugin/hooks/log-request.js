#!/usr/bin/env node
/**
 * Qoder request logger - full-fidelity agent audit collector.
 *
 * Answers four questions for every agent action:
 *   WHO      - the enterprise identity (payload extra.user) cached per session,
 *              flattened onto every saved record as top-level email / name /
 *              org_id / org_name / uid (schema 1.1.0); the legacy machine-level
 *              fields (client_id, hostname, os_user, user) are no longer saved.
 *              Records that end up without an enterprise identity are dropped -
 *              an unattributable record could never be queried back to a
 *              person, so it is never saved or uploaded.
 *   WHEN     - record timestamp (UTC + local), and the request/response times,
 *              repository and branch reported by the hook payload
 *   REQUEST  - the prompt, or the tool name plus its full input arguments
 *   RESPONSE - what came back: tool response, tool_result blocks, errors, and
 *              the assistant's final message
 *
 * Hook stdin carries no billing data. Credits and token usage live in the
 * session transcript JSONL referenced by `transcript_path`, on the `usage`
 * object of each `assistant` record. This collector therefore remembers a byte
 * offset per transcript and parses only the newly appended tail on each run, so
 * Credits, tokens, cache reads and context growth are recorded exactly once per
 * model call without re-reading large session files.
 *
 * Channels:
 *   1. Local JSONL, always on:  $QODER_LOG_DIR/requests_YYYY-MM-DD.jsonl
 *   2. HTTP POST, opt-in:       $QODER_LOG_SERVER_URL
 *      - legacy (default): one POST per record, failures queue to outbox;
 *        a rejected key (401/403) trips the shared 24h breaker instead, so
 *        those records are dropped rather than cycled through the outbox
 *      - cursor: gzip batches of complete lines from the daily files to
 *        {origin}/api/logs/batch, advancing a per-file byte offset stored in
 *        .request-logger/upload-state.json (any failure leaves the offset
 *        untouched, so the same bytes replay next round; server dedupes)
 *
 * Configuration (environment, normally set in hooks/hooks.json):
 *   QODER_LOG_SERVER_URL         Receiver base or full URL. Empty disables push.
 *   QODER_LOG_API_KEY            Sent as the X-API-Key header. When empty,
 *                                falls back to the per-machine credentials file
 *                                (see QODER_LOG_CREDENTIALS_FILE).
 *   QODER_LOG_USER_ID            Member identity override; same credentials-file
 *                                fallback as the API key.
 *   QODER_LOG_CREDENTIALS_FILE   Per-machine credentials file read when the API
 *                                key / user id env values are empty. Default:
 *                                ~/.qoder/log-credentials.json, format
 *                                {"api_key":"qk_...","user_id":"first.last@..."},
 *                                provisioned by IT so one fleet-wide plugin
 *                                package can carry per-person credentials.
 *   QODER_LOG_DIR                Log directory. Default: ~/.qoder/logs
 *   QODER_LOG_TRUNCATE           Per-field character cap. Default: 20000
 *   QODER_LOG_MAX_FILE_MB        Rotate the daily file above this size. Default: 64
 *   QODER_LOG_RAW                "0" drops the verbatim stdin copy. Default: 1
 *   QODER_LOG_INCLUDE_TRANSCRIPT "0" skips transcript harvesting. Default: 1
 *   QODER_LOG_TRANSCRIPT_PROMPT  "1" also replays prompts from the transcript.
 *   QODER_LOG_REDACT             "0" disables secret masking. Default: 1
 *   QODER_LOG_HTTP_TIMEOUT_MS    Push timeout. Default: 5000
 *   QODER_LOG_UPLOAD_MODE        off | legacy | cursor. Default: legacy (the
 *                                historical per-record push, unchanged)
 *   QODER_LOG_UPLOAD_INTERVAL_SEC Min seconds between cursor attempts; 0 = no
 *                                throttle, every hook run may upload. Default: 60
 *   QODER_LOG_LOCAL_RETENTION_DAYS Delete fully-uploaded daily files older than
 *                                N days. 0 (default) keeps everything forever.
 *   QODER_LOG_BATCH_MAX_LINES     Max lines per cursor batch. Default: 2000
 *   QODER_LOG_BATCH_MAX_MB        Max uncompressed megabytes per cursor batch.
 *                                Default: 6; keep it under the server's
 *                                AUDIT_MAX_BODY_MB=8 decompressed ceiling.
 *
 * Zero dependencies. A logger must never interfere with the agent, so this
 * script never writes to stdout and always exits 0.
 */

"use strict";

const fs = require("fs");
const path = require("path");
const os = require("os");
const http = require("http");
const https = require("https");
const zlib = require("zlib");
const { execFileSync } = require("child_process");

const LOGGER_VERSION = "1.1.0";
const ENV = process.env;
const SELF_TEST = process.argv[2] === "--self-test";

// Per-machine credentials file for fleet-wide plugin distributions. IT ships
// one identical plugin package to everyone and provisions this file per
// machine, so personal keys never travel inside the plugin itself.
const CREDENTIALS_FILE = ENV.QODER_LOG_CREDENTIALS_FILE
  || path.join(os.homedir(), ".qoder", "log-credentials.json");

// Set when the credentials file exists but is unusable; surfaced once through
// the error log by the next handled event. Never carries file contents, so no
// key material can leak into diagnostics.
let credentialIssue = null;

/**
 * Identity resolution: environment values (personalised hooks.json) win;
 * empty values fall back to the credentials file. Each call re-evaluates and
 * resets {@code credentialIssue}. A missing file is the expected
 * not-yet-provisioned state (silent, local-only mode); a file that exists but
 * is incomplete or malformed records an issue for diagnostics.
 */
function resolveIdentity(envApiKey, envUserId, credentialsFile) {
  credentialIssue = null;
  const envKey = typeof envApiKey === "string" ? envApiKey.trim() : "";
  const envUser = typeof envUserId === "string" ? envUserId.trim() : "";
  let fileKey = "";
  let fileUser = "";
  let fileExists = false;
  let parsed = null;
  try {
    parsed = JSON.parse(fs.readFileSync(credentialsFile, "utf8"));
  } catch (err) {
    if (err.code !== "ENOENT") {
      // SyntaxError messages echo file fragments, so classify instead of
      // forwarding err.message - diagnostics must never carry key material.
      credentialIssue = err instanceof SyntaxError ? "malformed JSON"
        : "unreadable: " + (err.code || err.message);
    }
    // ENOENT: not provisioned yet - the silent local-only default.
  }
  if (parsed !== null) {
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      fileExists = true;
      if (typeof parsed.api_key === "string") fileKey = parsed.api_key.trim();
      if (typeof parsed.user_id === "string") fileUser = parsed.user_id.trim();
    } else {
      credentialIssue = "not a JSON object";
    }
  }
  if (!credentialIssue && fileExists) {
    const keyOk = envKey || fileKey;
    const userOk = envUser || fileUser;
    if (!keyOk || !userOk) {
      credentialIssue = "missing " + (!keyOk ? "api_key" : "user_id");
    }
  }
  return { apiKey: envKey || fileKey, userId: envUser || fileUser };
}

const IDENTITY = resolveIdentity(ENV.QODER_LOG_API_KEY, ENV.QODER_LOG_USER_ID, CREDENTIALS_FILE);

const CONFIG = {
  serverUrl: ENV.QODER_LOG_SERVER_URL || "",
  apiKey: IDENTITY.apiKey,
  userId: IDENTITY.userId,
  credentialsFile: CREDENTIALS_FILE,
  logDir: ENV.QODER_LOG_DIR || path.join(os.homedir(), ".qoder", "logs"),
  truncate: intOpt(ENV.QODER_LOG_TRUNCATE, 20000),
  maxFileBytes: intOpt(ENV.QODER_LOG_MAX_FILE_MB, 64) * 1024 * 1024,
  keepRaw: ENV.QODER_LOG_RAW !== "0",
  harvestTranscript: ENV.QODER_LOG_INCLUDE_TRANSCRIPT !== "0",
  transcriptPrompt: ENV.QODER_LOG_TRANSCRIPT_PROMPT === "1",
  redact: ENV.QODER_LOG_REDACT !== "0",
  httpTimeoutMs: intOpt(ENV.QODER_LOG_HTTP_TIMEOUT_MS, 5000),
  // Upload channel selection. "legacy" keeps the historical per-record push
  // byte-for-byte; "cursor" switches to offset-tracked batch upload; "off",
  // an unknown value, or an empty serverUrl all disable HTTP entirely.
  uploadMode: normalizeUploadMode(ENV.QODER_LOG_UPLOAD_MODE),
  // Interval 0 is a legal value here: it disables the cursor throttle
  // entirely (every hook run may attempt an upload), which tests and demos
  // rely on. All other intOpt callers keep the default minimum of 1.
  uploadIntervalSec: intOpt(ENV.QODER_LOG_UPLOAD_INTERVAL_SEC, 60, 0),
  localRetentionDays: intOpt(ENV.QODER_LOG_LOCAL_RETENTION_DAYS, 0),
  batchMaxLines: intOpt(ENV.QODER_LOG_BATCH_MAX_LINES, 2000, 1),
  batchMaxBytes: intOpt(ENV.QODER_LOG_BATCH_MAX_MB, 6, 1) * 1024 * 1024,
  cursorBudgetMs: 10 * 1000,
  cursorMaxBatches: 3,
  maxTailBytes: 4 * 1024 * 1024,
  outboxMaxBytes: 8 * 1024 * 1024,
  drainBatch: 200,
};

// Paths are resolved lazily because the self test relocates CONFIG.logDir.
const P = {
  root: () => CONFIG.logDir,
  state: () => path.join(CONFIG.logDir, ".request-logger"),
  stateFile: () => path.join(CONFIG.logDir, ".request-logger", "state.json"),
  summary: () => path.join(CONFIG.logDir, ".request-logger", "session-summary.json"),
  outbox: () => path.join(CONFIG.logDir, ".request-logger", "outbox.ndjson"),
  uploadState: () => path.join(CONFIG.logDir, ".request-logger", "upload-state.json"),
  error: () => path.join(CONFIG.logDir, ".request-logger", "logger-error.log"),
  lock: () => path.join(CONFIG.logDir, ".request-logger", "lock"),
};

const HOSTNAME = os.hostname();
const OS_USER = safe(() => os.userInfo().username, "unknown");
const CLIENT_ID = CONFIG.userId || HOSTNAME + "/" + OS_USER;

// ─── Entry point ────────────────────────────────────────────────────────────

/**
 * Dispatch is defined here but invoked at the very bottom of the file.
 *
 * `runSelfTest()` runs synchronously, so calling it from the middle of the
 * module would read `let`/`const` bindings declared further down and throw a
 * TDZ ReferenceError. Deferring the call to the last line guarantees every
 * top-level binding, and therefore every helper, is initialised first.
 */
function main() {
  if (SELF_TEST) {
    runSelfTest();
    return;
  }

  let stdinData = "";
  process.stdin.setEncoding("utf8");
  process.stdin.on("data", (chunk) => { stdinData += chunk; });
  process.stdin.on("error", () => finish());
  process.stdin.on("end", () => {
    try {
      handleEvent(stdinData);
    } catch (err) {
      reportError(err, { stage: "handleEvent" });
    }
    finish();
  });
  // Never let a stalled stdin hang the agent past the hook timeout.
  setTimeout(() => finish(), 15000).unref();
}

let exiting = false;

/**
 * Flush queued records, giving in-flight HTTP pushes a short window to settle,
 * then always allow the agent to proceed.
 */
function finish() {
  if (exiting) return;
  exiting = true;
  try {
    flushPending();
  } catch (err) {
    reportError(err, { stage: "finish" });
  }
  // Cursor mode: the local write is complete, so hand the backlog to the
  // batch uploader now. Its in-flight requests bump the same activeRequests
  // counter, so they settle inside the existing wait window below and the
  // exit guarantees of finish() are never extended.
  if (CONFIG.uploadMode === "cursor" && CONFIG.serverUrl) {
    try {
      cursorUploadTick();
    } catch (err) {
      reportError(err, { stage: "cursorUpload" });
    }
  }
  if (activeRequests > 0) {
    // Keep the process alive just long enough for the sockets to complete.
    const deadline = Date.now() + Math.min(CONFIG.httpTimeoutMs + 300, 6000);
    const poll = setInterval(() => {
      if (activeRequests <= 0 || Date.now() > deadline) {
        clearInterval(poll);
        process.exit(0);
      }
    }, 50);
    poll.unref();
    setTimeout(() => process.exit(0), CONFIG.httpTimeoutMs + 800).unref();
    return;
  }
  process.exit(0);
}

// ─── Event handling ─────────────────────────────────────────────────────────

function handleEvent(raw) {
  // Surface a credentials-file problem before anything else, once per process
  // (hook runs are one-shot). Path only - never the file contents.
  if (credentialIssue) {
    const issue = credentialIssue;
    credentialIssue = null;
    reportError(new Error("credentials file unusable: " + issue + " (" + CONFIG.credentialsFile + ")"),
      { stage: "credentials" });
  }
  if (!raw || !raw.trim()) return;

  let input;
  try {
    input = JSON.parse(raw);
  } catch (err) {
    input = { hook_event_name: "UnparsableStdin", _unparsable: String(raw).slice(0, 4000) };
  }
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    input = { hook_event_name: "UnparsableStdin", _unparsable: String(raw).slice(0, 4000) };
  }

  const eventName = str(input.hook_event_name) || "UnknownEvent";
  const base = buildBaseRecord(input, eventName);
  const record = Object.assign(base, enrich(eventName, input));

  const state = readStateLocked();
  try {
    // The Qoder Quest window ships two hook dispatchers that each execute
    // every plugin hook for the same event. markSeen() records the event's
    // identity key inside the shared state lock, so only the first dispatch
    // reaches emit() and session counters stay accurate.
    if (markSeen(state, record)) return;
    const session = ensureSession(state, record);
    cacheUserInfo(session, input);
    // Attribution requirement: a record with no enterprise identity can never
    // be queried back to a person downstream, so it must not be saved. Hook
    // events that carry no extra.user of their own are backfilled from the
    // session cache; whatever still lacks user_info is dropped in emit().
    if (!("user_info" in record) && session.user_info) {
      record.user_info = Object.assign({}, session.user_info);
    }
    // The local session summary keeps the enterprise email as the session's
    // human identity whenever one is known; saved records themselves carry
    // the flattened identity (see emit()).
    const enterpriseEmail = str(record.user_info && record.user_info.email);
    if (enterpriseEmail) session.user = enterpriseEmail;
    else if (!session.user) session.user = resolveUser(input, pickObject(input.extra) || {});
    emit(record);
    if (CONFIG.harvestTranscript && input.transcript_path) {
      try {
        harvestTranscript(input, base, state, session);
      } catch (err) {
        reportError(err, { stage: "transcript", event: eventName });
      }
    }
    if (session.model_calls || session.credits) writeSummary(state);
  } finally {
    saveStateLocked(state);
  }
}

/**
 * Identity, timing and session context shared by every record.
 */
function buildBaseRecord(input, eventName) {
  const now = new Date();
  const extra = pickObject(input.extra) || {};
  const business = pickObject(input.parent_business_info) || {};

  const record = {
    log_schema: LOGGER_VERSION,
    record_kind: "hook_event",
    // Structured enterprise identity from payload extra.user, when present.
    // emit() flattens it to the top level and strips the legacy machine-level
    // identity fields; records without it are dropped.
    user_info: extractUserInfo(extra) || undefined,
    timestamp: now.toISOString(),
    timestamp_ms: now.getTime(),
    local_time: localTime(now),
    event: eventName,
    session_id: str(input.session_id) || "unknown",
    agent_id: str(input.agent_id) || undefined,
    agent_type: str(input.agent_type) || undefined,
    model: str(input.model) || undefined,
    permission_mode: str(input.permission_mode) || undefined,
    cwd: str(input.cwd) || undefined,
    transcript_path: str(input.transcript_path) || undefined,
    request_set_id: str(input.request_set_id || input.parent_request_set_id) || undefined,
    // Where the action happened: repository and branch from the hook payload.
    repo: str(extra.repo) || undefined,
    git_branch: str(extra.branch) || undefined,
    request_time: str(extra.request_time) || undefined,
    response_time: str(extra.response_time) || undefined,
    diff_text: typeof extra.full_diff_text === "string" ? cut(extra.full_diff_text) : undefined,
    // Product metadata observed on real Qoder payloads.
    product: str(business.product) || undefined,
    product_version: str(business.version) || undefined,
    business_stage: str(business.stage) || undefined,
  };

  if (CONFIG.keepRaw) record._raw = cut(safeJson(input));

  return dropUndefined(record);
}

/**
 * Last-resort identity kept only for the local session summary while no
 * enterprise identity is known, in priority order: explicit member id, the Git
 * email reported by the hook payload, local Git configuration, OS account.
 * Saved records carry the flattened enterprise identity instead (see emit()).
 */
function resolveUser(input, extra) {
  if (CONFIG.userId) return CONFIG.userId;
  const fromPayload = str(extra.email);
  if (fromPayload) return fromPayload;
  const fromGit = gitEmail(input.cwd);
  if (fromGit) return fromGit;
  return OS_USER + "@" + HOSTNAME;
}

// ─── Enterprise identity (payload extra.user) ─────────────────────────────
//
// IDE-dispatched hook events carry the signed-in member profile on
// payload.extra.user, e.g.
//   {"email":"jiahao.li@sigmob.com","name":"嘉豪 李","org_id":"019cbcf2-…",
//    "org_name":"sigmob","uid":"019efd72-…"}
// qodercli CLI sessions have no `extra` at all. Until schema 1.1.0 this object
// only survived inside _raw; it is now cached per session (state.json, keyed
// by session_id, merged by key) and mirrored onto records as the structured
// `user_info` field, so transcript-derived records - which never see the
// hook payload - stay attributable even without _raw.

const USER_INFO_KEYS = ["email", "name", "org_id", "org_name", "uid"];

/**
 * Copy the known identity keys out of payload extra.user. Unknown keys are
 * ignored, absent keys simply stay absent (never null). Every surviving value
 * gets the same hygiene here - value-level redaction (when CONFIG.redact is
 * on) plus cut() truncation - because this one object travels to three
 * places: the hook record's user_info field, the session cache inside
 * state.json (saveStateLocked persists it verbatim, never applyRedaction)
 * and the transcript backfill. Sanitising at the source keeps all three
 * consistent. Redaction runs before cut() so a credential longer than the
 * truncate bound is masked whole instead of leaking its head through the
 * cut; "[redacted ...]" markers match no secret pattern, so the emit path's
 * applyRedaction pass over already-masked user_info values is an idempotent
 * no-op.
 * Returns null when there is nothing usable.
 */
function extractUserInfo(extra) {
  const source = pickObject(extra && extra.user);
  if (!source) return null;
  const info = {};
  for (const key of USER_INFO_KEYS) {
    let value = str(source[key]);
    if (CONFIG.redact) value = redactString(value);
    value = cut(value);
    if (value) info[key] = value;
  }
  return Object.keys(info).length ? info : null;
}

/**
 * Merge by key (latest wins per key): an event carrying only some keys
 * (e.g. just uid) refreshes those keys without dropping the others already
 * cached; a key, once written, stays until the session entry itself is
 * pruned. Runs inside the shared state lock, so concurrent dispatchers
 * serialise on the same merge.
 */
function cacheUserInfo(session, input) {
  const info = extractUserInfo(input && input.extra);
  // pickObject guards against externally polluted state: if a hand-edited
  // state.json ever holds user_info as a string/array, Object.assign would
  // copy its characters/elements into bogus numeric keys that then propagate
  // into every derived record. A non-object cache is simply discarded.
  if (info) session.user_info = Object.assign({}, pickObject(session.user_info) || {}, info);
}

// Single memo slot: the identity of a session never changes, so one `git config`
// probe per collector process is enough.
let gitEmailCache;
function gitEmail(cwd) {
  if (gitEmailCache !== undefined) return gitEmailCache;
  gitEmailCache = "";
  try {
    gitEmailCache = execFileSync("git", ["config", "--get", "user.email"], {
      cwd: cwd || process.cwd(),
      encoding: "utf8",
      timeout: 700,
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
  } catch (err) {
    // Git absent, outside a repo, or slow: fall back to config file parsing.
    gitEmailCache = readEmailFromGitConfig(cwd) || "";
  }
  return gitEmailCache;
}

function readEmailFromGitConfig(cwd) {
  const candidates = [];
  if (cwd) {
    let dir = path.resolve(cwd);
    for (let depth = 0; depth < 6; depth += 1) {
      candidates.push(path.join(dir, ".git", "config"));
      const parent = path.dirname(dir);
      if (parent === dir) break;
      dir = parent;
    }
  }
  candidates.push(path.join(os.homedir(), ".gitconfig"));
  for (const file of candidates) {
    try {
      const match = fs.readFileSync(file, "utf8").match(/email\s*=\s*([^;\r\n]+)/i);
      if (match) return match[1].trim();
    } catch (err) { /* try the next candidate */ }
  }
  return "";
}

/**
 * Event-specific request / response fields. Aliases matter: the IDE documents
 * `tool_response` while other entry points deliver `tool_output`, and a call id
 * may arrive as `tool_use_id` or `tool_call_id`. Every alias is probed so no
 * response content is silently dropped.
 */
function enrich(eventName, input) {
  const out = { type: eventName.toUpperCase() };
  const toolResponse = firstDefined(input.tool_response, input.tool_output, input.output, input.result);
  const toolCallId = str(input.tool_use_id || input.tool_call_id) || undefined;
  const toolName = str(input.tool_name) || undefined;
  const mcpContext = pickObject(input.mcp_context) || {};

  switch (eventName) {
    case "UserPromptSubmit":
      out.type = "USER_REQUEST";
      out.prompt = cut(str(input.prompt || input.user_prompt));
      out.prompt_id = str(input.prompt_id) || undefined;
      break;

    case "PreToolUse":
      out.type = "TOOL_REQUEST";
      out.tool_name = toolName;
      out.tool_input = cut(safeJson(input.tool_input));
      out.tool_call_id = toolCallId;
      out.mcp_server = str(mcpContext.server_name) || undefined;
      out.original_request_name = str(input.original_request_name) || undefined;
      break;

    case "PostToolUse":
      out.type = "TOOL_RESPONSE";
      out.tool_name = toolName;
      out.tool_input = cut(safeJson(input.tool_input));
      out.tool_response = cut(safeJson(toolResponse));
      out.tool_call_id = toolCallId;
      out.duration_ms = numOpt(input.duration_ms);
      out.original_request_name = str(input.original_request_name) || undefined;
      out.mcp_server = str(mcpContext.server_name) || undefined;
      break;

    case "PostToolUseFailure":
      out.type = "TOOL_RESPONSE_FAILURE";
      out.tool_name = toolName;
      out.tool_input = cut(safeJson(input.tool_input));
      out.tool_call_id = toolCallId;
      out.error = cut(str(input.error));
      out.error_type = str(input.error_type) || undefined;
      out.is_interrupt = typeof input.is_interrupt === "boolean" ? input.is_interrupt : undefined;
      break;

    case "PermissionRequest":
      out.type = "PERMISSION_REQUEST";
      out.tool_name = toolName;
      out.tool_input = cut(safeJson(input.tool_input));
      out.tool_call_id = toolCallId;
      break;

    case "PermissionDenied":
      out.type = "PERMISSION_DENIED";
      out.tool_name = toolName;
      out.tool_input = cut(safeJson(input.tool_input));
      out.reason = cut(str(input.reason || input.permissionDecisionReason));
      break;

    case "Stop":
      out.type = "AGENT_STOP";
      out.stop_hook_active = input.stop_hook_active;
      out.last_assistant_message = cut(str(input.last_assistant_message));
      out.stop_reason = str(input.stop_reason) || undefined;
      break;

    case "StopFailure":
      out.type = "AGENT_STOP_FAILURE";
      out.error = cut(str(input.error));
      out.error_type = str(input.error_type) || undefined;
      break;

    case "SubagentStart":
      out.type = "SUBAGENT_START";
      out.subagent_id = str(input.agent_id) || undefined;
      out.subagent_type = str(input.agent_type) || undefined;
      break;

    case "SubagentStop":
      out.type = "SUBAGENT_STOP";
      out.subagent_id = str(input.agent_id) || undefined;
      out.subagent_type = str(input.agent_type) || undefined;
      out.subagent_transcript_path = str(input.agent_transcript_path) || undefined;
      out.last_assistant_message = cut(str(input.last_assistant_message));
      out.stop_hook_active = input.stop_hook_active;
      break;

    case "SessionStart":
      out.type = "SESSION_START";
      out.source = str(input.source || input.type) || undefined;
      break;

    case "SessionEnd":
      out.type = "SESSION_END";
      out.reason = str(input.reason || input.exit_reason) || undefined;
      break;

    case "PreCompact":
      out.type = "PRE_COMPACT";
      out.trigger = str(input.trigger) || undefined;
      out.custom_instructions = cut(str(input.custom_instructions));
      break;

    case "PostCompact":
      out.type = "POST_COMPACT";
      out.trigger = str(input.trigger) || undefined;
      out.compact_summary = cut(str(input.compact_summary));
      break;

    case "Notification":
      out.type = "NOTIFICATION";
      out.notification_type = str(input.notification_type) || undefined;
      out.title = str(input.title) || undefined;
      out.message = cut(str(input.message));
      break;

    case "ConfigChange":
      out.type = "CONFIG_CHANGE";
      out.source = str(input.source) || undefined;
      out.file_path = str(input.file_path) || undefined;
      break;

    case "InstructionsLoaded":
      out.type = "INSTRUCTIONS_LOADED";
      out.file_path = str(input.file_path) || undefined;
      out.memory_type = str(input.memory_type) || undefined;
      out.load_reason = str(input.load_reason) || undefined;
      break;

    case "CwdChanged":
      out.type = "CWD_CHANGED";
      out.old_cwd = str(input.old_cwd) || undefined;
      out.new_cwd = str(input.new_cwd) || undefined;
      break;

    case "FileChanged":
      out.type = "FILE_CHANGED";
      out.file_path = str(input.file_path || input.path) || undefined;
      out.change_type = str(input.change_type) || undefined;
      break;

    case "WorktreeCreate":
      out.type = "WORKTREE_CREATE";
      out.name = str(input.name) || undefined;
      break;

    case "WorktreeRemove":
      out.type = "WORKTREE_REMOVE";
      out.worktree_path = str(input.worktree_path) || undefined;
      break;

    case "TaskCreated":
      out.type = "TASK_CREATED";
      out.task_id = str(input.task_id) || undefined;
      out.task_name = str(input.task_name) || undefined;
      break;

    case "TaskCompleted":
      out.type = "TASK_COMPLETED";
      out.task_id = str(input.task_id) || undefined;
      out.task_name = str(input.task_name) || undefined;
      out.task_status = str(input.status || input.task_status) || undefined;
      break;

    case "Elicitation":
      out.type = "ELICITATION";
      out.mcp_server_name = str(input.mcp_server_name) || undefined;
      out.message = cut(str(input.message));
      out.requested_schema = cut(safeJson(input.requested_schema));
      break;

    case "ElicitationResult":
      out.type = "ELICITATION_RESULT";
      out.mcp_server_name = str(input.mcp_server_name) || undefined;
      out.action = str(input.action) || undefined;
      out.content = cut(safeJson(input.content));
      break;

    case "TeammateIdle":
      out.type = "TEAMMATE_IDLE";
      out.teammate_id = str(input.teammate_id || input.agent_id) || undefined;
      break;

    case "UnparsableStdin":
      out.type = "UNPARSABLE_STDIN";
      out.raw_text = cut(str(input._unparsable));
      break;

    default:
      out.type = "EVENT";
      break;
  }

  return dropUndefined(out);
}

// ─── Transcript harvesting: Credits and token accounting ────────────────────
//
// Transcript records relevant to cost auditing:
//   { type: "assistant", message: { model, stop_reason, usage: {...}, content: [...] } }
//   { type: "user",      message: { content: [ { type: "tool_result", ... } ] }, origin, promptId }
//   { type: "runtime-config", model, reasoningEffort, contextWindow }
//
// On real Qoder transcripts `usage` carries: input_tokens, output_tokens,
// cache_read_input_tokens, cache_creation_input_tokens, cache_creation
// (ephemeral 5m / 1h), server_tool_use (web search / fetch counts),
// service_tier, speed, inference_geo, credits, original_credits, billable,
// request_id and context_usage_ratio.

function harvestTranscript(input, base, state, session) {
  const transcriptPath = input.transcript_path;
  let stat;
  try {
    stat = fs.statSync(transcriptPath);
  } catch (err) {
    return; // transcript not flushed yet
  }

  let entry = state[transcriptPath];
  if (!entry || entry.inode !== stat.ino || entry.size > stat.size) {
    // New file, or rewritten / truncated / rotated: re-read from the start.
    entry = { inode: stat.ino, offset: 0, size: 0, seenUsage: [], lastUserTs: 0 };
    state[transcriptPath] = entry;
  }
  entry.updatedAt = Date.now();
  if (stat.size <= entry.offset) return; // nothing appended since the last run

  const tail = readTail(transcriptPath, entry.offset, stat.size);
  // Consume complete lines only; a partial trailing line is read next run.
  const lastNewline = tail.lastIndexOf(0x0a);
  if (lastNewline < 0) return;

  const consumed = tail.slice(0, lastNewline + 1);
  entry.offset += consumed.length;
  entry.size = stat.size;

  const text = consumed.toString("utf8");
  const seenUsage = new Set(entry.seenUsage || []);
  // Derived records repeat `_raw` for every line, so drop the verbatim copy.
  const slimBase = Object.assign({}, base);
  delete slimBase._raw;
  // Derived records never see the hook payload, so attribute them from the
  // session-cached enterprise identity (extra.user), when one exists.
  if (session.user_info) slimBase.user_info = Object.assign({}, session.user_info);

  for (const line of text.split("\n")) {
    if (!line.trim()) continue;
    let record;
    try {
      record = JSON.parse(line);
    } catch (err) {
      continue;
    }
    if (!record || typeof record !== "object") continue;

    if (record.type === "runtime-config") {
      session.runtime_model = str(record.model) || session.runtime_model;
      if (record.contextWindow !== undefined) session.context_window = record.contextWindow;
      if (record.reasoningEffort !== undefined) session.reasoning_effort = record.reasoningEffort;
      continue;
    }

    if (record.type === "user") {
      const ts = parseIsoMs(record.timestamp);
      if (ts) entry.lastUserTs = ts;
      harvestToolResults(record, slimBase, session);
      if (CONFIG.transcriptPrompt) harvestPrompt(record, slimBase);
      continue;
    }

    if (record.type === "assistant") {
      harvestAssistant(record, slimBase, session, seenUsage, entry);
    }
  }

  entry.seenUsage = Array.from(seenUsage).slice(-2000);
}

/**
 * One record per model call: the Credits actually charged plus the token mix.
 * Deduplicated by transcript uuid so replays never double count.
 */
function harvestAssistant(record, base, session, seenUsage, entry) {
  const message = pickObject(record.message);
  if (!message) return;
  const usage = pickObject(message.usage);
  const uuid = str(record.uuid) || str(message.id);

  const content = Array.isArray(message.content) ? message.content : [];
  const toolUses = content.filter((c) => c && c.type === "tool_use");
  const textParts = content.filter((c) => c && c.type === "text").map((c) => str(c.text));
  const thinkingParts = content.filter((c) => c && c.type === "thinking").map((c) => str(c.thinking));

  if (usage && uuid && !seenUsage.has(uuid)) {
    seenUsage.add(uuid);
    const cacheCreation = pickObject(usage.cache_creation) || {};
    const serverToolUse = pickObject(usage.server_tool_use) || {};
    const assistantTs = parseIsoMs(record.timestamp);
    const latencyMs = entry.lastUserTs && assistantTs ? assistantTs - entry.lastUserTs : undefined;

    const credits = numOpt(usage.credits);
    session.model_calls = (session.model_calls || 0) + 1;
    session.credits = round((session.credits || 0) + numOr0(credits));
    session.input_tokens = (session.input_tokens || 0) + numOr0(usage.input_tokens);
    session.output_tokens = (session.output_tokens || 0) + numOr0(usage.output_tokens);
    if (typeof usage.context_usage_ratio === "number") session.context_usage_ratio = usage.context_usage_ratio;
    session.last_activity = new Date().toISOString();

    emit(dropUndefined(Object.assign({}, base, {
      record_kind: "usage",
      type: "LLM_USAGE",
      event: "TranscriptAssistant",
      transcript_uuid: uuid,
      request_id: str(usage.request_id) || undefined,
      llm_record_timestamp: record.timestamp,
      model: str(message.model) || base.model,
      stop_reason: str(message.stop_reason) || undefined,
      // Billing
      credits: credits,
      original_credits: numOpt(usage.original_credits),
      billable: typeof usage.billable === "boolean" ? usage.billable : undefined,
      service_tier: str(usage.service_tier) || undefined,
      speed: str(usage.speed) || undefined,
      inference_geo: str(usage.inference_geo) || undefined,
      // Token accounting
      input_tokens: numOpt(usage.input_tokens),
      output_tokens: numOpt(usage.output_tokens),
      cache_read_input_tokens: numOpt(usage.cache_read_input_tokens),
      cache_creation_input_tokens: numOpt(usage.cache_creation_input_tokens),
      cache_ephemeral_5m_input_tokens: numOpt(cacheCreation.ephemeral_5m_input_tokens),
      cache_ephemeral_1h_input_tokens: numOpt(cacheCreation.ephemeral_1h_input_tokens),
      web_search_requests: numOpt(serverToolUse.web_search_requests),
      web_fetch_requests: numOpt(serverToolUse.web_fetch_requests),
      context_usage_ratio: numOpt(usage.context_usage_ratio),
      iterations_count: Array.isArray(usage.iterations) ? usage.iterations.length : undefined,
      // Response shape, without duplicating bulky content
      response_text: cut(textParts.join("\n")) || undefined,
      response_text_chars: textParts.join("").length || undefined,
      thinking_chars: thinkingParts.join("").length || undefined,
      tool_calls: toolUses.length ? toolUses.map((t) => str(t.name)).filter(Boolean).join(",") : undefined,
      tool_call_count: toolUses.length,
      latency_ms: latencyMs,
      session_credits_total: session.credits,
    })));
  }

  // Assistant tool_use blocks are the request half of a tool call and appear
  // even when the entry point fires no PreToolUse hook, so capture arguments.
  for (const block of toolUses) {
    session.tool_calls_from_transcript = (session.tool_calls_from_transcript || 0) + 1;
    emit(dropUndefined(Object.assign({}, base, {
      record_kind: "transcript",
      type: "LLM_TOOL_CALL",
      event: "TranscriptToolUse",
      tool_call_id: str(block.id) || undefined,
      tool_name: str(block.name) || undefined,
      tool_input: cut(safeJson(block.input)),
    })));
  }
}

/**
 * Tool output as the model actually received it. PostToolUse may deliver only a
 * short status string, so transcript tool_result blocks are the reliable source
 * for "what came back".
 */
function harvestToolResults(record, base, session) {
  const message = pickObject(record.message);
  if (!message) return;
  const content = Array.isArray(message.content) ? message.content : [];
  for (const block of content) {
    if (!block || block.type !== "tool_result") continue;
    if (block.is_error) session.tool_failures = (session.tool_failures || 0) + 1;
    else session.tool_results = (session.tool_results || 0) + 1;
    session.last_activity = new Date().toISOString();
    emit(dropUndefined(Object.assign({}, base, {
      record_kind: "transcript",
      type: "TOOL_RESULT",
      event: "TranscriptToolResult",
      tool_call_id: str(block.tool_use_id) || undefined,
      is_error: typeof block.is_error === "boolean" ? block.is_error : undefined,
      tool_response: cut(typeof block.content === "string" ? block.content : safeJson(block.content)),
    })));
  }
}

function harvestPrompt(record, base) {
  const message = pickObject(record.message);
  if (!message) return;
  const origin = pickObject(record.origin) || {};
  if (str(origin.kind) !== "human") return;
  const content = Array.isArray(message.content) ? message.content : [];
  const text = content.filter((c) => c && c.type === "text").map((c) => str(c.text)).join("\n")
    || str(pickObject(record.humanInput) && record.humanInput.text);
  if (!text) return;
  emit(dropUndefined(Object.assign({}, base, {
    record_kind: "transcript",
    type: "USER_REQUEST_TRANSCRIPT",
    event: "TranscriptPrompt",
    prompt: cut(text),
    prompt_id: str(record.promptId) || undefined,
    prompt_origin: str(origin.kind) || undefined,
  })));
}

// ─── Storage: local JSONL, state, summary, rotation ─────────────────────────

const pending = [];

function emit(record) {
  // Unattributable records are never stored or uploaded: without an enterprise
  // identity the saved copy could not be queried back to a person, so drop it.
  if (!record || !record.user_info) return;
  // Work on the redacted copy (or a shallow clone when redaction is off) so
  // the caller's record/base object keeps its original shape for the
  // transcript harvesting that runs right after.
  const final = CONFIG.redact ? applyRedaction(record) : Object.assign({}, record);
  // Saved-record schema: the enterprise identity lives at the top level; the
  // legacy machine-level identity fields no longer ship.
  for (const key of USER_INFO_KEYS) {
    if (key in final.user_info) final[key] = final.user_info[key];
  }
  delete final.user_info;
  delete final.client_id;
  delete final.hostname;
  delete final.os_user;
  delete final.user;
  writeLocal(final);
  // Only legacy mode buffers records for the per-record push. Cursor mode
  // uploads straight from the local file; "off" (or an empty server URL)
  // never touches the network.
  if (CONFIG.serverUrl && CONFIG.uploadMode === "legacy") {
    pending.push(final);
    if (pending.length >= 50) flushPending();
  }
}

function writeLocal(record) {
  try {
    ensureDir(P.root());
    const day = new Date().toISOString().slice(0, 10);
    let file = path.join(P.root(), "requests_" + day + ".jsonl");
    file = rotateIfNeeded(file);
    fs.appendFileSync(file, JSON.stringify(record) + "\n", "utf8");
  } catch (err) {
    reportError(err, { stage: "writeLocal" });
  }
}

function rotateIfNeeded(file) {
  if (!CONFIG.maxFileBytes) return file;
  try {
    if (fs.statSync(file).size < CONFIG.maxFileBytes) return file;
  } catch (err) {
    return file; // file does not exist yet
  }
  let index = 1;
  let candidate = file + "." + index;
  while (fs.existsSync(candidate)) {
    index += 1;
    candidate = file + "." + index;
  }
  return candidate;
}

/**
 * Hooks fire concurrently (async hooks, subagents, parallel tool calls) and the
 * transcript offset is a read-modify-write value, so state access needs a mutex
 * or Credits get double counted.
 */
function readStateLocked() {
  const lock = acquireLock();
  let state = {};
  try {
    const parsed = JSON.parse(fs.readFileSync(P.stateFile(), "utf8"));
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) state = parsed;
  } catch (err) { /* first run or corrupt state: start empty */ }
  state.__lock = lock;
  return state;
}

function saveStateLocked(state) {
  const lock = state.__lock;
  delete state.__lock;
  try {
    ensureDir(P.state());
    pruneState(state);
    fs.writeFileSync(P.stateFile(), JSON.stringify(state), "utf8");
  } catch (err) {
    reportError(err, { stage: "saveState" });
  } finally {
    releaseLock(lock);
  }
}

/** Keep the state file bounded to recently active transcripts and sessions. */
function pruneState(state) {
  const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000;
  for (const key of Object.keys(state)) {
    if (key.charAt(0) !== "/" && !/^[A-Za-z]:[\\/]/.test(key)) continue; // skip meta keys
    const entry = state[key];
    if (!entry || typeof entry !== "object") continue;
    if (typeof entry.updatedAt === "number" && entry.updatedAt < cutoff) delete state[key];
  }
  const sessions = state.__sessions;
  if (sessions && typeof sessions === "object") {
    const ids = Object.keys(sessions);
    if (ids.length > 300) {
      ids.sort((a, b) => numOr0(sessions[a].started_at_ms) - numOr0(sessions[b].started_at_ms));
      for (const id of ids.slice(0, ids.length - 300)) delete sessions[id];
    }
  }
}

function ensureSession(state, record) {
  if (!state.__sessions) state.__sessions = {};
  const id = record.session_id || "unknown";
  if (!state.__sessions[id]) {
    state.__sessions[id] = {
      session_id: id,
      client_id: CLIENT_ID,
      hostname: HOSTNAME,
      started_at: new Date().toISOString(),
      started_at_ms: Date.now(),
      prompts: 0,
      tool_requests: 0,
      tool_responses: 0,
      tool_results: 0,
      tool_failures: 0,
      model_calls: 0,
      credits: 0,
      input_tokens: 0,
      output_tokens: 0,
      last_activity: new Date().toISOString(),
    };
  }
  const session = state.__sessions[id];
  if (record.cwd) session.cwd = record.cwd;
  if (record.repo) session.repo = record.repo;
  if (record.git_branch) session.git_branch = record.git_branch;
  if (record.model && !session.model) session.model = record.model;
  if (record.type === "USER_REQUEST") session.prompts = (session.prompts || 0) + 1;
  if (record.type === "TOOL_REQUEST") session.tool_requests = (session.tool_requests || 0) + 1;
  if (record.type === "TOOL_RESPONSE") session.tool_responses = (session.tool_responses || 0) + 1;
  if (record.type === "TOOL_RESPONSE_FAILURE") session.tool_failures = (session.tool_failures || 0) + 1;
  return session;
}

/**
 * Idempotency guard against the host's double dispatch: identical hook events
 * arrive twice, usually in the same millisecond but occasionally several
 * milliseconds apart. The identity key therefore uses only the unique payload
 * ids (request set / tool call); a genuine repeat of the same logical event
 * always shares them, while distinct events carry different ids. A 10-second
 * window absorbs the host's timing jitter without ever suppressing a real
 * second event, and each key is single-shot: once it catches a duplicate it
 * is deleted, so a later legitimate event with the same key shape (e.g. a
 * new SessionStart after a session resume) is recorded normally. Runs inside
 * the shared state lock, so the racing dispatchers are serialised and only
 * the first one records the key. Returns true when the record was already
 * written by a sibling dispatch and must be skipped.
 */
function markSeen(state, record) {
  const key = [
    record.event || "",
    record.session_id || "",
    record.request_set_id || "",
    record.tool_call_id || "",
  ].join("|");
  if (!state.__seen) state.__seen = {};
  const seen = state.__seen;
  const now = Date.now();
  const prev = seen[key];
  const eventTs = typeof record.timestamp_ms === "number" ? record.timestamp_ms : now;
  const prevTs = prev && typeof prev.ts === "number" ? prev.ts : (typeof prev === "number" ? prev : null);
  if (prevTs !== null && Math.abs(eventTs - prevTs) < 10000) {
    delete seen[key]; // single-shot: the duplicate is absorbed, arm for the next real event
    return true;
  }
  seen[key] = { ts: eventTs, at: now };
  // Keep the ring bounded: drop stale entries, then trim to the newest 2000.
  const keys = Object.keys(seen);
  for (const k of keys) {
    const entry = seen[k];
    if (typeof entry === "number" || now - (entry.at || 0) > 10 * 60 * 1000) delete seen[k];
  }
  const remaining = Object.keys(seen);
  if (remaining.length > 2000) {
    remaining.sort((a, b) => (seen[a].at || 0) - (seen[b].at || 0));
    for (const k of remaining.slice(0, remaining.length - 2000)) delete seen[k];
  }
  return false;
}

function writeSummary(state) {
  try {
    ensureDir(P.state());
    const sessions = state.__sessions || {};
    const today = new Date().toISOString().slice(0, 10);
    let todayCredits = 0;
    let todayCalls = 0;
    const list = [];
    for (const id of Object.keys(sessions)) {
      const session = sessions[id];
      list.push({
        session_id: id,
        user: session.user,
        cwd: session.cwd,
        repo: session.repo,
        git_branch: session.git_branch,
        model: session.model,
        runtime_model: session.runtime_model,
        context_window: session.context_window,
        context_usage_ratio: session.context_usage_ratio,
        started_at: session.started_at,
        last_activity: session.last_activity,
        prompts: session.prompts || 0,
        tool_requests: session.tool_requests || 0,
        tool_responses: session.tool_responses || 0,
        tool_results: session.tool_results || 0,
        tool_failures: session.tool_failures || 0,
        model_calls: session.model_calls || 0,
        credits: round(session.credits || 0),
        input_tokens: session.input_tokens || 0,
        output_tokens: session.output_tokens || 0,
      });
      if (String(session.last_activity || "").slice(0, 10) === today) {
        todayCredits += session.credits || 0;
        todayCalls += session.model_calls || 0;
      }
    }
    list.sort((a, b) => String(b.last_activity).localeCompare(String(a.last_activity)));
    fs.writeFileSync(P.summary(), JSON.stringify({
      generated_at: new Date().toISOString(),
      client_id: CLIENT_ID,
      user: CLIENT_ID,
      today: { date: today, credits: round(todayCredits), model_calls: todayCalls },
      session_count: list.length,
      sessions: list.slice(0, 500),
    }, null, 2), "utf8");
  } catch (err) {
    reportError(err, { stage: "writeSummary" });
  }
}

// ─── Locking ────────────────────────────────────────────────────────────────

function acquireLock() {
  const dir = P.lock();
  ensureDir(P.state());
  const deadline = Date.now() + 1500;
  for (;;) {
    try {
      fs.mkdirSync(dir);
      return dir;
    } catch (err) {
      if (err.code !== "EEXIST") return null;
      let reclaimed = false;
      try {
        if (Date.now() - fs.statSync(dir).mtimeMs > 20000) {
          fs.rmdirSync(dir);
          reclaimed = true;
        }
      } catch (statErr) { /* raced with another holder */ }
      if (reclaimed) continue;
      if (Date.now() > deadline) return null; // proceed unlocked rather than stall
      sleepSync(25);
    }
  }
}

function releaseLock(dir) {
  if (!dir) return;
  try { fs.rmdirSync(dir); } catch (err) { /* already removed */ }
}

function sleepSync(ms) {
  try {
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
  } catch (err) {
    const end = Date.now() + ms;
    while (Date.now() < end) { /* spin fallback */ }
  }
}

// ─── HTTP channel ───────────────────────────────────────────────────────────

let activeRequests = 0;

function flushPending() {
  if (!CONFIG.serverUrl) return;
  if (CONFIG.uploadMode !== "legacy") return; // cursor/off never touch the legacy channel
  // 401/403 circuit breaker, shared with cursor mode through the same
  // upload-state.json (breakerUntilMs). While tripped, the whole legacy
  // channel stands down silently. Read-only on the happy path: a 2xx never
  // writes state, and without SERVER_URL this function returns before the
  // state file is even read.
  if (Date.now() < readUploadState().breakerUntilMs) return;
  drainOutbox();
  while (pending.length) pushRecord(pending.shift());
}

function pushRecord(record) {
  let url;
  try {
    url = resolveEndpoint(CONFIG.serverUrl);
  } catch (err) {
    queueOutbox(record);
    return;
  }
  let body;
  try {
    body = JSON.stringify(record);
  } catch (err) {
    return; // unserializable record
  }
  const transport = url.protocol === "https:" ? https : http;
  const request = transport.request({
    hostname: url.hostname,
    port: url.port || (url.protocol === "https:" ? 443 : 80),
    path: url.pathname + (url.search || ""),
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Content-Length": Buffer.byteLength(body),
    },
    timeout: CONFIG.httpTimeoutMs,
  }, (response) => {
    activeRequests -= 1;
    const status = response.statusCode || 0;
    response.resume();
    if (status === 401 || status === 403) {
      // The key itself is rejected, so retrying can never succeed; queueing
      // would cycle the same records through the outbox on every hook run
      // until it overflows and drops data. Trip the shared 24h breaker and
      // drop the record instead - the daily file keeps it, and once the key
      // works again cursor mode re-uploads it from disk (server dedupes).
      tripLegacyBreaker(status);
      return;
    }
    if (status >= 400) queueOutbox(record);
  });
  activeRequests += 1;
  request.on("error", () => { activeRequests -= 1; queueOutbox(record); });
  request.on("timeout", () => { request.destroy(); queueOutbox(record); });
  if (CONFIG.apiKey) request.setHeader("X-API-Key", CONFIG.apiKey);
  request.write(body);
  request.end();
}

/**
 * A rejected key (401/403) trips the same 24h circuit breaker cursor mode
 * uses - both channels share upload-state.json. The first trip persists
 * breakerUntilMs and writes one logger-error.log line; trips while the
 * breaker is already open stay silent, so a revoked key churning hundreds
 * of records cannot flood the error log either.
 */
function tripLegacyBreaker(status) {
  let already = false;
  try {
    const state = readUploadState();
    already = Date.now() < state.breakerUntilMs;
    if (!already) {
      state.breakerUntilMs = Date.now() + 24 * 60 * 60 * 1000;
      writeUploadState(state);
    }
  } catch (err) { /* silent by design: never break the hook */ }
  if (already) return;
  reportError(new Error("push refused: HTTP " + status + " (api key rejected); legacy upload paused for 24h"), { stage: "legacyPush" });
}

/**
 * Failed pushes must not vanish while the collector is down: queue them and
 * retry opportunistically on the next hook invocation.
 */
function queueOutbox(record) {
  try {
    ensureDir(P.state());
    try {
      if (fs.statSync(P.outbox()).size > CONFIG.outboxMaxBytes) return; // bounded
    } catch (err) { /* not created yet */ }
    fs.appendFileSync(P.outbox(), JSON.stringify(record) + "\n", "utf8");
  } catch (err) { /* drop rather than fail the hook */ }
}

function drainOutbox() {
  let lines;
  try {
    lines = fs.readFileSync(P.outbox(), "utf8").split("\n").filter((line) => line.trim());
  } catch (err) {
    return; // no outbox
  }
  if (!lines.length) return;
  const batch = lines.slice(0, CONFIG.drainBatch);
  const remainder = lines.slice(batch.length);
  try {
    if (remainder.length) fs.writeFileSync(P.outbox(), remainder.join("\n") + "\n", "utf8");
    else fs.unlinkSync(P.outbox());
  } catch (err) {
    return;
  }
  for (const line of batch) {
    try {
      pushRecord(JSON.parse(line)); // failures re-queue via pushRecord
    } catch (err) { /* skip malformed queue entry */ }
  }
}

function resolveEndpoint(serverUrl) {
  const url = new URL(serverUrl);
  if (!url.pathname || url.pathname === "/") url.pathname = "/api/logs";
  return url;
}

// ─── Cursor upload: offset-tracked batch shipping ─────────────────────────
//
// Instead of pushing each record as it is produced, cursor mode treats the
// local daily JSONL as the source of truth and ships gzip batches of complete
// lines to {origin}/api/logs/batch. A per-file byte offset (persisted in
// .request-logger/upload-state.json) is advanced only after a 2xx, so any
// failure simply replays the same bytes next round; the server dedupes.
// The two channels are mutually exclusive: in cursor mode emit() never
// buffers and the outbox is never touched, so no record can travel twice.

let cursorDeadlineAt = 0;
let cursorBatches = 0;

function readUploadState() {
  let parsed = null;
  try {
    parsed = JSON.parse(fs.readFileSync(P.uploadState(), "utf8"));
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) parsed = null;
  } catch (err) { /* missing or corrupt: reset silently */ }
  const files = parsed && parsed.files && typeof parsed.files === "object" && !Array.isArray(parsed.files) ? parsed.files : {};
  return {
    files: files,
    lastAttemptMs: numOr0(parsed && parsed.lastAttemptMs),
    nextAttemptAtMs: numOr0(parsed && parsed.nextAttemptAtMs),
    breakerUntilMs: numOr0(parsed && parsed.breakerUntilMs),
  };
}

/** tmp + rename, so a concurrent reader never sees a half-written state. */
function writeUploadState(state) {
  try {
    ensureDir(P.state());
    const tmp = P.uploadState() + ".tmp." + process.pid;
    fs.writeFileSync(tmp, JSON.stringify(state), "utf8");
    fs.renameSync(tmp, P.uploadState());
  } catch (err) {
    reportError(err, { stage: "cursorUploadState" });
  }
}

function cursorOffset(state, fileName) {
  const entry = state.files[fileName];
  return entry && typeof entry.offset === "number" && Number.isFinite(entry.offset) ? entry.offset : 0;
}

function cursorSetOffset(state, fileName, offset) {
  if (!state.files[fileName] || typeof state.files[fileName] !== "object") state.files[fileName] = { offset: 0 };
  state.files[fileName].offset = offset;
}

/**
 * Entry point, called from finish() once the local write is complete.
 * Throttled by the interval, by an explicit backoff timestamp and by a 24h
 * circuit breaker; a skipped tick costs nothing.
 */
function cursorUploadTick() {
  if (!CONFIG.serverUrl || CONFIG.uploadMode !== "cursor") return;
  const now = Date.now();
  const state = readUploadState();
  if (now < state.nextAttemptAtMs || now < state.breakerUntilMs) return;
  if (state.lastAttemptMs && now - state.lastAttemptMs < CONFIG.uploadIntervalSec * 1000) return;

  // Record the attempt up front: concurrent hook processes then observe the
  // throttle and stand down. A race merely duplicates an idempotent batch.
  state.lastAttemptMs = now;
  writeUploadState(state);

  pruneOldFiles(state);

  cursorDeadlineAt = now + CONFIG.cursorBudgetMs;
  cursorBatches = 0;
  cursorStep(recentDayFiles(), 0, state, CONFIG.batchMaxLines, false);
}

/**
 * Today and the two preceding days (UTC, matching writeLocal's file names),
 * including each day's rotated variants. rotateIfNeeded() keeps the bytes
 * already written where they are and appends NEW records to the first free
 * requests_<day>.jsonl.N, so within one day the bare file is the OLDEST
 * segment and .N grows with N (.1 is older than .2). The list therefore
 * orders each day bare-file-first, then .1 .. .9, so old segments ship
 * before the current one. Every entry is a plain file name, so the per-file
 * cursors in upload-state.json (keyed by name) track variants naturally, and
 * pruneOldFiles - whose regex already accepts .N - can finally delete a
 * variant once its offset covers its size. Beyond .9 (640MB+ in one day)
 * variants are left alone; that volume was never observed.
 */
function recentDayFiles() {
  const names = [];
  const now = Date.now();
  for (let back = 0; back < 3; back += 1) {
    const base = "requests_" + new Date(now - back * 86400000).toISOString().slice(0, 10) + ".jsonl";
    names.push(base);
    const prefix = base + ".";
    let variants = [];
    try {
      for (const entry of fs.readdirSync(CONFIG.logDir)) {
        if (entry.length === prefix.length + 1 && entry.startsWith(prefix)
            && /^[1-9]$/.test(entry.slice(prefix.length))) variants.push(entry);
      }
      variants.sort();
    } catch (err) { /* log dir missing: bare file names only */ }
    names.push.apply(names, variants);
  }
  return names;
}

/**
 * Advance from files[fileIdx] onwards. Synchronous up to the point where a
 * batch is handed to the socket; the response callback re-enters here.
 */
function cursorStep(files, fileIdx, state, maxLines, halved) {
  if (Date.now() > cursorDeadlineAt || cursorBatches >= CONFIG.cursorMaxBatches) return; // budget spent
  if (fileIdx >= files.length) return; // nothing left to ship
  const fileName = files[fileIdx];
  const filePath = path.join(CONFIG.logDir, fileName);
  const offset = cursorOffset(state, fileName);
  const batch = buildBatch(filePath, offset, maxLines);
  if (!batch) {
    cursorStep(files, fileIdx + 1, state, CONFIG.batchMaxLines, false);
    return;
  }
  postBatch(batch.body, (result) => {
    cursorOnResponse(result, files, fileIdx, state, fileName, batch, maxLines, halved);
  });
}

function cursorOnResponse(result, files, fileIdx, state, fileName, batch, maxLines, halved) {
  const status = result.status;
  const now = Date.now();
  if (status >= 200 && status < 300) {
    // Any 2xx means the whole batch landed: advance the cursor and continue
    // within the remaining budget (same file first, it may have more lines).
    cursorSetOffset(state, fileName, batch.nextOffset);
    writeUploadState(state);
    cursorBatches += 1;
    cursorStep(files, fileIdx, state, maxLines, halved);
    return;
  }
  if (status === 401 || status === 403) {
    state.breakerUntilMs = now + 24 * 60 * 60 * 1000;
    writeUploadState(state);
    reportError(new Error("batch upload refused: HTTP " + status + " (api key rejected); cursor paused for 24h"), { stage: "cursorUpload" });
    return;
  }
  if (status === 429 || status === 503) {
    const waitSec = result.retryAfterSec > 0 ? result.retryAfterSec : 60;
    state.nextAttemptAtMs = now + waitSec * 1000;
    writeUploadState(state);
    return;
  }
  if (status === 413) {
    if (!halved && batch.lineCount > 25) {
      cursorStep(files, fileIdx, state, Math.max(25, Math.floor(batch.lineCount / 2)), true);
      return;
    }
    reportError(new Error("batch upload rejected: HTTP 413 even after halving (" + batch.lineCount + " lines)"), { stage: "cursorUpload" });
    return;
  }
  // Network error, timeout, 5xx or anything else: stop. The offset was not
  // advanced, so the same bytes are replayed on the next tick.
}

/**
 * Read complete NDJSON lines from `offset` of `file`, capped at maxLines lines
 * and CONFIG.batchMaxBytes bytes. A trailing line without "\n" is still being
 * written and is left for the next run.
 */
function buildBatch(file, offset, maxLines) {
  let stat;
  try {
    stat = fs.statSync(file);
  } catch (err) {
    return null;
  }
  if (stat.size <= offset) return null; // fully shipped already
  const want = Math.min(stat.size - offset, CONFIG.batchMaxBytes);
  const fd = fs.openSync(file, "r");
  let chunk;
  try {
    const buffer = Buffer.alloc(want);
    const bytesRead = fs.readSync(fd, buffer, 0, want, offset);
    chunk = buffer.slice(0, bytesRead);
  } finally {
    fs.closeSync(fd);
  }
  const lastNewline = chunk.lastIndexOf(0x0a);
  if (lastNewline < 0) return null; // no complete line yet
  let cut = -1;
  let lines = 0;
  for (let i = 0; i <= lastNewline && lines < maxLines; i += 1) {
    if (chunk[i] === 0x0a) {
      cut = i;
      lines += 1;
    }
  }
  if (cut < 0) return null;
  return { body: chunk.slice(0, cut + 1), nextOffset: offset + cut + 1, lineCount: lines };
}

/**
 * POST one gzip batch to {origin}/api/logs/batch. `done` receives
 * { status, retryAfterSec }; status 0 means transport failure or timeout.
 */
function postBatch(body, done) {
  let url;
  let gz;
  try {
    url = new URL(new URL(CONFIG.serverUrl).origin + "/api/logs/batch");
    gz = zlib.gzipSync(body);
  } catch (err) {
    done({ status: 0, retryAfterSec: 0 });
    return;
  }
  const transport = url.protocol === "https:" ? https : http;
  const request = transport.request({
    hostname: url.hostname,
    port: url.port || (url.protocol === "https:" ? 443 : 80),
    path: url.pathname,
    method: "POST",
    headers: {
      "Content-Type": "application/x-ndjson",
      "Content-Encoding": "gzip",
      "Content-Length": gz.length,
    },
    timeout: CONFIG.httpTimeoutMs,
  }, (response) => {
    activeRequests -= 1;
    const status = response.statusCode || 0;
    const retryAfter = Number(response.headers["retry-after"]);
    response.resume();
    done({
      status: status,
      retryAfterSec: Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : 0,
    });
  });
  activeRequests += 1;
  request.on("error", () => {
    activeRequests -= 1;
    done({ status: 0, retryAfterSec: 0 });
  });
  request.on("timeout", () => { request.destroy(); }); // destroy emits "error"
  if (CONFIG.apiKey) request.setHeader("X-API-Key", CONFIG.apiKey);
  request.end(gz);
}

/**
 * Delete fully-uploaded daily files (including rotated .1/.2 variants) older
 * than QODER_LOG_LOCAL_RETENTION_DAYS. A file is only removed when its cursor
 * offset covers its current size, so data awaiting upload is never lost.
 */
function pruneOldFiles(state) {
  if (!CONFIG.localRetentionDays) return;
  try {
    const cutoffDay = new Date(Date.now() - CONFIG.localRetentionDays * 86400000).toISOString().slice(0, 10);
    for (const entry of fs.readdirSync(CONFIG.logDir)) {
      const match = /^requests_(\d{4}-\d{2}-\d{2})\.jsonl(?:\.\d+)?$/.exec(entry);
      if (!match || match[1] >= cutoffDay) continue;
      const file = path.join(CONFIG.logDir, entry);
      try {
        if (cursorOffset(state, entry) < fs.statSync(file).size) continue; // not fully uploaded yet
        fs.unlinkSync(file);
      } catch (err) { /* keep the file, retry next time */ }
    }
  } catch (err) { /* best effort, always silent */ }
}

// ─── Redaction ──────────────────────────────────────────────────────────────
// Detailed logs and secrets share the same fields (tool inputs, HTTP headers,
// env dumps), so credential-looking values are masked before storage / push.

const SECRET_PATTERNS = [
  { re: /-----BEGIN (?:RSA |EC |OPENSSH |PGP |DSA )?PRIVATE KEY-----[\s\S]*?-----END (?:RSA |EC |OPENSSH |PGP |DSA )?PRIVATE KEY-----/g, to: "[redacted private key]" },
  { re: /\b(?:AKIA|ASIA)[0-9A-Z]{16}\b/g, to: "[redacted aws key]" },
  { re: /\bgh[pousr]_[A-Za-z0-9]{20,}\b/g, to: "[redacted github token]" },
  { re: /\bxox[baprs]-[A-Za-z0-9-]{10,}\b/g, to: "[redacted slack token]" },
  { re: /\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/g, to: "[redacted jwt]" },
  { re: /\blark_[a-z0-9]{16,}\b/gi, to: "[redacted lark token]" },
  { re: /((?:password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?ticket|authorization)["']?\s*[:=]\s*["']?)([^\s"',;\\]{6,})/gi, to: "$1[redacted]" },
];

// Every field is redacted value by value, user_info included: real identity
// values (emails, display names, org ids, UUIDs) match no secret pattern, so
// per-value masking leaves attribution intact, while a crafted value that
// does look like a credential (e.g. a JWT-shaped uid) is still masked.
function applyRedaction(target) {
  if (Array.isArray(target)) return target.map(applyRedaction);
  if (target && typeof target === "object") {
    const out = {};
    for (const key of Object.keys(target)) out[key] = applyRedaction(target[key]);
    return out;
  }
  return typeof target === "string" ? redactString(target) : target;
}

function redactString(value) {
  let result = value;
  for (const pattern of SECRET_PATTERNS) result = result.replace(pattern.re, pattern.to);
  return result;
}

// ─── Helpers ────────────────────────────────────────────────────────────────

function readTail(file, offset, size) {
  const length = Math.min(size - offset, CONFIG.maxTailBytes);
  const buffer = Buffer.alloc(length);
  const fd = fs.openSync(file, "r");
  let bytesRead = 0;
  try {
    bytesRead = fs.readSync(fd, buffer, 0, length, offset);
  } finally {
    fs.closeSync(fd);
  }
  return buffer.slice(0, bytesRead);
}

function str(value) {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return "";
}

function firstDefined() {
  for (let i = 0; i < arguments.length; i += 1) {
    if (arguments[i] !== undefined && arguments[i] !== null) return arguments[i];
  }
  return undefined;
}

function pickObject(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : null;
}

function numOpt(value) {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim() && Number.isFinite(Number(value))) return Number(value);
  return undefined;
}

function numOr0(value) {
  const num = numOpt(value);
  return num === undefined ? 0 : num;
}

// `min` is the inclusive lower bound for accepted values. It defaults to 1,
// which preserves the historical `> 0` acceptance rule; the upload interval
// passes 0 so QODER_LOG_UPLOAD_INTERVAL_SEC=0 means "no throttle at all".
function intOpt(value, fallback, min) {
  const num = Number(value);
  const smallest = min === undefined ? 1 : min;
  return Number.isFinite(num) && num >= smallest ? Math.floor(num) : fallback;
}

/** Upload mode whitelist: anything unknown falls back to legacy (current behaviour). */
function normalizeUploadMode(value) {
  const mode = String(value || "").trim().toLowerCase();
  return mode === "cursor" || mode === "off" ? mode : "legacy";
}

function round(value) {
  return Math.round(value * 1e6) / 1e6;
}

function cut(value) {
  if (typeof value !== "string") return value;
  if (value.length <= CONFIG.truncate) return value;
  return value.slice(0, CONFIG.truncate) + "...[truncated " + (value.length - CONFIG.truncate) + " chars]";
}

function safeJson(value) {
  if (value === undefined || value === null) return "";
  if (typeof value === "string") return value;
  try {
    return JSON.stringify(value);
  } catch (err) {
    return String(value);
  }
}

function dropUndefined(record) {
  for (const key of Object.keys(record)) if (record[key] === undefined) delete record[key];
  return record;
}

function ensureDir(dir) {
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
}

function safe(fn, fallback) {
  try {
    return fn();
  } catch (err) {
    return fallback;
  }
}

function localTime(date) {
  const pad = (n) => String(n).padStart(2, "0");
  const offsetMin = -date.getTimezoneOffset();
  const sign = offsetMin >= 0 ? "+" : "-";
  const abs = Math.abs(offsetMin);
  return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate())
    + "T" + pad(date.getHours()) + ":" + pad(date.getMinutes()) + ":" + pad(date.getSeconds())
    + sign + pad(Math.floor(abs / 60)) + ":" + pad(abs % 60);
}

function parseIsoMs(value) {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value !== "string") return 0;
  const ms = Date.parse(value);
  return Number.isNaN(ms) ? 0 : ms;
}

function reportError(err, context) {
  const line = JSON.stringify({
    timestamp: new Date().toISOString(),
    stage: (context && context.stage) || "general",
    event: (context && context.event) || undefined,
    message: err && err.message ? err.message : String(err),
    stack: err && err.stack ? String(err.stack).split("\n").slice(0, 4).join(" | ") : undefined,
  }) + "\n";
  if (SELF_TEST) process.stderr.write("[self-test error] " + line);
  try {
    ensureDir(P.state());
    fs.appendFileSync(P.error(), line, "utf8");
  } catch (writeErr) { /* never break the agent */ }
}

// ─── Self test ──────────────────────────────────────────────────────────────
// Runs synthetic payloads that mirror real captured traffic against a
// throwaway log directory and asserts the key audit fields survive end to end.

function runSelfTest() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "qoder-log-selftest-"));
  CONFIG.logDir = tmp;
  CONFIG.serverUrl = "";
  const transcript = path.join(tmp, "session.jsonl");

  fs.writeFileSync(transcript, [
    { type: "runtime-config", sessionId: "s1", model: "ultimate", contextWindow: 200000, timestamp: 1 },
    { type: "user", uuid: "u1", timestamp: "2026-08-31T12:00:00.000Z", origin: { kind: "human" },
      message: { role: "user", content: [{ type: "text", text: "查一下今天的收入" }] }, promptId: "p1" },
    { type: "assistant", uuid: "a1", timestamp: "2026-08-31T12:00:09.000Z",
      message: { id: "c1", role: "assistant", model: "auto", stop_reason: "tool_use",
        content: [{ type: "tool_use", id: "call_1", name: "Bash", input: { command: "run-sql --password=hunter2secret" } }],
        usage: { input_tokens: 1200, output_tokens: 90, cache_read_input_tokens: 800,
          cache_creation_input_tokens: 10, credits: 0.4, original_credits: 0.4, billable: true,
          request_id: "req1", context_usage_ratio: 0.25 } } },
    { type: "user", uuid: "u2", timestamp: "2026-08-31T12:00:11.000Z",
      message: { role: "user", content: [{ type: "tool_result", tool_use_id: "call_1",
        content: "revenue=12345", is_error: false }] } },
    { type: "assistant", uuid: "a2", timestamp: "2026-08-31T12:00:15.000Z",
      message: { id: "c2", role: "assistant", model: "auto", stop_reason: "end_turn",
        content: [{ type: "text", text: "今天收入 12345。" }],
        usage: { input_tokens: 1400, output_tokens: 40, credits: 0.11, billable: true, request_id: "req2" } } },
    "trailing-partial-without-newline",
  ].map((o) => (typeof o === "string" ? o : JSON.stringify(o))).join("\n") + "\n", "utf8");

  const events = [
    // Partial-key identity first: exercises merge-by-key caching. The
    // notification event seeds only email + uid, the SessionStart below
    // merges the full 5-key identity on top, and the transcript-derived
    // records (harvested after SessionStart) must then carry the merged key
    // set - proving partial keys neither stick nor drop cached keys.
    { hook_event_name: "Notification", session_id: "s1", cwd: tmp, notification_type: "info", title: "partial identity", message: "partial key merge check",
      extra: { user: { email: "partial@sigmob.com", uid: "u-partial" } } },
    { hook_event_name: "SessionStart", session_id: "s1", transcript_path: transcript, cwd: tmp, model: "Ultimate", source: "startup",
      extra: { user: { email: "dev@sigmob.com", name: "开发者", org_id: "019cbcf2-test", org_name: "sigmob", uid: "019efd72-test" } } },
    { hook_event_name: "UserPromptSubmit", session_id: "s1", transcript_path: transcript, cwd: tmp, permission_mode: "acceptEdits", prompt: "查一下今天的收入" },
    { hook_event_name: "PreToolUse", session_id: "s1", transcript_path: transcript, cwd: tmp, tool_name: "Bash", tool_input: { command: "run-sql --password=hunter2secret" }, tool_use_id: "call_1" },
    { hook_event_name: "PostToolUse", session_id: "s1", transcript_path: transcript, cwd: tmp, tool_name: "Bash", tool_input: { command: "run-sql" }, tool_response: { success: true, rows: 1 }, tool_use_id: "call_1", extra: { email: "dev@sigmob.com", repo: "sigmob/data-platform", branch: "main", request_time: "2026-08-31T12:00:09Z", response_time: "2026-08-31T12:00:11Z", user: { email: "dev@sigmob.com", name: "开发者", org_id: "019cbcf2-test", org_name: "sigmob", uid: "019efd72-test" } } },
    { hook_event_name: "PostToolUseFailure", session_id: "s1", transcript_path: transcript, cwd: tmp, tool_name: "Bash", tool_input: { command: "npm test" }, error: "exit 1", error_type: "non_zero_exit", is_interrupt: false },
    { hook_event_name: "Stop", session_id: "s1", transcript_path: transcript, cwd: tmp, model: "Auto", stop_hook_active: false, last_assistant_message: "今天收入 12345。", parent_business_info: { product: "app", version: "1.1.35", type: "agent", stage: "processing" } },
    { hook_event_name: "SessionEnd", session_id: "s1", transcript_path: transcript, cwd: tmp, reason: "exit" },
  ];

  for (const event of events) handleEvent(JSON.stringify(event));
  // Replay only the round-ending events, the ones that drive transcript
  // harvesting: stored offsets must prevent duplicate usage/tool records.
  // Hook events themselves are recorded per invocation, so replaying
  // PostToolUseFailure here would inflate the failure counters being asserted.
  // Index 6 skips the partial-key notification added at the head.
  for (const event of events.slice(6)) handleEvent(JSON.stringify(event));

  // Secret-shaped identity probe on its own session, emitted once after the
  // replays so neither the events array nor the replay window changes: the
  // uid must land masked in BOTH the emitted record and the state.json
  // session cache (the cache is persisted verbatim by saveStateLocked, so
  // masking has to happen at the extractUserInfo source).
  handleEvent(JSON.stringify({ hook_event_name: "Notification", session_id: "s2", cwd: tmp, notification_type: "info", title: "jwt probe", message: "secret shaped uid probe",
    extra: { user: { uid: "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk" } } }));

  // Identity-less probe: no extra.user and no cached identity for its session,
  // so the attribution gate must drop it instead of saving an unattributable
  // record.
  handleEvent(JSON.stringify({ hook_event_name: "Notification", session_id: "s3", cwd: tmp, notification_type: "info", title: "no identity", message: "must be dropped" }));
  // Second event on the dropped session: the session cache must NOT backfill
  // an identity created from nothing, so this one is dropped as well.
  handleEvent(JSON.stringify({ hook_event_name: "UserPromptSubmit", session_id: "s3", cwd: tmp, prompt: "also unattributable" }));

  const day = new Date().toISOString().slice(0, 10);
  const written = fs.readFileSync(path.join(tmp, "requests_" + day + ".jsonl"), "utf8")
    .split("\n").filter((line) => line.trim()).map((line) => JSON.parse(line));

  const byType = {};
  for (const record of written) byType[record.type] = (byType[record.type] || 0) + 1;

  const usage = written.filter((r) => r.type === "LLM_USAGE");
  const toolResults = written.filter((r) => r.type === "TOOL_RESULT");
  const toolCalls = written.filter((r) => r.type === "LLM_TOOL_CALL");
  const toolResponse = written.find((r) => r.type === "TOOL_RESPONSE");
  const summary = (() => {
    try { return JSON.parse(fs.readFileSync(P.summary(), "utf8")); } catch (err) { return null; }
  })();
  const stateSessions = (() => {
    try { return JSON.parse(fs.readFileSync(P.stateFile(), "utf8")).__sessions || null; } catch (err) { return null; }
  })();

  const IDKEYS = ["email", "name", "org_id", "org_name", "uid"];
  const FULL_ID = { email: "dev@sigmob.com", name: "开发者", org_id: "019cbcf2-test", org_name: "sigmob", uid: "019efd72-test" };
  const GONE = ["user_info", "client_id", "hostname", "os_user", "user"];
  const idMatches = (r, want) => IDKEYS.every((k) => want[k] === undefined ? !(k in r) : r[k] === want[k]);

  const checks = [
    ["prompt captured (request)", (byType.USER_REQUEST || 0) === 1],
    ["who: enterprise identity at top level on every record", written.length > 0 && written.every((r) => IDKEYS.some((k) => typeof r[k] === "string" && r[k].length > 0)) && written.some((r) => r.email === "dev@sigmob.com")],
    ["where: repo + branch", written.some((r) => r.repo === "sigmob/data-platform" && r.git_branch === "main")],
    ["when: request/response timing", !!toolResponse && !!toolResponse.request_time && !!toolResponse.response_time],
    ["response: PostToolUse tool_response captured", !!toolResponse && /success/.test(String(toolResponse.tool_response))],
    ["response: tool_use_id captured", !!toolResponse && toolResponse.tool_call_id === "call_1"],
    ["failure captured", (byType.TOOL_RESPONSE_FAILURE || 0) === 1],
    ["failure: error text captured", written.some((r) => r.type === "TOOL_RESPONSE_FAILURE" && r.error === "exit 1")],
    ["assistant final message captured", written.some((r) => r.type === "AGENT_STOP" && /12345/.test(String(r.last_assistant_message)))],
    ["credits captured", usage.length === 2 && usage.every((r) => typeof r.credits === "number")],
    ["credits deduplicated across replays", usage.length === 2],
    ["tokens captured (input/output)", usage.some((r) => r.input_tokens === 1200 && r.output_tokens === 90)],
    ["cache tokens captured", usage.some((r) => r.cache_read_input_tokens === 800)],
    ["billable + request_id captured", usage.every((r) => r.billable === true && typeof r.request_id === "string")],
    ["context ratio captured", usage.some((r) => r.context_usage_ratio === 0.25)],
    ["latency computed", usage.some((r) => typeof r.latency_ms === "number" && r.latency_ms > 0)],
    ["tool_result captured (what came back)", toolResults.length === 1 && toolResults[0].is_error === false && /12345/.test(String(toolResults[0].tool_response))],
    ["tool_use args captured", (byType.LLM_TOOL_CALL || 0) === 1],
    ["partial transcript line not consumed", !written.some((r) => String(r.tool_response || "").includes("trailing-partial"))],
    ["secrets redacted", !JSON.stringify(written).includes("hunter2secret")],
    ["summary totals correct", !!summary && Math.abs(summary.today.credits - 0.51) < 1e-9 && summary.today.model_calls === 2],
    ["summary session counters correct", !!summary && summary.sessions[0].tool_failures === 1 && summary.sessions[0].prompts === 1],
    ["who: hook record carries the full flattened identity", !!toolResponse && idMatches(toolResponse, FULL_ID)],
    ["who: transcript-derived records carry the flattened identity", toolCalls.length > 0 && toolResults.length > 0 && usage.length > 0 && toolCalls.concat(toolResults, usage).every((r) => r.email === "dev@sigmob.com" && r.uid === "019efd72-test")],
    ["who: legacy identity fields gone everywhere", written.length > 0 && written.every((r) => GONE.every((k) => !(k in r)))],
    ["who: records without enterprise identity are dropped", !written.some((r) => r.session_id === "s3") && (byType.USER_REQUEST || 0) === 1 && written.every((r) => r.email || r.uid)],
    ["who: partial-key event carries exactly its 2 keys", (function () {
      // Scoped to s1: other notification probes (e.g. the s2 secret probe
      // below) must never be mistaken for the partial-key one.
      const note = written.find((r) => r.type === "NOTIFICATION" && r.session_id === "s1");
      return !!note && idMatches(note, { email: "partial@sigmob.com", uid: "u-partial" })
        && IDKEYS.filter((k) => k in note).length === 2;
    })()],
    ["who: partial keys merge into the full cached identity", toolCalls.concat(toolResults, usage).every((r) => idMatches(r, FULL_ID))],
    ["who: secret-shaped uid masked in record and state cache", (function () {
      const rec = written.find((r) => r.type === "NOTIFICATION" && r.session_id === "s2");
      const cached = stateSessions ? stateSessions["s2"] : null;
      return !!rec && rec.uid === "[redacted jwt]" && !("user_info" in rec)
        && !!cached && !!cached.user_info && cached.user_info.uid === "[redacted jwt]";
    })()],
    // Credentials-file fallback for fleet-wide distributions: a valid file
    // supplies identity when env is empty, env wins over the file, a missing
    // file is the silent local-only default, and unusable files flag an issue
    // without echoing key material. Pure-function checks: CONFIG itself was
    // already resolved at module load against the real per-machine file.
    ...(function () {
      const credDir = fs.mkdtempSync(path.join(os.tmpdir(), "qoder-cred-"));
      const credFile = path.join(credDir, "log-credentials.json");
      fs.writeFileSync(credFile, JSON.stringify({ api_key: "qk_file_key", user_id: "file@sigmob.com" }));
      const results = [
        ["credentials: file supplies identity when env empty", (function () {
          const r = resolveIdentity("", "", credFile);
          return r.apiKey === "qk_file_key" && r.userId === "file@sigmob.com";
        })()],
        ["credentials: env overrides file", (function () {
          const r = resolveIdentity("qk_env_key", "env@sigmob.com", credFile);
          return r.apiKey === "qk_env_key" && r.userId === "env@sigmob.com";
        })()],
        ["credentials: mixed env/file identity merges", (function () {
          const r = resolveIdentity("qk_env_key", "", credFile);
          return r.apiKey === "qk_env_key" && r.userId === "file@sigmob.com";
        })()],
        ["credentials: missing file is silent local-only", (function () {
          const r = resolveIdentity("", "", path.join(credDir, "absent.json"));
          return r.apiKey === "" && r.userId === "" && credentialIssue === null;
        })()],
        ["credentials: broken file flagged without echoing contents", (function () {
          fs.writeFileSync(credFile, "{not json");
          const r = resolveIdentity("", "", credFile);
          return r.apiKey === "" && r.userId === "" && credentialIssue === "malformed JSON";
        })()],
        ["credentials: incomplete file flagged", (function () {
          fs.writeFileSync(credFile, JSON.stringify({ api_key: "qk_only" }));
          const r = resolveIdentity("", "", credFile);
          return r.apiKey === "qk_only" && r.userId === "" && credentialIssue === "missing user_id";
        })()],
        ["credentials: env fills an incomplete file without issue", (function () {
          const r = resolveIdentity("qk_env_key", "env@sigmob.com", credFile);
          return r.apiKey === "qk_env_key" && r.userId === "env@sigmob.com" && credentialIssue === null;
        })()],
      ];
      credentialIssue = null;
      try { fs.rmSync(credDir, { recursive: true, force: true }); } catch (err) { /* best effort */ }
      return results;
    })(),
  ];

  const failures = checks.filter((entry) => !entry[1]);
  process.stderr.write("self-test directory: " + tmp + "\n");
  process.stderr.write("self-test records: " + written.length + " -> " + JSON.stringify(byType) + "\n");
  for (const entry of checks) process.stderr.write((entry[1] ? "  PASS  " : "  FAIL  ") + entry[0] + "\n");
  process.stderr.write(failures.length ? "SELF TEST FAILED (" + failures.length + ")\n" : "SELF TEST OK\n");
  try { fs.rmSync(tmp, { recursive: true, force: true }); } catch (err) { /* best effort */ }
  process.exit(failures.length ? 1 : 0);
}

// ─── Run ──────────────────────────────────────────────────────────────────────
// Last statement of the module: every top-level binding above is initialised.
main();
