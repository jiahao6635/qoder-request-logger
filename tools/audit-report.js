#!/usr/bin/env node
/**
 * Audit report generator for the Qoder request logger plugin.
 *
 * The collector writes the raw trail ($QODER_LOG_DIR/requests_YYYY-MM-DD.jsonl);
 * this tool turns it into a Markdown audit report that answers the questions a
 * team actually asks about an AI coding assistant:
 *
 *   Coverage  - did the collector record tool calls and billing at all?
 *   Cost      - Credits / tokens / cache reads per session, per user, per repo
 *   Activity  - which tools were called, which failed, which files were touched
 *   Risk      - credential redactions and verbatim payload dumps on disk
 *
 * Reads every schema the collector family emits: 1.1.0 (Node collector,
 * optional user_info identity field), 1.0.1-fallback (jq collector) and the
 * flat legacy records. Zero dependencies,
 * never writes outside --out, and exits 1 when coverage checks fail so it can
 * gate a CI job.
 *
 * Usage:
 *   node tools/audit-report.js [--dir ~/.qoder/logs] [--days 7 | --day 2026-08-31]
 *                              [--cwd-filter data-platform] [--top 10]
 *                              [--out report.md] [--json] [--strict]
 */

"use strict";

const fs = require("fs");
const os = require("os");
const path = require("path");

const TOOL_FILE_KEYS = ["file_path", "path", "notebook_path"];

// ─── CLI ────────────────────────────────────────────────────────────────────

function parseArgs(argv) {
  const opts = {
    dir: process.env.QODER_LOG_DIR || path.join(os.homedir(), ".qoder", "logs"),
    days: [],
    cwdFilter: "",
    top: 10,
    out: "",
    json: false,
    strict: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = () => argv[++i];
    if (arg === "--dir") opts.dir = next();
    else if (arg === "--day") opts.days.push(next());
    else if (arg === "--days") {
      const n = parseInt(next(), 10);
      const today = new Date();
      for (let k = 0; k < (Number.isNaN(n) ? 1 : n); k += 1) {
        const d = new Date(today.getTime() - k * 86400000);
        opts.days.push(d.toISOString().slice(0, 10));
      }
    } else if (arg === "--cwd-filter") opts.cwdFilter = next();
    else if (arg === "--top") opts.top = parseInt(next(), 10) || 10;
    else if (arg === "--out") opts.out = next();
    else if (arg === "--json") opts.json = true;
    else if (arg === "--strict") opts.strict = true;
    else throw new Error("unknown argument: " + arg);
  }
  if (!opts.days.length) opts.days.push(new Date().toISOString().slice(0, 10));
  return opts;
}

// ─── Loading ────────────────────────────────────────────────────────────────

function readRecords(dir, days, problems) {
  const records = [];
  const files = [];
  for (const day of days) {
    const file = path.join(dir, "requests_" + day + ".jsonl");
    let text;
    try {
      text = fs.readFileSync(file, "utf8");
    } catch (err) {
      if (err.code !== "ENOENT") problems.push("读取失败 " + file + ": " + err.message);
      continue;
    }
    files.push(file);
    text.split("\n").forEach((line, index) => {
      if (!line.trim()) return;
      try {
        const record = JSON.parse(line);
        if (record && typeof record === "object") {
          record._day = day;
          record._file = file;
          record._line = index + 1;
          records.push(record);
        }
      } catch (err) {
        problems.push(file + ":" + (index + 1) + " 非法 JSON 行，已跳过");
      }
    });
  }
  return { records, files };
}

/**
 * Field aliases across collector generations. The jq fallback nests request /
 * response objects, the Node collector keeps them flat, the legacy script only
 * emits `type`. Normalising here keeps every aggregation readable.
 */
