#!/usr/bin/env bash
# gen-api-key.sh — 审计上报 API Key 生成助手（与 docs/runbook.md §4.1 的命令约定一致）。
#
# 用法:
#   bash tools/gen-api-key.sh <user_id> [<user_id>...] [--count N]
#
#   <user_id>   公司邮箱（小写，字符白名单 [a-z0-9._@-]，如 jiahao.li@sigmob.com），可多个
#   --count N   每个 user_id 生成 N 把 Key（默认 1，范围 1-100；新旧 Key 并存轮转场景用）
#
# 输出: 明文 Key（qk_ + openssl rand -hex 16）、对应 SHA-256（echo -n | shasum -a 256），
#       以及可直接粘贴进 api-keys.yml 的 keys: 列表片段（display_name 留空待补、enabled: true）。
# 安全: 明文 Key 仅在终端一次性展示，请立即经安全渠道交付（runbook §5 G1）；Server 只存哈希。
#       本脚本 set -euo pipefail，全程不落盘任何文件。
set -euo pipefail

PROG="gen-api-key.sh"

usage() {
  cat >&2 <<'EOF'
用法: bash tools/gen-api-key.sh <user_id> [<user_id>...] [--count N]
  <user_id>    公司邮箱（小写，白名单 [a-z0-9._@-]，如 jiahao.li@sigmob.com）
  --count N    每个 user_id 生成 N 把 Key（默认 1，范围 1-100）
  -h, --help   显示本帮助
示例:
  bash tools/gen-api-key.sh jiahao.li@sigmob.com
  bash tools/gen-api-key.sh a@sigmob.com b@sigmob.com --count 2
EOF
  exit "${1:-2}"
}

die() { echo "$PROG: $*" >&2; exit 1; }

COUNT=1
USERS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --count) [ $# -ge 2 ] || die "--count 需要一个参数值"; COUNT="$2"; shift 2 ;;
    -h|--help) usage 0 ;;
    -*) die "未知参数: $1" ;;
    *) USERS+=("$1"); shift ;;
  esac
done

[ "${#USERS[@]}" -gt 0 ] || usage
[[ "$COUNT" =~ ^[0-9]+$ ]] || die "--count 需为正整数（收到: '$COUNT'）"
{ [ "$COUNT" -ge 1 ] && [ "$COUNT" -le 100 ]; } || die "--count 需在 1-100 范围（收到: '$COUNT'）"
command -v openssl >/dev/null 2>&1 || die "未找到 openssl 命令，请先安装"
command -v shasum >/dev/null 2>&1 || die "未找到 shasum 命令（macOS 自带；Linux 可用 sha256sum 等效替代）"
for u in "${USERS[@]}"; do
  [[ "$u" =~ ^[a-z0-9._-]+@[a-z0-9.-]+$ ]] || die "user_id 需为小写公司邮箱（白名单 [a-z0-9._@-]，收到: '$u'）"
done

idx=0
for user in "${USERS[@]}"; do
  for ((i = 1; i <= COUNT; i += 1)); do
    idx=$((idx + 1))
    key="qk_$(openssl rand -hex 16)"
    sha="$(echo -n "$key" | shasum -a 256 | awk '{print $1}')"
    echo "────────────────────────────────────────────────────────────"
    echo "[$idx] user_id : $user"
    echo "     明文 Key  : $key"
    echo "     SHA-256   : $sha"
    echo "     校验命令  : echo -n '$key' | shasum -a 256"
    echo "     （明文 Key 仅此一次展示，请立即经安全渠道交付；Server 只存上面的哈希）"
    echo "# ↓ 可直接粘贴进 api-keys.yml 的 keys: 列表（display_name 为必填项，分发前请补全）"
    echo "  - user_id: $user"
    echo "    key_sha256: $sha"
    echo '    display_name: ""'
    echo "    enabled: true"
  done
done
echo "────────────────────────────────────────────────────────────"
echo "共生成 $idx 把 Key。粘贴进 api-keys.yml 后按 runbook §4.3 热加载，最迟 5 分钟生效。"
