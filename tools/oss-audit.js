#!/usr/bin/env node
/**
 * oss-audit.js — Qoder 使用审计日志 OSS 查询工具（零 npm 依赖，仅 Node 内置模块）。
 * 按 docs/oss-path-spec.md 契约定位对象：
 *   <prefix>date=<北京日期>/user=<邮箱>/src=<qoder|qoderwork>/part-*.jsonl.gz
 *   <prefix>_manifest/date=<D>.json.gz        （prefix 默认 logs/qoder/v1/）
 *
 * 子命令：fetch（下载合并某日日志为 requests_<date>.jsonl，--user/--src 过滤，--report 出报告）、
 *   manifest（读 _manifest 打印 per-user 汇总表）、coverage（实际上报 user= 分区 vs 注册表）。
 * 数据源：默认 OSS 模式（ossutil；bucket/prefix 由 QODER_AUDIT_BUCKET/QODER_AUDIT_PREFIX 覆盖，
 * 默认 sigmob-logs / logs/qoder/v1/）；--from-local ROOT 本地模式把 ROOT 当桶根平铺遍历（离线
 * 测试，行为一致）。表格/统计走 stdout；错误一律走 stderr 且 exit 非零。
 */
"use strict";

const fs = require("fs");
const os = require("os");
const path = require("path");
const zlib = require("zlib");
const crypto = require("crypto");
const { execFile, spawnSync } = require("child_process");

const PROG = "oss-audit";
const DEFAULT_BUCKET = "sigmob-logs";
const DEFAULT_PREFIX = "logs/qoder/v1";
const EXEC_OPTS = { timeout: 60 * 1000, maxBuffer: 64 * 1024 * 1024, encoding: "utf8" };
const OSSUTIL_INSTALL_HINT =
  "未找到 ossutil 命令。请先安装并配置：参考 https://help.aliyun.com/document_detail/120075.html" +
  "（macOS 可 brew install aliyun-ossutil），并按 runbook §1.3 用审计只读账号 qoder-log-auditor 配置凭证。";
const USAGE = [
  "用法: node tools/oss-audit.js <子命令> [选项]",
  "",
  "子命令:",
  "  fetch      下载某日审计日志并合并为 <out>/requests_<date>.jsonl",
  "  manifest   读取某日 _manifest 清单, 打印 per-user 汇总表",
  "  coverage   实际上报用户 vs api-keys.yml 注册表 (谁没上报)",
  "",
  "选项:",
  "  --date YYYY-MM-DD      分区日期 (北京日期, 必填)",
  "  --user EMAIL           仅该用户 (fetch)",
  "  --src qoder|qoderwork  仅该来源 (fetch)",
  "  --out DIR              输出/缓存目录, 默认 ~/.qoder-audit-cache (fetch)",
  "  --report               合并完成后自动运行 tools/audit-report.js (fetch)",
  "  --registry FILE        api-keys.yml 注册表 (coverage)",
  "  --from-local ROOT      本地模式: ROOT 作为桶根直接遍历 (不走 ossutil)",
  "  --help                 显示本帮助",
  "",
  "环境变量: QODER_AUDIT_BUCKET (默认 " + DEFAULT_BUCKET + "), QODER_AUDIT_PREFIX (默认 " + DEFAULT_PREFIX + "/)",
].join("\n");

// ── 通用小工具 ──

function die(msg, code) {
  process.stderr.write(PROG + ": " + msg + "\n");
  process.exit(code === undefined ? 1 : code);
}

function sha16(key) {
  return crypto.createHash("sha256").update(key).digest("hex").slice(0, 16);
}

function humanBytes(n) {
  if (n < 1024) return n + " B";
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
  if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + " MB";
  return (n / 1024 / 1024 / 1024).toFixed(2) + " GB";
}

// 简易显示宽度（CJK 记 2 列），用于表格对齐
function strWidth(s) {
  let w = 0;
  for (const ch of String(s)) w += ch.charCodeAt(0) > 0xff ? 2 : 1;
  return w;
}
function padEndW(s, w) {
  return String(s) + " ".repeat(Math.max(0, w - strWidth(s)));
}
function padStartW(s, w) {
  return " ".repeat(Math.max(0, w - strWidth(s))) + String(s);
}

function countLines(text) {
  if (!text) return 0;
  return (text.match(/\n/g) || []).length + (text.endsWith("\n") ? 0 : 1);
}