function view(record) {
  const nested = record.request && typeof record.request === "object" ? record.request : {};
  const nestedRes = record.response && typeof record.response === "object" ? record.response : {};
  const type = record.type
    || (record.event ? String(record.event).toUpperCase() : "UNKNOWN");
  return {
    type,
    kind: record.record_kind || (record.log_schema ? "hook_event" : "legacy"),
    collector: record.collector || "node",
    schema: record.log_schema || "n/a",
    event: record.event || type,
    day: record._day,
    timestamp: record.timestamp || "",
    // Transcript-derived records carry the real model-call time; the collector
    // write time is only a fallback, otherwise a replayed session looks flat.
    eventTime: record.llm_record_timestamp || record.timestamp || "",
    session: record.session_id || "unknown",
    user: record.email || record.ingest_user || record.user || "unknown",
    repo: record.repo || "",
    branch: record.git_branch || "",
    cwd: record.cwd || "",
    model: record.model || record.runtime_model || "",
    toolName: record.tool_name || nested.tool_name || "",
    toolInput: record.tool_input || nested.tool_input || "",
    toolResponse: record.tool_response || nestedRes.tool_response || "",
    error: record.error || nestedRes.error || "",
    errorType: record.error_type || nestedRes.error_type || "",
    prompt: record.prompt || nested.prompt || "",
    credits: typeof record.credits === "number" ? record.credits : null,
    inputTokens: num(record.input_tokens),
    outputTokens: num(record.output_tokens),
    cacheRead: num(record.cache_read_input_tokens),
    cacheWrite: num(record.cache_creation_input_tokens),
    contextRatio: typeof record.context_usage_ratio === "number" ? record.context_usage_ratio : null,
    latencyMs: typeof record.latency_ms === "number" ? record.latency_ms : null,
    isError: record.is_error === true,
    hasRaw: typeof record._raw === "string",
    redacted: /"?\[redacted\]"?/.test(JSON.stringify(record)),
  };
}

function num(value) {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function extractFile(toolInput) {
  if (typeof toolInput !== "string" || !toolInput) return "";
  const text = toolInput.trim();
  if (text.startsWith("{")) {
    try {
      const parsed = JSON.parse(text);
      for (const key of TOOL_FILE_KEYS) {
        if (typeof parsed[key] === "string" && parsed[key]) return parsed[key];
      }
    } catch (err) { /* untruncated JSON: fall through to regex */ }
  }
  const match = text.match(/"(?:file_path|path)"\s*:\s*"([^"]+)"/);
  return match ? match[1] : "";
}

// ─── Aggregation ────────────────────────────────────────────────────────────

function newSession(id) {
  return {
    id,
    users: new Set(), repos: new Set(), branches: new Set(), cwds: new Set(), models: new Set(),
    prompts: 0, toolRequests: 0, toolResponses: 0, toolFailures: 0,
    first: "", last: "", startedAt: "", endedAt: "",
    transcriptToolCalls: 0, toolResults: 0, toolResultErrors: 0,
    modelCalls: 0, credits: 0, inputTokens: 0, outputTokens: 0, cacheRead: 0, cacheWrite: 0,
    maxContextRatio: null, maxLatencyMs: null,
    tools: new Map(), files: new Map(), events: new Map(),
    redactions: 0, rawRecords: 0,
    failures: [],
  };
}

function aggregate(views) {
  const sessions = new Map();
  const global = {
    records: views.length, tools: new Map(), files: new Map(),
    users: new Set(), repos: new Set(), schemas: new Set(), collectors: new Set(),
    redactions: 0, rawRecords: 0,
  };
  const byDay = new Map();

  for (const v of views) {
    if (!sessions.has(v.session)) sessions.set(v.session, newSession(v.session));
    const s = sessions.get(v.session);
    if (!byDay.has(v.day)) byDay.set(v.day, { records: 0, credits: 0, toolEvents: 0, usage: 0, sessions: new Set() });
    const day = byDay.get(v.day);
    day.records += 1;
    day.sessions.add(v.session);

    s.events.set(v.type, (s.events.get(v.type) || 0) + 1);
    if (v.timestamp) {
      if (!s.first || v.timestamp < s.first) s.first = v.timestamp;
      if (!s.last || v.timestamp > s.last) s.last = v.timestamp;
    }
    if (v.eventTime) {
      if (!s.startedAt || v.eventTime < s.startedAt) s.startedAt = v.eventTime;
      if (!s.endedAt || v.eventTime > s.endedAt) s.endedAt = v.eventTime;
    }
    addAll(s, v);
    bump(global, v);
    if (v.type === "LLM_USAGE") day.usage += 1;
    if (v.type === "TOOL_REQUEST" || v.type === "TOOL_RESPONSE" || v.type === "TOOL_RESPONSE_FAILURE") day.toolEvents += 1;
  }
  return { sessions, global, byDay };
}

