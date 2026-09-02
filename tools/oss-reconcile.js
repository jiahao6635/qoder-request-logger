#!/usr/bin/env node
// oss-reconcile.js — G2 灰度对账工具（零 npm 依赖，仅 Node 内置模块）。
// X = 本地 requests_<day>.jsonl（含 .1/.2 变体）行数合计；Y = upload-state.json 游标覆盖行数；
// Z = _manifest 该日全部 users 的 lines_total 合计。X==Z → PASS；X>Z → WARN 缺失（游标未推进/
// spool 未上传）；Z>X → WARN 疑似重复（多机多份属正常，需按机器细分口径）。
// manifest 来源优先级: --manifest > --from-local > ossutil。退出码: 0=PASS；1=WARN；2=错误。
"use strict";

const fs = require("fs");
const os = require("os");
const path = require("path");
const zlib = require("zlib");
const { execFile } = require("child_process");

const PROG = "oss-reconcile";
const DEFAULT_BUCKET = "sigmob-logs";
const DEFAULT_PREFIX = "logs/qoder/v1";
const EXEC_OPTS = { timeout: 60 * 1000, maxBuffer: 64 * 1024 * 1024, encoding: "utf8" };
const OSSUTIL_INSTALL_HINT =
  "未找到 ossutil 命令。请先安装并配置：参考 https://help.aliyun.com/document_detail/120075.html（macOS 可 brew install aliyun-ossutil），并按 runbook §1.3 用审计只读账号 qoder-log-auditor 配置凭证。";
const USAGE = [
  "用法: node tools/oss-reconcile.js --day YYYY-MM-DD --local-dir <目录> [选项]",
  "  --day YYYY-MM-DD / --local-dir DIR   必填: 对账日 / 客户端日志目录（如 ~/.qoder/logs）",
  "  --state DIR        游标状态目录, 默认 <local-dir>/.request-logger（读 upload-state.json）",
  "  --manifest FILE    已下载的 manifest（.json.gz/.json）, 也支持 oss://bucket/key",
  "  --from-local ROOT  本地桶根模式: 从 ROOT/<prefix>_manifest/ 读取（不走 ossutil）",
  "  --verbose          附逐用户细目表（本地按 ingest_user/user 分组 vs manifest per-user）",
  "  --help             显示本帮助；退出码: 0=PASS 完全一致; 1=WARN 存在差异; 2=错误",
  "环境变量: QODER_AUDIT_BUCKET (默认 " + DEFAULT_BUCKET + "), QODER_AUDIT_PREFIX (默认 " + DEFAULT_PREFIX + "/)",
].join("\n");

function die(msg, code) {
  process.stderr.write(PROG + ": " + msg + "\n");
  process.exit(code === undefined ? 2 : code);
}
const warn = (msg) => process.stderr.write(PROG + ": " + msg + "\n");

// ossutil 封装（child_process.execFile + argv 数组，60s 超时，不经过 shell）
function execFileP(file, args, opts) {
  return new Promise((resolve, reject) =>
    execFile(file, args, opts, (err, stdout, stderr) => (err ? reject(Object.assign(err, { stderr: stderr })) : resolve(stdout)))
  );
}

async function ossDownload(bucket, key, destFile) {
  const tmp = destFile + ".part." + process.pid;
  try {
    await execFileP("ossutil", ["cp", "-f", "oss://" + bucket + "/" + key, tmp], EXEC_OPTS);
    fs.renameSync(tmp, destFile);
  } catch (err) {
    try { fs.unlinkSync(tmp); } catch (e) { /* 忽略清理失败 */ }
    if (err.code === "ENOENT") throw new Error(OSSUTIL_INSTALL_HINT);
    throw new Error("ossutil 下载失败: " + (String(err.stderr || "").trim().split("\n")[0] || err.message));
  }
}

function parseArgs(argv) {
  const VALUED = { "--day": "day", "--local-dir": "localDir", "--state": "stateDir", "--manifest": "manifest", "--from-local": "fromLocal" };
  const opts = { day: "", localDir: "", stateDir: "", manifest: "", fromLocal: "", verbose: false, help: false };
  opts.bucket = process.env.QODER_AUDIT_BUCKET || DEFAULT_BUCKET;
  const p = String(process.env.QODER_AUDIT_PREFIX || DEFAULT_PREFIX).replace(/^\/+|\/+$/g, "");
  opts.prefix = p ? p + "/" : "";
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === "--help" || a === "-h") opts.help = true;
    else if (a === "--verbose") opts.verbose = true;
    else if (VALUED[a]) {
      if (i + 1 >= argv.length) throw new Error(a + " 需要一个参数值");
      opts[VALUED[a]] = argv[++i];
    } else throw new Error("未知参数: " + a);
  }
  return opts;
}