// part 文件名（段关闭时刻在前）升序 → 合并结果时间序大致有序；全键作次序保证确定性
function byPartName(a, b) {
  const na = path.posix.basename(a);
  const nb = path.posix.basename(b);
  return na !== nb ? (na < nb ? -1 : 1) : a < b ? -1 : a > b ? 1 : 0;
}

// ── ossutil 封装（child_process.execFile + argv 数组，60s 超时，不经过 shell） ──

function execFileP(file, args, opts) {
  return new Promise((resolve, reject) =>
    execFile(file, args, opts, (err, stdout, stderr) => {
      if (err) {
        err.stderr = stderr;
        err.stdout = stdout;
        reject(err);
      } else resolve({ stdout: stdout, stderr: stderr });
    })
  );
}

function ossutilError(err) {
  if (err && err.code === "ENOENT") return new Error(OSSUTIL_INSTALL_HINT);
  const detail = String((err && err.stderr) || (err && err.stdout) || "").trim();
  const code = err && typeof err.code === "number" ? " (exit " + err.code + ")" : "";
  const first = detail ? detail.split("\n")[0] : err && err.message ? err.message : String(err);
  return new Error("ossutil 执行失败" + code + ": " + first);
}

// 列举 bucket 下某前缀的全部对象键（不含 bucket 名）
async function ossListKeys(bucket, prefix) {
  let stdout = "";
  try {
    stdout = (await execFileP("ossutil", ["ls", "oss://" + bucket + "/" + prefix], EXEC_OPTS)).stdout;
  } catch (err) {
    if (err.code === "ENOENT") throw new Error(OSSUTIL_INSTALL_HINT);
    // 前缀无对象时 ossutil 可能非零退出：stdout 解析不到键且 stderr 为空 → 视为空结果
    const keys = parseLsKeys(err.stdout || "", prefix);
    if (keys.length) return keys;
    if (!String(err.stderr || "").trim()) return [];
    throw ossutilError(err);
  }
  return parseLsKeys(stdout, prefix);
}

function parseLsKeys(stdout, prefix) {
  const keys = new Set();
  for (const line of String(stdout).split("\n")) {
    const t = line.trim();
    if (!t) continue;
    const last = t.split(/\s+/).pop(); // 对象键为行末列（契约键不含空白字符）
    if (last.startsWith(prefix)) keys.add(last);
  }
  return Array.from(keys).sort();
}

// 下载单个对象到本地文件（先写 .part 再原子 rename，失败不留残件）
async function ossGetObjectToFile(bucket, key, destFile) {
  const tmp = destFile + ".part." + process.pid;
  try {
    await execFileP("ossutil", ["cp", "-f", "oss://" + bucket + "/" + key, tmp], EXEC_OPTS);
    fs.renameSync(tmp, destFile);
  } catch (err) {
    try {
      fs.unlinkSync(tmp);
    } catch (e) { /* 忽略清理失败 */ }
    throw ossutilError(err);
  }
}

// ── 本地模式 walker（ROOT 为桶根，对象按 root/<key> 平铺存放） ──

function localListKeys(root, prefix) {
  const startDir = path.join(root, prefix);
  const out = [];
  if (!fs.existsSync(startDir)) return out;
  const walk = (dir) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, e.name);
      if (e.isDirectory()) walk(full);
      else if (e.isFile()) out.push(path.relative(root, full).split(path.sep).join("/"));
    }
  };
  walk(startDir);
  return out.sort();
}

// date 目录的直接子目录中取 user= 段（目录名匹配，与 URL 编码无关）
function localUserDirs(root, datePrefix) {
  const dir = path.join(root, datePrefix);
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory() && e.name.startsWith("user=")) out.push(e.name.slice("user=".length));
  }
  return out.sort();
}

// ── 统一对象访问层（ctx = { local, root, bucket, prefix }） ──

async function listPartKeys(ctx, date, user, src) {
  let p = ctx.prefix + "date=" + date + "/";
  if (user) p += "user=" + user + "/";
  if (src) p += "src=" + src + "/";
  const keys = ctx.local ? localListKeys(ctx.root, p) : await ossListKeys(ctx.bucket, p);
  return keys.filter((k) => /\/part-[^/]+\.jsonl\.gz$/.test(k));
}