function addAll(s, v) {
  if (v.user) s.users.add(v.user);
  if (v.repo) s.repos.add(v.repo);
  if (v.branch) s.branches.add(v.branch);
  if (v.cwd) s.cwds.add(v.cwd);
  if (v.model) s.models.add(v.model);
}

function bump(global, v) {
  global.schemas.add(v.schema);
  global.collectors.add(v.collector);
  global.users.add(v.user);
  if (v.repo) global.repos.add(v.repo);
  if (v.redacted) global.redactions += 1;
  if (v.hasRaw) global.rawRecords += 1;
  if (v.toolName && /^(TOOL_REQUEST|TOOL_RESPONSE|LLM_TOOL_CALL)$/.test(v.type)) {
    global.tools.set(v.toolName, (global.tools.get(v.toolName) || 0) + 1);
  }
  const file = extractFile(v.toolInput);
  if (file) global.files.set(file, (global.files.get(file) || 0) + 1);
}

function sessionStats(s, views) {
  for (const v of views.filter((r) => r.session === s.id)) {
    switch (v.type) {
      case "USER_REQUEST":
      case "USER_REQUEST_TRANSCRIPT":
        s.prompts += 1;
        break;
      case "TOOL_REQUEST":
        s.toolRequests += 1;
        countTool(s, v);
        break;
      case "TOOL_RESPONSE":
        s.toolResponses += 1;
        countTool(s, v);
        break;
      case "TOOL_RESPONSE_FAILURE":
        s.toolFailures += 1;
        countTool(s, v);
        s.failures.push({ tool: v.toolName || "?", error: clip(v.error, 160), type: v.errorType });
        break;
      case "LLM_TOOL_CALL":
        s.transcriptToolCalls += 1;
        break;
      case "TOOL_RESULT":
        s.toolResults += 1;
        if (v.isError) s.toolResultErrors += 1;
        break;
      case "LLM_USAGE":
        s.modelCalls += 1;
        s.credits += v.credits || 0;
        s.inputTokens += v.inputTokens || 0;
        s.outputTokens += v.outputTokens || 0;
        s.cacheRead += v.cacheRead || 0;
        s.cacheWrite += v.cacheWrite || 0;
        if (v.contextRatio != null) s.maxContextRatio = Math.max(s.maxContextRatio || 0, v.contextRatio);
        if (v.latencyMs != null) s.maxLatencyMs = Math.max(s.maxLatencyMs || 0, v.latencyMs);
        break;
      default:
        break;
    }
    if (v.hasRaw) s.rawRecords += 1;
    if (v.redacted) s.redactions += 1;
  }
  return s;
}

function countTool(s, v) {
  if (!v.toolName) return;
  s.tools.set(v.toolName, (s.tools.get(v.toolName) || 0) + 1);
  const file = extractFile(v.toolInput);
  if (file) s.files.set(file, (s.files.get(file) || 0) + 1);
}

// ─── Coverage findings ──────────────────────────────────────────────────────