// requests_<day>.jsonl 及其 .1/.2 滚动变体（变体序号升序 = 写入时间序）
function findDayFiles(localDir, day) {
  const re = new RegExp("^requests_" + day + "\\.jsonl(?:\\.(\\d+))?$");
  const files = [];
  for (const e of fs.readdirSync(localDir, { withFileTypes: true })) {
    if (!e.isFile()) continue;
    const m = re.exec(e.name);
    if (m) files.push({ name: e.name, path: path.join(localDir, e.name), variant: m[1] === undefined ? -1 : Number(m[1]) });
  }
  return files.sort((a, b) => a.variant - b.variant);
}

// upload-state.json: { files: { "<文件名>": { offset: N, ... } }, ... }（见 plugin/hooks/log-request.js）
function readUploadState(stateDir) {
  const f = path.join(stateDir, "upload-state.json");
  if (!fs.existsSync(f)) return { files: {}, missing: true };
  try {
    const j = JSON.parse(fs.readFileSync(f, "utf8"));
    return { files: j && j.files && typeof j.files === "object" ? j.files : {}, missing: false };
  } catch (err) { return { files: {}, missing: false, corrupt: err.message }; }
}

function countNewlines(buf, end) {
  let n = 0;
  for (let i = 0; i < end; i += 1) if (buf[i] === 0x0a) n += 1; // UTF-8 中 0x0a 不会出现在多字节序列内
  return n;
}

// X = 行数合计；Y = 游标覆盖的完整行数（offset 截断到文件大小，无记录按 0）
function localSide(files, offsets) {
  let x = 0;
  let y = 0;
  const perFile = [];
  for (const f of files) {
    const buf = fs.readFileSync(f.path);
    const rawOff = Number(offsets[f.name] && offsets[f.name].offset) || 0;
    const effOff = Math.max(0, Math.min(rawOff, buf.length));
    const lines = countNewlines(buf, buf.length);
    const curLines = countNewlines(buf, effOff);
    x += lines;
    y += curLines;
    perFile.push({ name: f.name, lines: lines, curLines: curLines, rawOff: rawOff, size: buf.length });
  }
  return { x: x, y: y, perFile: perFile };
}

// 逐用户分组（优先 Server 盖章 ingest_user，缺省回退客户端自报 user）
function localPerUser(files) {
  const map = new Map();
  for (const f of files) {
    for (const line of fs.readFileSync(f.path, "utf8").split("\n")) {
      if (!line.trim()) continue;
      let u = "(unknown)";
      try {
        const o = JSON.parse(line);
        u = o.ingest_user || o.email || u;
      } catch (err) { /* 非法 JSON 行计入 unknown */ }
      map.set(u, (map.get(u) || 0) + 1);
    }
  }
  return map;
}

function parseManifestBuf(buf, label) {
  let json = null;
  try {
    json = JSON.parse(zlib.gunzipSync(buf).toString("utf8"));
  } catch (err) {
    try {
      json = JSON.parse(buf.toString("utf8")); // 兼容未压缩 JSON
    } catch (err2) { return { ok: false, reason: "manifest 解析失败: " + err.message }; }
  }
  return { ok: true, label: label, users: Array.isArray(json.users) ? json.users : [] };
}

async function loadManifest(opts) {
  const tmp = path.join(os.tmpdir(), "oss-reconcile-manifest-" + process.pid + ".json.gz");
  try {
    if (opts.manifest) {
      const m = /^oss:\/\/([^/]+)\/(.+)$/.exec(opts.manifest);
      if (m) {
        await ossDownload(m[1], m[2], tmp);
        return parseManifestBuf(fs.readFileSync(tmp), opts.manifest);
      }
      if (!fs.existsSync(opts.manifest)) return { ok: false, reason: "文件不存在: " + opts.manifest };
      return parseManifestBuf(fs.readFileSync(opts.manifest), opts.manifest);
    }
    const key = opts.prefix + "_manifest/date=" + opts.day + ".json.gz";
    if (opts.fromLocal) {
      const p = path.join(opts.fromLocal, key);
      if (!fs.existsSync(p)) return { ok: false, reason: "本地模式未找到对象: " + p };
      return parseManifestBuf(fs.readFileSync(p), p);
    }
    await ossDownload(opts.bucket, key, tmp);
    return parseManifestBuf(fs.readFileSync(tmp), "oss://" + opts.bucket + "/" + key);
  } catch (err) {
    return { ok: false, reason: err.message };
  } finally {
    try { fs.unlinkSync(tmp); } catch (e) { /* 忽略清理失败 */ }
  }
}

