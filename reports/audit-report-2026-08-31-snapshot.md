# Qoder 操作日志审计报告（真实日志快照）

> 数据来源：`tools/audit-report.js --dir ~/.qoder/logs --days 7`，运行于 2026-08-31 21:32（本地）。
> 该时刻 `~/.qoder/logs/requests_2026-08-31.jsonl` 有 7 条记录；文件在 21:34 已被外部删除，
> 因此本快照是当前唯一的真实日志证据。正文为工具原始输出，未手工修改。

---

# Qoder 操作日志审计报告

- 生成时间：2026-08-31T13:32:10.314Z
- 日志目录：/Users/happyelements/.qoder/logs
- 覆盖日期：2026-08-31, 2026-08-30, 2026-08-29, 2026-08-28, 2026-08-27, 2026-08-26, 2026-08-25
- 记录数：7（解析失败 0 行）

## 1. 采集健康度

| 结论 | 级别 | 说明 |
| --- | --- | --- |
| ❌ | ERROR | 0 条工具级记录：PreToolUse / PostToolUse 未注册，或插件是在会话启动后才安装的（hooks 在会话启动时加载，需重启会话）。 |
| ⚠️ | WARN | 0 条 LLM_USAGE 记录：Credits / token 成本不可见，通常是 transcript 未被采集（QODER_LOG_INCLUDE_TRANSCRIPT=0）或使用了旧版采集脚本。 |
| ⚠️ | WARN | 存在无 log_schema 的旧版记录：由独立脚本 ~/.qoder/hooks/request-logger 产生，建议下线，避免与插件双写。 |
| ⚠️ | WARN | 7 条记录含 _raw 原始 payload 落盘（QODER_LOG_RAW=1）：敏感面最大，导出或共享日志前请先设置 QODER_LOG_RAW=0。 |

## 2. 每日概览

| 日期 | 记录 | 会话 | 工具级记录 | 模型调用记录 | Credits |
| --- | --- | --- | --- | --- | --- |
| 2026-08-31 | 7 | 1 | 0 | 0 | 0.000 |

## 3. 会话明细

| 会话 | 用户 | 仓库/分支 | 起止 | 提示 | 工具调用 | 失败 | 模型轮次 | Credits | In/Out tokens | 峰值上下文 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| faabe6e9-fe42… | happyelements | /Users/happyelements/Documents/Qoder/202… | 366s | 3 | 0 | 0 | 0 | 0.000 | 0 / 0 | - |

## 4. 工具使用分布

_无工具级记录，说明 PreToolUse / PostToolUse 未生效。_

## 5. 涉及文件

_未从工具入参中解析到文件路径。_

## 6. 失败调用

_窗口内没有 PostToolUseFailure 记录。_

## 7. 人员与仓库归因

| 用户 | 会话 | Credits |
| --- | --- | --- |
| happyelements | 1 | 0.000 |

---

## 结论与后续动作

1. **插件版采集器当时完全没产出**：这 7 条记录全部来自 `~/.qoder/hooks/request-logger/log-request.js`
   （旧版独立脚本，字段扁平、无 `log_schema`、无工具级事件、无成本）。插件版采集器在
   `resolveUser → gitEmail` 处抛 `ReferenceError: gitEmailCache is not defined`，每个事件都被静默丢弃，
   已由 `tools/verify-collector.sh` 复现并验证修复。
2. **重启 Qoder 会话才会加载插件 hooks**：本工作区当前会话是在插件安装（21:18）之前启动的，
   所以修复后的采集器没有在本会话内产生记录。新开会话后用下面命令确认：
   `node tools/audit-report.js --dir ~/.qoder/logs --days 1`
3. **下线旧脚本**：`~/.qoder/hooks/request-logger/` 与插件双写、且保真度更低，建议移除。
4. **收敛敏感面**：对外分享日志前先设 `QODER_LOG_RAW=0`（`_raw` 是完整原始 payload）。