function findings(records, sessions, errorLog) {
  const list = [];
  const views = records;
  if (!views.length) {
    list.push({ level: "ERROR", text: "指定目录里没有采集记录：采集器未安装、未启用，或 QODER_LOG_DIR 指向了别处。" });
    return list;
  }
  const toolEvents = views.filter((v) => /^TOOL_(REQUEST|RESPONSE|RESPONSE_FAILURE)$/.test(v.type)).length;
  const usageEvents = views.filter((v) => v.type === "LLM_USAGE").length;
  const kinds = new Set(views.map((v) => v.kind));
  const schemas = new Set(views.map((v) => v.schema));

  if (toolEvents === 0) {
    list.push({ level: "ERROR", text: "0 条工具级记录：PreToolUse / PostToolUse 未注册，或插件是在会话启动后才安装的（hooks 在会话启动时加载，需重启会话）。" });
  } else {
    list.push({ level: "OK", text: "工具级记录 " + toolEvents + " 条：请求与响应均已入档。" });
  }
  if (usageEvents === 0) {
    list.push({ level: "WARN", text: "0 条 LLM_USAGE 记录：Credits / token 成本不可见，通常是 transcript 未被采集（QODER_LOG_INCLUDE_TRANSCRIPT=0）或使用了旧版采集脚本。" });
  } else {
    const credits = views.reduce((sum, v) => sum + (v.credits || 0), 0);
    list.push({ level: "OK", text: "模型调用 " + usageEvents + " 次，合计 Credits " + credits.toFixed(3) + "。" });
  }
  if (schemas.has("n/a")) {
    list.push({ level: "WARN", text: "存在无 log_schema 的旧版记录：由独立脚本 ~/.qoder/hooks/request-logger 产生，建议下线，避免与插件双写。" });
  }
  if (kinds.has("hook_event") && views.some((v) => v.collector === "jq-fallback")) {
    list.push({ level: "WARN", text: "部分记录来自 jq 兜底采集器：Node.js 未找到，成本字段只做近似快照，不可跨记录求和。" });
  }
  const rawCount = views.filter((v) => v.hasRaw).length;
  if (rawCount) {
    list.push({ level: "WARN", text: rawCount + " 条记录含 _raw 原始 payload 落盘（QODER_LOG_RAW=1）：敏感面最大，导出或共享日志前请先设置 QODER_LOG_RAW=0。" });
  }
  const redacted = views.filter((v) => v.redacted).length;
  if (redacted) {
    list.push({ level: "WARN", text: redacted + " 条记录命中脱敏（[redacted]）：说明提示词或工具输出里出现过凭据，脱敏已生效，但源头需要治理。" });
  }
  if (errorLog.trim()) {
    const lines = errorLog.trim().split("\n");
    list.push({ level: "ERROR", text: "采集器自身报错 " + lines.length + " 条，最后一条：" + clip(lines[lines.length - 1], 200) });
  }
  if (!list.some((f) => f.level === "ERROR") && toolEvents > 0) {
    list.push({ level: "OK", text: "采集链路健康：" + sessions + " 个会话的记录完整。" });
  }
  return list;
}

function readErrorLog(dir) {
  try {
    return fs.readFileSync(path.join(dir, ".request-logger", "logger-error.log"), "utf8");
  } catch (err) {
    return "";
  }
}

// ─── Markdown rendering ─────────────────────────────────────────────────────

function mdEscape(text) {
  return String(text == null ? "" : text).replace(/\|/g, "\\|").replace(/\n/g, " ");
}

function clip(text, max) {
  const str = String(text == null ? "" : text);
  return str.length <= max ? str : str.slice(0, max) + "…";
}

function topTools(map, limit) {
  return Array.from(map.entries()).sort((a, b) => b[1] - a[1]).slice(0, limit);
}

function durationSeconds(start, end) {
  const a = Date.parse(start);
  const b = Date.parse(end);
  if (Number.isNaN(a) || Number.isNaN(b)) return null;
  return Math.round((b - a) / 1000);
}

