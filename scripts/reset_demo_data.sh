#!/usr/bin/env bash
#
# 把演示 / 评测数据复位到种子状态。
#
# 为什么需要这个脚本：
#   data.sql 用 INSERT IGNORE，只在行不存在时插入。一旦演示或评测把某张订单改成
#   CANCELLED / REFUNDED，再重启应用也不会恢复——种子数据只保证"首次建库正确"，
#   不保证"可以重复演示"。于是会出现"同一份代码、同一份数据文件，但 selftest 从
#   12/12 变成 9/12"这种假回归。
#
# 做法：清空 orders 后重放 data.sql，用同一份种子文件作为唯一事实来源，
#      避免在这里再抄一遍订单状态（抄一遍就多一处会腐化的地方）。
#
# 用法：scripts/reset_demo_data.sh
# 可用环境变量覆盖：MYSQL_CONTAINER / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DATABASE
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-aftersale-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root123}"
DB="${MYSQL_DATABASE:-aftersale}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_SQL="$ROOT_DIR/src/main/resources/data.sql"

if [[ ! -f "$DATA_SQL" ]]; then
  echo "找不到种子数据文件: $DATA_SQL" >&2
  exit 1
fi

# 不带参数时从 stdin 读 SQL；带参数时直接透传（如 mysql_exec -e "SELECT 1"）
mysql_exec() {
  docker exec -i "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -N -B "$DB" "$@" 2>/dev/null
}

echo "==> 清空运行时数据（plans / plan_steps / execution_log / idempotency_keys / agent_trace / conversations / orders）"
mysql_exec <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM plans;
DELETE FROM plan_steps;
DELETE FROM execution_log;
DELETE FROM idempotency_keys;
DELETE FROM agent_trace;
DELETE FROM conversation_messages;
DELETE FROM conversations;
DELETE FROM orders;
SET FOREIGN_KEY_CHECKS = 1;
SQL

echo "==> 重放种子数据 $DATA_SQL"
docker exec -i "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$DB" 2>/dev/null < "$DATA_SQL"

echo "==> 复位后订单状态"
mysql_exec -e "SELECT order_no, user_id, status FROM orders ORDER BY order_no;"

echo "==> 完成。若应用正在运行，其连接池里的数据已是新的，无需重启。"
