# OSS 路径规范 —— Qoder 使用审计日志（三方契约）

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.1 |
| 生效日期 | 2026-09-01（v1.1 修订 2026-09-02：归因来源改为记录内 `email`，见 §5；路径结构不变） |
| 契约三方 | ① 日志上报 Server（`server/`）② 审计工具（`tools/oss-audit.js`）③ 员工端采集插件（`plugin/hooks/log-request.js`，经 Server 间接依赖本规范） |
| 变更约束 | 任何一方不得单方面变更路径结构；破坏性变更须升版为 `v2/`（见 §10） |

本规范定义审计日志在公司共享 OSS Bucket 中的**对象键（Object Key）布局**。它是三方之间的硬契约：Server 按本规范写入，审计工具按本规范前缀定位下载，运维按本规范前缀挂生命周期与 RAM 权限。

---

## 1. 设计目标

| 目标 | 说明 |
| --- | --- |
| 确定性归因 | 任意一条记录，仅凭对象键即可回答：**哪天、谁、来自 Qoder 还是 QoderWork** |
| 免索引查询 | 审计员不需要数据库，仅凭前缀 ListObjects + 单对象下载即可完成常见审计问题 |
| 归因口径统一 | 归因以记录内拍平的企业身份 `email` 为准（客户端自报）；共享 API Key 仅做接口鉴权。接受的取舍：内网环境 + Qoder 官方后台用量数据兜底，不防御内网恶意伪造 |
| 多业务共存 | 共享 Bucket 中与其他业务的日志完全隔离，权限与生命周期可按前缀独立管理 |
| 成本可控 | gzip 必开 + 生命周期规则挂前缀 + 低基数字段不进路径 |

---

## 2. 路径总览（规范原文）

```
oss://<公司共享日志Bucket>/logs/qoder/v1/
  date=2026-09-01/                        # 分区第 1 级：北京日期 = 记录 timestamp + 8h 取日
    user=jiahao.li@sigmob.com/            # 分区第 2 级：记录内企业身份 email（小写 + 白名单清洗 [a-z0-9._@-]）
      src=qoder/                          # 分区第 3 级：来源二分（qoder | qoderwork）
        part-143005-a7f3-0001.jsonl.gz    # part-<段关闭时刻HHmmss>-<实例ID 4hex>-<序号>
      src=qoderwork/
        part-...
  _manifest/
    date=2026-09-01.json.gz               # 每日 02:00 生成：per-user 文件清单/行数/credits合计/时间范围
```

> 注：计划原文 `user=` 行中白名单 `[a-z099._@-]` 为笔误，正确字符集为 **`[a-z0-9._@-]`**（小写字母、数字、点、下划线、@、连字符），本规范以修正后为准。

**完整对象键示例**：

```
logs/qoder/v1/date=2026-09-01/user=jiahao.li@sigmob.com/src=qoderwork/part-143005-a7f3-0001.jsonl.gz
```

---

## 3. 前缀三层语义：`logs/` / `qoder/` / `v1/`

| 层级 | 取值 | 语义 | 设计依据 |
| --- | --- | --- | --- |
| 第 1 层 | `logs` | **资源大类**。共享 Bucket 中区分"日志类"与"备份/附件/制品"等其他资源 | 共享 Bucket 由平台团队统一治理，资源大类是权限与生命周期管理的最粗粒度边界 |
| 第 2 层 | `qoder` | **业务名**。本项目（Qoder/QoderWork 使用审计）专属前缀 | 共享 Bucket 多业务共存，业务名隔离是**必须**的：任何其他业务不得读写 `logs/qoder/` 之下；反之本系统也不越界 |
| 第 3 层 | `v1` | **路径规范版本** | 未来若字段语义、分区结构发生破坏性变更，新数据写入 `v2/`，与 `v1/` 并行互不影响；审计工具按版本路由解析规则 |