function renderMarkdown(sessions, global, byDay, problems, opts) {
  const out = [];
  const push = (line) => out.push(line);
  const allViews = global.views;

  push("# Qoder 操作日志审计报告");
  push("");
  push("- 生成时间：" + new Date().toISOString());
  push("- 日志目录：" + opts.dir);
  push("- 覆盖日期：" + opts.days.join(", "));
  push("- 记录数：" + global.records + "（解析失败 " + problems.length + " 行）");
  if (opts.cwdFilter) push("- 工作区过滤：`" + opts.cwdFilter + "`");
  push("");

  push("## 1. 采集健康度");
  push("");
  push("| 结论 | 级别 | 说明 |");
  push("| --- | --- | --- |");
  for (const f of global.findings) push("| " + (f.level === "OK" ? "✅" : f.level === "WARN" ? "⚠️" : "❌") + " | " + f.level + " | " + mdEscape(f.text) + " |");
  push("");

  push("## 2. 每日概览");
  push("");
  push("| 日期 | 记录 | 会话 | 工具级记录 | 模型调用记录 | Credits |");
  push("| --- | --- | --- | --- | --- | --- |");
  for (const day of Array.from(byDay.keys()).sort()) {
    const d = byDay.get(day);
    const credits = allViews.filter((v) => v.day === day).reduce((sum, v) => sum + (v.credits || 0), 0);
    push("| " + day + " | " + d.records + " | " + d.sessions.size + " | " + d.toolEvents + " | " + d.usage + " | " + credits.toFixed(3) + " |");
  }
  push("");

  push("## 3. 会话明细");
  push("");
  push("| 会话 | 用户 | 仓库/分支 | 起止 | 提示 | 工具调用 | 失败 | 模型轮次 | Credits | In/Out tokens | 峰值上下文 |");
  push("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |");
  const sorted = sessions.slice().sort((a, b) => (b.credits - a.credits) || String(b.last).localeCompare(String(a.last)));
  for (const s of sorted.slice(0, opts.top)) {
    const dur = durationSeconds(s.startedAt || s.first, s.endedAt || s.last);
    const repo = Array.from(s.repos)[0] || Array.from(s.cwds)[0] || "-";
    const branch = Array.from(s.branches)[0] || "";
    push("| " + mdEscape(clip(s.id, 13))
      + " | " + mdEscape(clip(Array.from(s.users)[0] || "-", 28))
      + " | " + mdEscape(clip(repo + (branch ? "@" + branch : ""), 40))
      + " | " + (dur == null ? "-" : dur + "s")
      + " | " + s.prompts
      + " | " + (s.toolRequests + s.toolResponses + s.transcriptToolCalls)
      + " | " + (s.toolFailures + s.toolResultErrors)
      + " | " + s.modelCalls
      + " | " + s.credits.toFixed(3)
      + " | " + s.inputTokens + " / " + s.outputTokens
      + " | " + (s.maxContextRatio == null ? "-" : (s.maxContextRatio * 100).toFixed(1) + "%") + " |");
  }
  push("");

  push("## 4. 工具使用分布");
  push("");
  if (!global.tools.size) {
    push("_无工具级记录，说明 PreToolUse / PostToolUse 未生效。_");
  } else {
    push("| 工具 | 调用次数 |");
    push("| --- | --- |");
    for (const [tool, count] of topTools(global.tools, 30)) push("| `" + mdEscape(tool) + "` | " + count + " |");
  }
  push("");

  push("## 5. 涉及文件");
  push("");
  if (!global.files.size) {
    push("_未从工具入参中解析到文件路径。_");
  } else {
    push("| 文件 | 出现次数 |");
    push("| --- | --- |");
    for (const [file, count] of topTools(global.files, opts.top)) push("| `" + mdEscape(clip(file, 110)) + "` | " + count + " |");
  }
  push("");

  push("## 6. 失败调用");
  push("");
  const failures = [];
  for (const s of sessions) for (const f of s.failures) failures.push(Object.assign({ session: s.id }, f));
  if (!failures.length) {
    push("_窗口内没有 PostToolUseFailure 记录。_");
  } else {
    push("| 会话 | 工具 | 错误类型 | 错误 |");
    push("| --- | --- | --- | --- |");
    for (const f of failures.slice(0, opts.top)) {
      push("| " + mdEscape(clip(f.session, 13)) + " | `" + mdEscape(f.tool) + "` | " + mdEscape(f.type || "-") + " | " + mdEscape(clip(f.error, 160)) + " |");
    }
  }
  push("");

  push("## 7. 人员与仓库归因");
  push("");
  push("| 用户 | 会话 | Credits |");
  push("| --- | --- | --- |");
  const perUser = new Map();
  for (const s of sessions) {
    const user = Array.from(s.users)[0] || "unknown";
    if (!perUser.has(user)) perUser.set(user, { sessions: 0, credits: 0 });
    const entry = perUser.get(user);
    entry.sessions += 1;
    entry.credits += s.credits;
  }
  for (const [user, entry] of Array.from(perUser.entries()).sort((a, b) => b[1].credits - a[1].credits)) {
    push("| " + mdEscape(user) + " | " + entry.sessions + " | " + entry.credits.toFixed(3) + " |");
  }
  push("");

  if (problems.length) {
    push("## 8. 解析告警");
    push("");
    for (const p of problems.slice(0, 20)) push("- " + mdEscape(clip(p, 200)));
    push("");
  }
  return out.join("\n");
}