function noManifestHelp(opts) {
  const key = opts.prefix + "_manifest/date=" + opts.day + ".json.gz";
  warn("无法获取 manifest，对账中止。获取方式示例:");
  warn("  ossutil cp oss://" + opts.bucket + "/" + key + " /tmp/");
  warn("  node tools/oss-reconcile.js --day " + opts.day + " --local-dir <目录> --manifest /tmp/date=" + opts.day + ".json.gz");
  warn("或本地桶根模式: node tools/oss-reconcile.js --day " + opts.day + " --local-dir <目录> --from-local <桶根>");
}

async function main() {
  let opts;
  try {
    opts = parseArgs(process.argv.slice(2));
  } catch (err) {
    die(err.message + "（--help 查看用法）");
    return;
  }
  if (opts.help || !opts.day || !opts.localDir) {
    process.stdout.write(USAGE + "\n");
    process.exit(opts.help ? 0 : 2);
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(opts.day)) die("--day 需为 YYYY-MM-DD 格式（收到: '" + opts.day + "'）");
  if (!fs.existsSync(opts.localDir)) die("--local-dir 目录不存在: " + opts.localDir);
  if (!opts.stateDir) opts.stateDir = path.join(opts.localDir, ".request-logger");

  const files = findDayFiles(opts.localDir, opts.day);
  const state = readUploadState(opts.stateDir);
  if (state.missing) warn("未找到 " + path.join(opts.stateDir, "upload-state.json") + "，游标按 0 处理（该机器可能从未进入 cursor 模式或从未成功上传）");
  if (state.corrupt) warn("upload-state.json 解析失败（" + state.corrupt + "），游标按 0 处理");
  const local = localSide(files, state.files);
  const man = await loadManifest(opts);
  if (!man.ok) {
    if (man.reason) warn("原因: " + man.reason);
    noManifestHelp(opts);
    process.exit(2);
  }
  const z = man.users.reduce((s, u) => s + (Number(u.lines_total) || 0), 0);
  const x = local.x;
  const y = local.y;
  const fileDetail = files.length ? "（" + local.perFile.map((f) => f.name + "=" + f.lines).join(" + ") + "）" : "（未找到本地日志文件）";
  const cursorDetail = local.perFile.length ? "（字节游标: " + local.perFile.map((f) => f.name + " " + f.rawOff + "/" + f.size).join(", ") + "）" : "";
  console.log("对账日: " + opts.day);
  console.log("本地行数 X: " + x + fileDetail);
  console.log("已上报游标 Y: " + y + " 行" + cursorDetail);
  console.log("远端记录 Z: " + z + " 行（manifest: " + man.label + ", users=" + man.users.length + "）");
  console.log("");
  let exitCode;
  if (x === z) {
    console.log("判定: PASS — 本地行数与远端记录完全一致 (" + x + " == " + z + ")");
    if (y < x) console.log("附注: 游标 Y 落后本地 X 共 " + (x - y) + " 行（不影响本次判定，但建议检查 upload-state.json 的 offset 写入）");
    exitCode = 0;
  } else if (x > z) {
    console.log("判定: WARN — 本地行数 > 远端记录，缺失 " + (x - z) + " 行");
    console.log("可能原因: 客户端游标未推进（上传中断/Server 不可达）或 Server spool 未上传至 OSS");
    console.log("建议: ① 检查 " + path.join(opts.stateDir, "upload-state.json") + " 的 offset 是否落后于本地文件大小 ② 检查 Server health 与 spool 积压指标（runbook §6）③ 恢复后重跑本命令复核");
    exitCode = 1;
  } else {
    console.log("判定: WARN — 远端记录 > 本地行数，多出 " + (z - x) + " 行（疑似重复）");
    console.log("说明: 客户端重传经 Server 去重后两端应相等；若同一人在多台机器产生日志，远端多于单机本地属正常——此时按机器分别指定 --local-dir 逐台对账（记录本身不再携带机器级字段）");
    if (x === 0) console.log("附注: 本地侧行数为 0，请确认 --local-dir 是否正确");
    exitCode = 1;
  }
  if (opts.verbose) {
    const localMap = localPerUser(files);
    const remoteMap = new Map(man.users.map((u) => [String(u.user || "?"), Number(u.lines_total) || 0]));
    const rows = Array.from(new Set([...localMap.keys(), ...remoteMap.keys()])).sort().map((u) => [u, localMap.get(u) || 0, remoteMap.get(u) || 0]);
    console.log("");
    console.log("逐用户细目（本地分组依据: ingest_user，缺省回退 user 字段）:");
    console.log("用户".padEnd(34) + "本地行数".padStart(10) + "远端行数".padStart(10) + "  差异(本地-远端)");
    for (const r of rows) console.log(r[0].padEnd(34) + String(r[1]).padStart(8) + String(r[2]).padStart(10) + "  " + (r[1] - r[2]));
  }
  process.exit(exitCode);
}

main().catch((err) => die(err && err.message ? err.message : String(err)));