由此形成的完整业务前缀为 **`logs/qoder/v1/`**（即 Server 配置项 `oss.prefix`，见 `runbook.md` §3）。生命周期规则与 RAM 权限均按该前缀挂载，详见 `runbook.md` §2。

---

## 4. 分区第 1 级：`date=YYYY-MM-DD`（北京日期）

**规则**：`date = 记录 timestamp（UTC）+ 8 小时，再取日期`。

```text
timestamp = "2026-09-01T16:09:12.883Z"   →  +8h = 2026-09-02 00:09:12 (+08:00)  →  date=2026-09-02
timestamp = "2026-09-01T15:59:59.000Z"   →  +8h = 2026-09-01 23:59:59 (+08:00)  →  date=2026-09-01
```

**设计依据**：

| 关注点 | 结论 |
| --- | --- |
| 为什么用北京时间 | 公司办公与审计口径均为北京时间；"某天"的审计问题必须与员工直觉一致（晚上 8 点的加班记录应落在当天） |
| 为什么用记录内 `timestamp` 而非上传时刻 | **确定性**：同一条记录无论何时上传（哪怕 outbox 重试延迟 3 天），永远落在同一分区，重放/对账结果可复现；上传时刻受网络抖动、Server 停机影响，不可作为分区依据 |
| 时区换算实现 | `new Date(timestamp_ms + 8 * 3600 * 1000).toISOString().slice(0, 10)`，纯函数、无时区库依赖 |
| 本地时间保留 | 记录内 `local_time` 字段（如 `"2026-09-01T10:09:12+08:00"`）原样保留，供跨时区出差等场景交叉核对；**分区只看 +8h 规则，不看 `local_time`** |

---

## 5. 分区第 2 级：`user=<公司邮箱>`

**规则**：取记录内拍平的企业身份 `email`（客户端从 payload `extra.user` 拍平，见 §8），统一转小写后按白名单 `[a-z0-9._@-]` 清洗（白名单外字符替换为 `_`）。共享 API Key 仅做接口鉴权、不参与归因（注册表见 `runbook.md` §4）。

**设计依据**：

| 关注点 | 结论 |
| --- | --- |
| 为什么用记录内 `email` | 插件统一强制安装、无法按人分发不同 Key，注册表归因不可行；改为共享 Key 鉴权 + 记录内 `email` 归因后，换机、轮转 Key 均不影响归因连续性 |
| 自报语义 | `email` 为客户端自报（企业身份，采集端已保证存量记录几乎必含，见 §8 归因门控）；内网环境接受伪造风险，真实用量以 Qoder 官方后台数据兜底 |
| Server 盖章字段 | Server 收到记录后附加 `ingest_user`（记录内 `email` 原文，无则空串）与 `ingest_time`（Server 接收时刻）；`ingest_user` 与 `user=` 分区同源，审计报表可直接引用 |
| 缺失兜底 | 记录无 `email` 时 `ingest_user` 为空串，分区归 `user=unknown` |
| 清洗规则 | 邮箱本身即匹配白名单；清洗仅是对异常数据的防御（例如误录入中文备注、空格），保证对象键永远合法 |

> v1.0 → v1.1 变更：`user=` 取值来源由“API Key 注册表 `user_id`”改为“记录内 `email`”。分区结构、值域（公司邮箱）、清洗规则均不变，审计工具与既有查询配方无需改动。

---

## 6. 分区第 3 级：`src=qoder | qoderwork`

**来源二分**，判定规则（Server 端逐条记录执行）：

| 条件（满足任一） | 判定 |
| --- | --- |
| 记录 `session_id` 以 `task-` 开头 | `qoderwork` |
| 记录 `event` 为 `TaskCreated` 或 `TaskCompleted` | `qoderwork` |
| 以上均不满足 | `qoder` |

**设计依据**：

