#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# configure-oss-env.sh —— 从 ECS 实例元数据读取 RAM 角色，一键生成/更新 .env
#
# 免 AK/SK：写入 OSS_CREDENTIAL_MODE=instance-profile，STS 临时凭证由 SDK
# 从元数据服务（100.100.100.200）自动获取与轮转，无需任何静态密钥。
#
# 用法（在 server/ 目录下执行）：
#   ./configure-oss-env.sh                        # 默认 bucket=sig-zhongtai + 内网 endpoint
#   ./configure-oss-env.sh <bucket> [endpoint] [role]
#   ./configure-oss-env.sh my-bucket https://oss-cn-beijing-internal.aliyuncs.com my-role
#
# 幂等：重复执行只更新受管键（见下方 set_kv 清单），不触碰 .env 中
# 其他手工配置（如 OSS_KMS_KEY_ID、AUDIT_*）。
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

BUCKET="${1:-sig-zhongtai}"
ENDPOINT="${2:-https://oss-cn-beijing-internal.aliyuncs.com}"
META_ROLE_URL="http://100.100.100.200/latest/meta-data/ram/security-credentials/"

# ── 1) 解析实例 RAM 角色名（参数指定优先，否则查元数据，与 SkillHub 同源）──
role="${3:-}"
if [ -z "$role" ]; then
  role="$(curl -fsS --connect-timeout 3 "$META_ROLE_URL" | head -n1 | tr -d '[:space:]' || true)"
fi
if [ -z "$role" ]; then
  echo "✗ 无法读取实例 RAM 角色名（元数据服务不可达或实例未绑定角色）" >&2
  echo "  处理：ECS 控制台 → 实例详情 → 绑定 RAM 角色后重试；" >&2
  echo "        或手工指定：./configure-oss-env.sh <bucket> <endpoint> <role>" >&2
  exit 1
fi
echo "✓ 实例 RAM 角色：$role"

# ── 2) 幂等写入 .env：先删除同键行（含注释行）再追加，末尾值为唯一生效值 ──
cd "$(dirname "$0")"
[ -f .env ] || cp .env.example .env
touch .env

set_kv() {
  local key="$1" value="$2"
  # POSIX 兼容（BSD/GNU sed 均可用）：删除同键行（含注释行）再追加
  sed -i.bak "/^[[:space:]]*#*[[:space:]]*${key}=/d" .env && rm -f .env.bak
  printf '%s=%s\n' "$key" "$value" >> .env
}

set_kv OSS_BUCKET "$BUCKET"
set_kv OSS_ENDPOINT "$ENDPOINT"
set_kv OSS_CREDENTIAL_MODE instance-profile
set_kv OSS_INSTANCE_ROLE_NAME "$role"
set_kv OSS_AK_ID ""
set_kv OSS_AK_SECRET ""
set_kv OSS_STS_TOKEN ""
# 共享 bucket 的加密策略与 bucket owner 确认后再改 kms（届时必须配 OSS_KMS_KEY_ID）
set_kv OSS_ENCRYPTION none

chmod 600 .env

echo "✓ 已写入 .env（权限 600）："
grep -E '^OSS_(BUCKET|ENDPOINT|CREDENTIAL_MODE|INSTANCE_ROLE_NAME|ENCRYPTION)=' .env | sed 's/^/    /' || true

echo
echo "下一步："
echo "  1) 确认角色 $role 的策略含 sig-zhongtai/logs/qoder/* 的 PutObject/ListObjects/AbortMultipartUpload"
echo "  2) 确认 ./config/api-keys.yml 已放置（Key 注册表，缺失则 /api/logs 全部 401）"
echo "  3) docker compose up -d && curl -s http://127.0.0.1:8080/api/health"
echo "  4) docker compose logs qoder-log-server | grep -i instance-profile   # 应看到 STS 凭证拉取成功"