// 确保 destFile 上有该对象内容（本地复制 / ossutil 下载），失败抛错
async function ensureObjectFile(ctx, key, destFile) {
  if (ctx.local) {
    const src = path.join(ctx.root, key);
    if (!fs.existsSync(src)) throw new Error("本地模式对象不存在: " + src);
    fs.copyFileSync(src, destFile);
  } else {
    await ossGetObjectToFile(ctx.bucket, key, destFile);
  }
}

// 读取某日 manifest；缺失/失败返回 { ok:false, reason }，不抛错
async function readManifest(ctx, date) {
  const key = ctx.prefix + "_manifest/date=" + date + ".json.gz";
  let buf = null;
  let reason = "";
  if (ctx.local) {
    const p = path.join(ctx.root, key);
    if (fs.existsSync(p)) {
      try {
        buf = fs.readFileSync(p);
      } catch (err) {
        reason = err.message;
      }
    }
  } else {
    const tmp = path.join(os.tmpdir(), "oss-audit-manifest-" + date + "-" + process.pid + ".json.gz");
    try {
      await ossGetObjectToFile(ctx.bucket, key, tmp);
      buf = fs.readFileSync(tmp);
    } catch (err) {
      reason = err.message;
    }
    try {
      fs.unlinkSync(tmp);
    } catch (e) { /* 忽略清理失败 */ }
  }
  if (!buf) return { ok: false, key: key, reason: reason };
  try {
    return { ok: true, key: key, manifest: JSON.parse(zlib.gunzipSync(buf).toString("utf8")) };
  } catch (err) {
    return { ok: false, key: key, reason: "manifest 解析失败: " + err.message };
  }
}

// ── api-keys.yml 轻量解析（仅支持 keys: 列表这一种结构） ──

function ymlScalar(s) {
  const v = String(s).trim();
  const quoted = v.length >= 2 && ((v[0] === '"' && v[v.length - 1] === '"') || (v[0] === "'" && v[v.length - 1] === "'"));
  if (quoted) return v.slice(1, -1);
  if (v === "true") return true;
  if (v === "false") return false;
  return v;
}

function parseApiKeysYml(file) {
  const entries = [];
  let inKeys = false;
  let cur = null;
  for (const rawLine of fs.readFileSync(file, "utf8").split("\n")) {
    // 去行注释与 \r（本注册表字段值不含 '#'，轻量实现够用）
    const line = rawLine.replace(/\r$/, "").replace(/#.*$/, "");
    if (!line.trim()) continue;
    const item = /^(\s*)-\s*([A-Za-z0-9_]+):\s*(.*)$/.exec(line);
    if (inKeys && item) {
      cur = {};
      cur[item[2]] = ymlScalar(item[3]);
      entries.push(cur);
      continue;
    }
    const kv = /^(\s*)([A-Za-z0-9_]+):\s*(.*)$/.exec(line);
    if (kv && kv[1] === "") {
      inKeys = kv[2] === "keys";
      cur = null;
    } else if (inKeys && kv && cur) {
      cur[kv[2]] = ymlScalar(kv[3]);
    }
  }
  return entries.filter((e) => e.user_id);
}

// ── CLI 解析 ──

function parseArgs(argv) {
  const opts = {
    cmd: "", date: "", user: "", src: "", out: "", registry: "", fromLocal: "",
    report: false, help: false,
    bucket: process.env.QODER_AUDIT_BUCKET || DEFAULT_BUCKET,
  };
  const rawPrefix = process.env.QODER_AUDIT_PREFIX || DEFAULT_PREFIX;
  opts.prefix = rawPrefix.replace(/^\/+|\/+$/g, "");
  opts.prefix = opts.prefix ? opts.prefix + "/" : "";
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    const need = () => {
      i += 1;
      if (i >= argv.length) throw new Error(a + " 需要一个参数值");
      return argv[i];
    };
    if (a === "--help" || a === "-h") opts.help = true;
    else if (a === "--date") opts.date = need();
    else if (a === "--user") opts.user = need();
    else if (a === "--src") opts.src = need();
    else if (a === "--out") opts.out = need();
    else if (a === "--report") opts.report = true;
    else if (a === "--from-local") opts.fromLocal = need();
    else if (a === "--registry") opts.registry = need();
    else if (!opts.cmd && (a === "fetch" || a === "manifest" || a === "coverage")) opts.cmd = a;
    else throw new Error("未知参数: " + a);
  }
  return opts;
}