- QoderWork 长任务会话的 `session_id` 带 `task-` 前缀（如 `task-5f024f8b…`）；Qoder IDE 普通会话为 UUID 形式（如 `15bcb426-8673-4338-be1b-0dd0fed0abf2`）。
- `TaskCreated`/`TaskCompleted` 事件只由 QoderWork 触发，覆盖 `session_id` 判定失效的边缘场景。
- 二分而非细分：审计关心的顶层问题是"个人 IDE 使用 vs 托管任务平台使用"，进一步的产品维度（具体任务类型等）低频且可由记录内字段过滤。

---

## 7. part 文件与滚动策略

### 7.1 文件名格式

```
part-<HHmmss>-<实例ID 4hex>-<序号 4位>.jsonl.gz
                  │           │        └── 同实例当日第 N 个段（0001 起，单调递增）
                  │           └── Server 实例 ID 的前 4 位十六进制
                  └── 段关闭时刻（北京时间）
```

示例：`part-143005-a7f3-0001.jsonl.gz` = 实例 `a7f3…` 在 14:30:05 关闭的当日第 1 个段。

**设计依据**：

| 设计点 | 说明 |
| --- | --- |
| 关闭时刻在前 | ls / ListObjects 天然按时间排序，肉眼排查"今天最后一次上传"直接看末尾 |
| 实例 ID 段 | 多 Server 实例并发写同一 `user=/src=` 前缀时**零协调、零冲突**（水平扩展的关键，见 `capacity-planning.md` §8） |
| 序号兜底 | 同一秒内连续关段（极小概率）也不会重名 |
| 客户端记录不感知 | 文件名完全由 Server 决定；客户端只管 POST `/api/logs` |

### 7.2 滚动策略（Server 端，写入 OSS 的段）

| 条件（先到先触发） | 阈值 |
| --- | --- |
| 未压缩累计字节 | **64 MB** |
| 静默时长（该段最后一条记录距现在） | **10 分钟** |

> 注意区分：客户端本地 `requests_YYYY-MM-DD.jsonl` 的滚动阈值是 `QODER_LOG_MAX_FILE_MB`（默认 64MB，见 `plugin/hooks/log-request.js` CONFIG）；本节 64MB/10min 指的是 **Server 聚合 spool 段**的滚动。

### 7.3 压缩

- **gzip 必开**，实测 JSONL 审计记录压缩比 **8~12x**（字段名重复率高）。
- 下载侧解压后得到标准 JSONL，逐行即一条记录，jq 直接消费（§9 配方）。

```bash
# 下载并解压（审计员标准姿势）
ossutil cp -rf oss://<bucket>/logs/qoder/v1/date=2026-09-01/user=jiahao.li@sigmob.com/src=qoder/ .
for f in part-*.jsonl.gz; do gunzip -c "$f"; done > requests_2026-09-01.jsonl
```

> 或直接使用 `tools/oss-audit.js fetch`（见 §9 配方表），它自动完成定位、下载、解压、按天合并为 `requests_<D>.jsonl`。

---

## 8. 记录字段参考（jq 配方用真实字段）

下载解压后的 `requests_<D>.jsonl` 每行一个 JSON 对象，关键字段（与客户端 `plugin/hooks/log-request.js` 采集、Server 盖章字段一致）：

