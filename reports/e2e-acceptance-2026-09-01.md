# Qoder 操作日志审计报告

- 生成时间：2026-09-01T02:12:05.192Z
- 日志目录：/Users/happyelements/.qoder/logs
- 覆盖日期：2026-09-01
- 记录数：216（解析失败 0 行）

## 1. 采集健康度

| 结论 | 级别 | 说明 |
| --- | --- | --- |
| ✅ | OK | 工具级记录 22 条：请求与响应均已入档。 |
| ⚠️ | WARN | 0 条 LLM_USAGE 记录：Credits / token 成本不可见，通常是 transcript 未被采集（QODER_LOG_INCLUDE_TRANSCRIPT=0）或使用了旧版采集脚本。 |
| ⚠️ | WARN | 26 条记录含 _raw 原始 payload 落盘（QODER_LOG_RAW=1）：敏感面最大，导出或共享日志前请先设置 QODER_LOG_RAW=0。 |
| ⚠️ | WARN | 17 条记录命中脱敏（[redacted]）：说明提示词或工具输出里出现过凭据，脱敏已生效，但源头需要治理。 |
| ✅ | OK | 采集链路健康：1 个会话的记录完整。 |

## 2. 每日概览

| 日期 | 记录 | 会话 | 工具级记录 | 模型调用记录 | Credits |
| --- | --- | --- | --- | --- | --- |
| 2026-09-01 | 216 | 1 | 22 | 0 | 0.000 |

## 3. 会话明细

| 会话 | 用户 | 仓库/分支 | 起止 | 提示 | 工具调用 | 失败 | 模型轮次 | Credits | In/Out tokens | 峰值上下文 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| task-5f024f8b… | li15733056635@163.com | jiahao6635/data-platform@main | 98s | 0 | 117 | 0 | 0 | 0.000 | 0 / 0 | - |

## 4. 工具使用分布

| 工具 | 调用次数 |
| --- | --- |
| `Bash` | 89 |
| `Read` | 11 |
| `SearchReplace` | 7 |
| `Write` | 3 |
| `TodoWrite` | 2 |
| `Glob` | 1 |
| `GetProblems` | 1 |
| `DeleteFile` | 1 |
| `UpdateMemory` | 1 |
| `WebFetch` | 1 |

## 5. 涉及文件

| 文件 | 出现次数 |
| --- | --- |
| `/Users/happyelements/workspace/Projects/sigmob/data-platform/qoder-request-logger/hooks/log-request.js` | 7 |
| `/Users/happyelements/workspace/Projects/sigmob/data-platform/qoder-request-logger/tools/audit-report.js` | 4 |
| `/Users/happyelements/workspace/Projects/sigmob/data-platform/qoder-request-logger/hooks/hooks.json` | 2 |
| `/Users/happyelements/workspace/Projects/sigmob/data-platform/qoder-request-logger/tools/verify-collector.sh` | 2 |
| `/Users/happyelements/workspace/Projects/sigmob/data-platform/qoder-request-logger` | 1 |
| `/Users/happyelements/workspace/Projects/sigmob/data-platform/qoder-request-logger/hooks/logger.sh` | 1 |
| `/Users/happyelements/.qoder/plugins/cache/qoder-enterprise-019cbcf2-6c18-7070-b6e4-e81af35ccd1c/qoder-request-…` | 1 |
| `/Users/happyelements/workspace/Projects/sigmob/data-platform/qoder-request-logger/hooks/log-request.sh` | 1 |
| `/Users/happyelements/workspace/Projects/sigmob/data-platform/qoder-request-logger/reports/audit-report-2026-08…` | 1 |
| `/Users/happyelements/workspace/Projects/sigmob/data-platform/qoder-request-logger/reports/audit-report-2026-08…` | 1 |

## 6. 失败调用

_窗口内没有 PostToolUseFailure 记录。_

## 7. 人员与仓库归因

| 用户 | 会话 | Credits |
| --- | --- | --- |
| li15733056635@163.com | 1 | 0.000 |