// ─── Main ───────────────────────────────────────────────────────────────────

function main() {
  const opts = parseArgs(process.argv.slice(2));
  const problems = [];
  const { records } = readRecords(opts.dir, opts.days, problems);
  let views = records.map(view);
  if (opts.cwdFilter) {
    views = views.filter((v) => v.cwd.includes(opts.cwdFilter) || v.repo.includes(opts.cwdFilter));
  }
  const { sessions, global, byDay } = aggregate(views);
  const list = [];
  for (const session of sessions.values()) list.push(sessionStats(session, views));
  global.views = views;
  global.findings = findings(views, list.length, readErrorLog(opts.dir));

  if (opts.json) {
    const payload = {
      days: opts.days,
      records: views.length,
      sessions: list.map((s) => ({
        id: s.id,
        users: Array.from(s.users),
        repos: Array.from(s.repos),
        cwds: Array.from(s.cwds),
        started_at: s.first,
        ended_at: s.last,
        prompts: s.prompts,
        tool_requests: s.toolRequests,
        tool_responses: s.toolResponses,
        tool_failures: s.toolFailures + s.toolResultErrors,
        transcript_tool_calls: s.transcriptToolCalls,
        model_calls: s.modelCalls,
        credits: Number(s.credits.toFixed(6)),
        input_tokens: s.inputTokens,
        output_tokens: s.outputTokens,
        cache_read_tokens: s.cacheRead,
        cache_creation_tokens: s.cacheWrite,
        max_context_usage_ratio: s.maxContextRatio,
        tools: Object.fromEntries(s.tools),
        files: Object.fromEntries(s.files),
        redacted_records: s.redactions,
        raw_payload_records: s.rawRecords,
      })),
      findings: global.findings,
      parse_problems: problems,
    };
    const text = JSON.stringify(payload, null, 2);
    if (opts.out) fs.writeFileSync(opts.out, text + "\n", "utf8");
    else process.stdout.write(text + "\n");
  } else {
    const markdown = renderMarkdown(list, global, byDay, problems, opts);
    if (opts.out) {
      fs.mkdirSync(path.dirname(path.resolve(opts.out)), { recursive: true });
      fs.writeFileSync(opts.out, markdown + "\n", "utf8");
      process.stdout.write("报告已写入 " + opts.out + "\n");
    } else {
      process.stdout.write(markdown + "\n");
    }
  }

  const broken = global.findings.some((f) => f.level === "ERROR");
  process.exit(opts.strict && broken ? 1 : 0);
}

main();