| 字段 | 类型 | 说明 | 示例 |
| --- | --- | --- | --- |
| `log_schema` | string | 客户端 schema 版本 | `"1.1.0"` |
| `record_kind` | string | 记录类别 | `"hook_event"` |
| `type` | string | 记录类型（**jq 过滤首选**） | `"SESSION_START"` / `"USER_REQUEST"` / `"TOOL_REQUEST"` 等 |
| `event` | string | 触发的 hook 事件名 | `"SessionStart"` / `"PreToolUse"` / `"TaskCompleted"` 等 |
| `session_id` | string | 会话 ID；`task-` 前缀 = QoderWork | `"15bcb426-…"` / `"task-5f024f8b…"` |
| `email` | string | 企业邮箱（企业身份拍平到最外层，**归因必备**；见下方说明） | `"jiahao.li@sigmob.com"` |
| `name` | string | 企业姓名（可选，缺失不出现） | `"嘉豪 李"` |
| `org_id` | string | 组织 ID（可选，缺失不出现） | `"019cbcf2-…"` |
| `org_name` | string | 组织名（可选，缺失不出现） | `"sigmob"` |
| `uid` | string | 企业成员 ID（可选，缺失不出现） | `"019efd72-…"` |
| `ingest_user` | string | Server 盖章：记录内 `email` 原文（归因依据，无 email 时为空串） | `"jiahao.li@sigmob.com"` |
| `ingest_time` | string | Server 盖章：接收时刻（UTC ISO8601） | `"2026-09-01T02:09:13.021Z"` |
| `timestamp` | string | 事件时刻（UTC ISO8601，分区依据） | `"2026-09-01T02:09:12.883Z"` |
| `timestamp_ms` | number | 事件时刻（毫秒） | `1788228552883` |
| `local_time` | string | 员工本地时间（原样保留） | `"2026-09-01T10:09:12+08:00"` |
| `prompt` | string | 用户提示词（USER_REQUEST 记录） | `"请用 Bash 工具执行…"` |
| `tool_name` | string | 工具名（TOOL_REQUEST/TOOL_RESPONSE 记录） | `"Bash"` |
| `tool_call_id` | string | 工具调用 ID（请求/响应/失败关联键） | `"call_bc575307793a4b50af43e296"` |
| `tool_input` | string | 工具入参（字符串化 JSON，需 `fromjson` 展开） | `"{\"command\":\"echo …\"}"` |
| `credits` | number | 本次模型调用 Credits（LLM_USAGE 记录） | `0.03` |
| `repo` | string | 仓库（hook payload 上报） | `"jiahao6635/data-platform"` |
| `git_branch` | string | 分支（hook payload 上报） | `"main"` |
| `cwd` | string | 会话工作目录 | `"/Users/…/data-platform"` |

**企业身份与归因门控**：`email` / `name` / `org_id` / `org_name` / `uid` 五个字段由客户端从 payload `extra.user`（含会话缓存回填）拍平到记录最外层，缺失键不出现；值逐值参与脱敏与长度截断（state.json 会话缓存同样只存脱敏后的值）。拿不到任何企业身份的记录在采集端即被丢弃，不落盘、不上传，因此**存量记录必含至少一个身份键**（实际几乎总有 `email`）。旧的机器级字段 `client_id` / `hostname` / `os_user` 与字符串 `user` 字段自 1.1.0 起不再采集；jq 兜底采集器同样只保存携带 `extra.user` 的事件，并以相同的拍平形式输出。

**`type` / `model` 不进路径**：两者均为低基数字段（type 个位数、model 十余个）。高基数字段（date/user/src）进路径用于前缀裁剪，低基数字段下载后 jq 过滤——否则路径树膨胀（×type×model 的笛卡尔积），对象数爆炸且无查询收益。

---

## 9. 查询配方

### 9.1 常见审计问题 → 定位方式