// ── 子命令: fetch ──

async function cmdFetch(opts, ctx) {
  fs.mkdirSync(opts.out, { recursive: true });
  const keys = (await listPartKeys(ctx, opts.date, opts.user, opts.src)).sort(byPartName);
  const outPath = path.join(opts.out, "requests_" + opts.date + ".jsonl");
  const fd = fs.openSync(outPath, "w");
  let lines = 0;
  let bytes = 0;
  let downloaded = 0;
  let cacheHit = 0;
  try {
    for (const key of keys) {
      // 增量缓存：<out>/<sha256(key) 前 16 位>/<part 文件名>，已存在则跳过下载
      const cacheDir = path.join(opts.out, sha16(key));
      const cacheFile = path.join(cacheDir, path.posix.basename(key));
      if (fs.existsSync(cacheFile)) {
        cacheHit += 1;
      } else {
        fs.mkdirSync(cacheDir, { recursive: true });
        await ensureObjectFile(ctx, key, cacheFile);
        downloaded += 1;
      }
      const text = zlib.gunzipSync(fs.readFileSync(cacheFile)).toString("utf8");
      if (text.length) {
        bytes += Buffer.byteLength(text, "utf8");
        lines += countLines(text);
        fs.writeSync(fd, text);
      }
    }
  } finally {
    fs.closeSync(fd);
  }
  console.log("命中对象: " + keys.length + "（新下载 " + downloaded + " / 缓存命中 " + cacheHit + "）");
  console.log("总行数: " + lines);
  console.log("解压后大小: " + humanBytes(bytes));
  console.log("输出文件: " + outPath);
  if (opts.report) {
    const reportJs = path.join(__dirname, "audit-report.js");
    if (!fs.existsSync(reportJs)) die("未找到审计报表工具: " + reportJs);
    console.log("── 调用 tools/audit-report.js ──");
    const r = spawnSync(process.execPath, [reportJs, "--dir", opts.out, "--day", opts.date], { stdio: "inherit" });
    if (r.error) die("无法启动 audit-report.js: " + r.error.message);
    if (r.status !== 0) process.exit(r.status === null ? 1 : r.status);
  }
}

// ── 子命令: manifest ──

async function cmdManifest(opts, ctx) {
  const res = await readManifest(ctx, opts.date);
  if (!res.ok) {
    process.stderr.write(PROG + ": 当日无清单（可能尚未生成或当日无数据）: " + res.key + "\n");
    if (res.reason) process.stderr.write(PROG + ": 获取失败原因: " + res.reason + "\n");
    process.stderr.write(
      PROG + ": 可用 ossutil 确认: ossutil ls oss://" + (ctx.local ? "<bucket>" : ctx.bucket) + "/" + res.key + "\n"
    );
    process.exit(1);
  }
  const m = res.manifest;
  const users = Array.isArray(m.users) ? m.users : [];
  console.log(
    "manifest: " + res.key + "  (date=" + (m.date || opts.date) + ", generated_at=" + (m.generated_at || "-") +
      ", users=" + users.length + ")"
  );
  const header = ["用户", "记录数", "credits", "首条时间", "末条时间"];
  const rows = users.map((u) => [
    String(u.user || "?"),
    String(Number(u.lines_total) || 0),
    (Number(u.credits_total) || 0).toFixed(2),
    String(u.first_ts || "-"),
    String(u.last_ts || "-"),
  ]);
  const w = [];
  for (let c = 0; c < header.length; c += 1) {
    w[c] = Math.max(strWidth(header[c]), Math.max.apply(null, rows.map((r) => strWidth(r[c])))) + 2;
  }
  console.log(padEndW(header[0], w[0]) + padStartW(header[1], w[1]) + padStartW(header[2], w[2]) + "  " + header[3] + "  " + header[4]);
  let lineTotal = 0;
  let creditTotal = 0;
  for (const r of rows) {
    lineTotal += Number(r[1]) || 0;
    creditTotal += Number(r[2]) || 0;
    console.log(padEndW(r[0], w[0]) + padStartW(r[1], w[1]) + padStartW(r[2], w[2]) + "  " + r[3] + "  " + r[4]);
  }
  console.log(padEndW("合计", w[0]) + padStartW(String(lineTotal), w[1]) + padStartW(creditTotal.toFixed(2), w[2]));
}