| 审计问题 | 命令 | 定位原理 |
| --- | --- | --- |
| 某人某天全部记录（出报告） | `node tools/oss-audit.js fetch --date 2026-09-01 --user jiahao.li@sigmob.com --report` | 前缀 `logs/qoder/v1/date=2026-09-01/user=jiahao.li@sigmob.com/` 下 ListObjects + 全量下载；`--report` 合并后自动调用 `audit-report.js` 出报告 |
| 某人某天 QoderWork 记录 | `node tools/oss-audit.js fetch --date 2026-09-01 --user jiahao.li@sigmob.com --src qoderwork --report` | 加第 3 级前缀 `src=qoderwork/` |
| 某天全员记录 | `node tools/oss-audit.js fetch --date 2026-09-01` | `date=` 前缀下递归 ListObjects（对象数见 §11 容量预估） |
| 某天全公司 Credits 汇总 | `node tools/oss-audit.js manifest --date 2026-09-01` | **只读 `_manifest/date=2026-09-01.json.gz` 单对象**，per-user credits 已预聚合，无需扫全天数据 |
| 按工具类型过滤 | 先 fetch 再 jq（§9.2 配方 2） | `type`/`tool_name` 是低基数字段，下载后过滤 |
| 谁没上报（coverage） | `node tools/oss-audit.js coverage --date 2026-09-01` | 列出实际出现的 `user=` 分区；差集比对可把仅含 `user_id` 的花名册 YAML 传给 `--registry`（结构同 api-keys.yml；共享 Key 部署下注册表本身不再是花名册） |
| 本地已有日志直接分析 | `node tools/oss-audit.js fetch --from-local --date 2026-09-01`（或直接用 `tools/audit-report.js`，见 §9.3） | 复用本地 `$QODER_LOG_DIR/requests_<D>.jsonl`，跳过下载 |

> 注：`fetch` 默认只下载、解压、合并出 `requests_<D>.jsonl`；**不带 `--report` 不会自动出报告**——需要 Markdown 报告时必须显式加 `--report`（如表中前两行）。

### 9.2 jq 配方（10 条）

输入统一为**下载解压后的 `requests_<D>.jsonl`**（§7.3），全部可直接复制执行。

```bash
# ① 只看工具级请求（全部工具调用流水）
jq -c 'select(.type=="TOOL_REQUEST")' requests_2026-09-01.jsonl

# ② 按工具统计调用次数（降序）
jq -r 'select(.type=="TOOL_REQUEST") | .tool_name' requests_2026-09-01.jsonl | sort | uniq -c | sort -rn

# ③ 只看 QoderWork 记录（task- 会话）
jq -c 'select(.session_id|startswith("task-"))' requests_2026-09-01.jsonl

# ④ 含敏感词的提示词检索（例：密码 / 内网 IP，-i 忽略大小写）
jq -c 'select(.type=="USER_REQUEST") | select(.prompt|test("密码|192\\.168\\.";"i"))' requests_2026-09-01.jsonl

# ⑤ 当日 Credits 合计
jq -s '[.[].credits // 0] | add' requests_2026-09-01.jsonl

# ⑥ 按仓库/分支统计活动量
jq -r '[.repo // "-", .git_branch // "-"] | @tsv' requests_2026-09-01.jsonl | sort | uniq -c | sort -rn

# ⑦ 还原某会话完整时间线（请求 → 工具 → 响应顺序）
jq -c 'select(.session_id=="15bcb426-8673-4338-be1b-0dd0fed0abf2") | {timestamp, event, tool_name}' requests_2026-09-01.jsonl

# ⑧ 提取全部用户提示词（审计员逐条审阅）
jq -r 'select(.type=="USER_REQUEST") | "[\(.timestamp)] \(.prompt)"' requests_2026-09-01.jsonl

# ⑨ 按小时活动分布（用 local_time 看员工本地作息）
jq -r '.local_time[0:13]' requests_2026-09-01.jsonl | sort | uniq -c

# ⑩ 工具调用失败明细（含 Bash 命令展开：tool_input 是字符串化 JSON）
jq -c 'select(.event=="PostToolUseFailure") | {timestamp, tool_name, tool_call_id}' requests_2026-09-01.jsonl
jq -r 'select(.tool_name=="Bash") | .tool_input | fromjson? | .command' requests_2026-09-01.jsonl
```

### 9.3 与现有审计报表工具衔接

`tools/oss-audit.js` 下载解压产物与客户端本地日志格式完全一致（同为 `requests_<D>.jsonl`），因此现有报表工具零改动即可复用：