// ── 子命令: coverage ──

function printUserList(title, list) {
  console.log(title + " (" + list.length + "):");
  if (list.length) list.forEach((u) => console.log("  " + u));
  else console.log("  （无）");
}

async function cmdCoverage(opts, ctx) {
  const datePrefix = ctx.prefix + "date=" + opts.date + "/";
  let reported;
  if (ctx.local) {
    reported = localUserDirs(ctx.root, datePrefix);
  } else {
    const keys = await ossListKeys(ctx.bucket, datePrefix);
    const seen = new Set();
    for (const k of keys) {
      const m = /\/user=([^/]+)\//.exec(k);
      if (m) seen.add(m[1]);
    }
    reported = Array.from(seen).sort();
  }
  if (!opts.registry) {
    console.log("date=" + opts.date + " 实际出现的 user= 分区（共 " + reported.length + "，未提供 --registry，仅列实际用户）:");
    if (!reported.length) console.log("  （无）");
    for (const u of reported) console.log("  " + u);
    return;
  }
  if (!fs.existsSync(opts.registry)) die("注册表文件不存在: " + opts.registry);
  const registry = parseApiKeysYml(opts.registry);
  if (!registry.length) {
    process.stderr.write(PROG + ": 警告: " + opts.registry + " 未解析到任何条目（期望 keys: 列表结构）\n");
  }
  const reportedSet = new Set(reported);
  const onList = [];
  const offList = [];
  for (const r of registry) {
    const tag = r.enabled === false ? "  [enabled=false]" : "";
    (reportedSet.has(r.user_id) ? onList : offList).push(r.user_id + tag);
  }
  const enabled = registry.filter((r) => r.enabled !== false);
  const enabledOn = enabled.filter((r) => reportedSet.has(r.user_id));
  const unknown = reported.filter((u) => !registry.some((r) => r.user_id === u));
  console.log("date=" + opts.date + " 上报情况（注册表 " + registry.length + " 人，实际出现 " + reported.length + " 个 user= 分区）");
  console.log("");
  printUserList("已上报", onList);
  console.log("");
  printUserList("未上报", offList);
  console.log("");
  const rate = enabled.length ? ((enabledOn.length / enabled.length) * 100).toFixed(1) + "%" : "n/a";
  console.log("上报率: " + enabledOn.length + "/" + enabled.length + " = " + rate + "（按 enabled=true 注册用户计，disabled 不计入分母）");
  if (unknown.length) {
    console.log("注册表外用户（OSS 有数据但 api-keys.yml 无此 user_id，请核查）: " + unknown.join(", "));
  }
}

// ── main ──

async function main() {
  let opts;
  try {
    opts = parseArgs(process.argv.slice(2));
  } catch (err) {
    process.stderr.write(USAGE + "\n");
    die(err.message);
    return;
  }
  if (opts.help || !opts.cmd) {
    process.stdout.write(USAGE + "\n");
    process.exit(opts.help ? 0 : 1);
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(opts.date)) die("--date 需为 YYYY-MM-DD 格式（收到: '" + opts.date + "'）");
  if (opts.user && !/^[a-z0-9._@-]+$/.test(opts.user)) {
    die("--user 需为小写公司邮箱，字符白名单 [a-z0-9._@-]（收到: '" + opts.user + "'）");
  }
  if (opts.src && opts.src !== "qoder" && opts.src !== "qoderwork") {
    die("--src 仅支持 qoder 或 qoderwork（收到: '" + opts.src + "'）");
  }
  if (!opts.out) opts.out = path.join(os.homedir(), ".qoder-audit-cache");
  const ctx = opts.fromLocal
    ? { local: true, root: opts.fromLocal, bucket: "", prefix: opts.prefix }
    : { local: false, root: "", bucket: opts.bucket, prefix: opts.prefix };
  if (ctx.local && !fs.existsSync(ctx.root)) die("--from-local 桶根目录不存在: " + ctx.root);
  if (opts.cmd === "fetch") await cmdFetch(opts, ctx);
  else if (opts.cmd === "manifest") await cmdManifest(opts, ctx);
  else await cmdCoverage(opts, ctx);
}

main().catch((err) => die(err && err.message ? err.message : String(err)));