```bash
# 对 OSS 下载的数据生成 Markdown 审计报告（--dir 指向解压目录）
node tools/audit-report.js --dir ./oss-logs/2026-09-01 --day 2026-09-01

# 近 7 天趋势 + 严格模式（coverage 不达标 exit 1，可挂 CI）
node tools/audit-report.js --dir ./oss-logs --days 7 --strict

# 只看某仓库
node tools/audit-report.js --dir ./oss-logs --day 2026-09-01 --cwd-filter data-platform
```

对账（灰度 G2 专用，"本地行数 == OSS 记录数"）：

```bash
node tools/oss-reconcile.js --day 2026-09-01 \
  --manifest oss://<bucket>/logs/qoder/v1/_manifest/date=2026-09-01.json.gz \
  --local-dir ~/.qoder/logs
```

---

## 10. `_manifest/` 每日清单

| 项目 | 规范 |
| --- | --- |
| 位置 | `logs/qoder/v1/_manifest/date=YYYY-MM-DD.json.gz`（与分区目录平级，**不在** `date=` 目录内） |
| 生成时机 | 每日 **02:00**（北京时间）由 Server 定时任务生成 |
| 覆盖范围 | 该分区日的全部已上传 part 文件（02:00 前的迟到数据进入次日 manifest 修正，见下） |
| 内容（per-user） | 文件清单（对象键列表）、行数合计、credits 合计、时间范围（min/max timestamp） |

**为什么需要 manifest**：审计最高频的问题是"某天全公司花了多少 credits""谁没上报"。没有 manifest 需要递归 ListObjects 数千对象 + 下载解压全天数据；有 manifest 则**单对象 GetObject** 即答（§9.1 配方 4、6）。

**迟到数据**：客户端 outbox 重试最迟可延迟数天（Server 停机场景）。manifest 生成后到达的记录仍写入对应 `date=` 分区（确定性不受影响），并计入**次日** manifest 的 per-user 汇总（Server 在生成 manifest 时对最近 N 天分区做增量修正）。审计员对账以 `oss-reconcile.js` 为准。

---

## 11. 容量预估（对象数与 ListObjects 翻页）

| 项目 | 估算 |
| --- | --- |
| 活跃用户上限 | 500 |
| 每用户每日 src 数 | ≤ 2（qoder + qoderwork） |
| 每 (user, src) 每日 part 数 | ≤ 24（64MB 滚动 + 10 分钟静默滚动，正常负载下远低于此，按上限估） |
| **每日数据对象数** | 500 × 2 × 24 = **24,000 上限**；实际约 **数千对象/天** |
| 每日 manifest 对象数 | 1 |
| ListObjects 翻页 | `maxKeys=1000` 时，全天全公司扫描 **< 10 次翻页**；单用户单天 1 次调用即达 |
| 存储体积 | 见 `capacity-planning.md` §7（gzip 后 1~1.5GB/天） |

结论：对象数规模对 OSS 无任何压力；单用户/单天查询 1~2 次 ListObjects 即完成定位，**免索引设计的性能收益成立**。

---

## 12. 版本演进（v2 规则）

| 规则 | 内容 |
| --- | --- |
| 何时升版 | 分区结构、归因键值域/清洗规则、文件名格式任一破坏性变更（例如增加第 4 级分区）；仅归因取值来源变化（如 v1.1 由注册表改为记录内 `email`）且值域与清洗规则不变时**不升版**，审计工具无需区分处理 |
| 并行期 | `v1/` 与 `v2/` 并存，各自完整独立；生命周期规则按版本前缀分别挂载 |
| 写入路由 | Server 一次只写一个版本（配置项 `oss.prefix` 控制），不跨版本混写 |
| 读取路由 | 审计工具按对象键中的 `v1`/`v2` 自动选择解析规则 |
| v1 退役 | 仅在 v2 数据完整覆盖审计保留期（365 天）之后，随生命周期规则自然过期删除 |
| 非破坏性变更 | 新增记录字段（如 `ingest_user` 盖章）**不升版**——JSONL 按 jq 语义天然向后兼容 |
