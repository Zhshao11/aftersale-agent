#!/usr/bin/env bash
#
# 崩溃恢复实验：真实 kill -9 打断一个正在执行的计划，重启后验证断点续跑。
#
# 为什么必须用真实的 kill -9，而不是手工把 plan.status 改成 EXECUTING：
#   手工造现场默认了"崩溃后进度还在库里"这个前提——而这恰恰是需要被验证的东西。
#   真实的 SIGKILL 会杀死进程、让未提交的事务被数据库回滚，只有提交过的进度才留得下来。
#   用真实崩溃去撞，才能区分"真的落盘了"和"我以为落盘了"。
#
# 判定标准（这也是本实验的意义）：
#   崩溃后仍应能在库里看到 —— plan=EXECUTING、第 1 步 SUCCESS、execution_log 有记录、幂等键存在。
#   这四条同时成立，才说明崩溃后"知道有事没做完"且"知道做到哪一步了"。
#   如果第 1 步的进度和幂等键随崩溃一起消失，那么续跑无从判断进度，
#   且在真实系统里（写工具调的是支付/物流接口）重试会变成重复退款。
#
# 用法：
#   APP_START_CMD='mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8082' \
#     scripts/crash_recovery_probe.sh
#
# 环境变量：
#   APP_START_CMD   必填，前台启动应用的命令（脚本会自己放到后台并管理其生命周期）
#   APP_PORT        默认 8082
#   HANG_MS         第 2 步的阻塞时长，默认 120000（远大于观察窗口，保证来得及 kill）
#   MYSQL_CONTAINER / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DATABASE  同 reset_demo_data.sh
set -uo pipefail

APP_PORT="${APP_PORT:-8082}"
BASE="http://localhost:${APP_PORT}"
HANG_MS="${HANG_MS:-120000}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-aftersale-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root123}"
DB="${MYSQL_DATABASE:-aftersale}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_FILE="${TMPDIR:-/tmp}/aftersale-crash-probe-${APP_PORT}.log"

ORDER_A="ORD20260901001"   # 第 1 步操作
ORDER_B="ORD202609150011"  # 第 2 步操作（会被 HANG 卡住）

FAILURES=0

step() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
ok()   { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; FAILURES=$((FAILURES + 1)); }
assert_eq() { # assert_eq 实际 期望 描述
  if [[ "$1" == "$2" ]]; then ok "$3 ($1)"; else bad "$3 —— 实际 [$1]，期望 [$2]"; fi
}

sql() {
  docker exec -i "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -N -B "$DB" "$@" 2>/dev/null
}
q() { sql -e "$1"; }

# 本机请求一律绕开 shell 里可能存在的 HTTP_PROXY：
# 否则 curl 会把 localhost 也送去代理，健康检查会被代理的错误页骗过（返回 0 但其实没连上）。
curl_local() { curl --noproxy '*' -s "$@"; }

app_pid() {
  lsof -nP -iTCP:"$APP_PORT" -sTCP:LISTEN 2>/dev/null | tail -1 | awk '{print $2}'
}

start_app() {
  # shellcheck disable=SC2086
  nohup bash -c "$APP_START_CMD" > "$LOG_FILE" 2>&1 &
  for _ in $(seq 1 60); do
    # 判活用 /actuator/info，不用另外两个候选：
    #  - /api/selftest 会真的调用写工具（靠强制回滚兜底），拿它当健康检查会把演示数据改脏；
    #  - /actuator/health 会聚合所有已注册的 HealthIndicator，环境里多一个无关 starter
    #    （比如 classpath 上多出 spring-data-redis 而 Redis 没跑）就会整体 DOWN。
    # -f：HTTP 状态码 >=400 也算失败，避免把代理/错误页当成"就绪"。
    if curl_local -f -m 2 -o /dev/null "${BASE}/actuator/info"; then
      for _ in $(seq 1 10); do [[ -n "$(app_pid)" ]] && return 0; sleep 0.5; done
      return 0
    fi
    sleep 1
  done
  echo "应用启动超时（${APP_PORT}），日志尾部："; tail -20 "$LOG_FILE"; return 1
}

stop_app() {
  local p; p="$(app_pid)"
  [[ -n "$p" ]] && kill -9 "$p" 2>/dev/null
  for _ in $(seq 1 20); do [[ -z "$(app_pid)" ]] && return 0; sleep 0.5; done
  return 0
}

dump_state() {
  printf '    plans.status            = %s\n' "$(q "SELECT status FROM plans WHERE id=$1")"
  printf '    plan_steps (seq:status) = %s\n' "$(q "SELECT GROUP_CONCAT(CONCAT(seq,':',status,'(attempt=',attempt,')') ORDER BY seq) FROM plan_steps WHERE plan_id=$1")"
  printf '    execution_log 行数       = %s\n' "$(q "SELECT COUNT(*) FROM execution_log WHERE plan_id=$1")"
  printf '    idempotency_keys 行数    = %s\n' "$(q "SELECT COUNT(*) FROM idempotency_keys WHERE plan_id=$1")"
  printf '    订单状态                 = %s\n' "$(q "SELECT GROUP_CONCAT(CONCAT(order_no,'=',status)) FROM orders WHERE order_no IN ('$ORDER_A','$ORDER_B')")"
}

if [[ -z "${APP_START_CMD:-}" ]]; then
  echo "缺少 APP_START_CMD：请提供启动应用的命令，例如" >&2
  echo "  APP_START_CMD='mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8082' $0" >&2
  exit 2
fi

step "0. 复位演示数据"
( cd "$ROOT_DIR" && ./scripts/reset_demo_data.sh > /dev/null ) || { echo "复位失败"; exit 1; }
q "SELECT CONCAT(order_no,'=',status) FROM orders WHERE order_no IN ('$ORDER_A','$ORDER_B')" | sed 's/^/    /'

step "1. 启动应用（端口 ${APP_PORT}）"
if [[ -n "$(app_pid)" ]]; then
  echo "  端口 ${APP_PORT} 已被占用（pid $(app_pid)），先停掉以免测到旧代码"
  stop_app
fi
start_app || exit 1
ok "应用已就绪 (pid $(app_pid))，日志 $LOG_FILE"

step "2. 造一个两步已确认计划（不经过 LLM，让实验聚焦在执行器）"
PLAN_ID=$(sql <<SQL
INSERT INTO plans (conversation_id, user_id, order_no, summary, estimated_amount_cents,
                   context_fingerprint, status, second_confirm_required, confirmed_at)
VALUES (1,'U001','$ORDER_A','崩溃恢复实验：取消两笔未发货订单',0,'crash-probe-fp','CONFIRMED',0,NOW());
SET @pid = LAST_INSERT_ID();
INSERT INTO plan_steps (plan_id, seq, tool_name, args_json, risk_level, status, attempt) VALUES
 (@pid,0,'cancelOrder',JSON_OBJECT('orderNo','$ORDER_A','userId','U001','reason','崩溃恢复实验 第1步'),'WRITE','PENDING',0),
 (@pid,1,'cancelOrder',JSON_OBJECT('orderNo','$ORDER_B','userId','U001','reason','崩溃恢复实验 第2步'),'WRITE','PENDING',0);
SELECT @pid;
SQL
)
echo "    plan id = $PLAN_ID"
[[ "$PLAN_ID" =~ ^[0-9]+$ ]] || { echo "造计划失败"; exit 1; }

step "3. 给第 2 步注入 HANG（${HANG_MS}ms），制造『第 1 步已完成、第 2 步进行中』的窗口"
curl_local -m 5 -X POST "${BASE}/api/fault?mode=HANG&planId=${PLAN_ID}&stepSeq=1&hangMs=${HANG_MS}" | sed 's/^/    /'
echo

step "4. 发起执行（后台），然后在执行中 kill -9"
curl_local -m 300 -X POST "${BASE}/api/plan/${PLAN_ID}/execute?userId=U001" > /dev/null 2>&1 &
CURL_JOB=$!
sleep 3

TRX=$(q "SELECT COUNT(*) FROM information_schema.innodb_trx")
echo "    kill 前数据库里未提交的事务数 = $TRX （>0 说明此刻确实有一个执行中的事务）"
echo "    kill 前事务外部可见的进度："
printf '      plans.status=%s  execution_log=%s\n' \
  "$(q "SELECT status FROM plans WHERE id=$PLAN_ID")" \
  "$(q "SELECT COUNT(*) FROM execution_log WHERE plan_id=$PLAN_ID")"

KILLED_PID="$(app_pid)"
if [[ -z "$KILLED_PID" ]]; then
  echo "  找不到端口 ${APP_PORT} 上的进程，实验无法继续" >&2
  exit 1
fi
kill -9 "$KILLED_PID"
wait "$CURL_JOB" 2>/dev/null
sleep 1
ok "已 SIGKILL 进程 ${KILLED_PID}（模拟进程崩溃）"

step "5. 崩溃后的库内现场（这是断点续跑的全部依据）"
dump_state "$PLAN_ID"
POST_PLAN=$(q "SELECT status FROM plans WHERE id=$PLAN_ID")
POST_STEP0=$(q "SELECT status FROM plan_steps WHERE plan_id=$PLAN_ID AND seq=0")
POST_LOGS=$(q "SELECT COUNT(*) FROM execution_log WHERE plan_id=$PLAN_ID")
POST_KEYS=$(q "SELECT COUNT(*) FROM idempotency_keys WHERE plan_id=$PLAN_ID")
POST_ORDER_A=$(q "SELECT status FROM orders WHERE order_no='$ORDER_A'")

assert_eq "$POST_PLAN"   "EXECUTING" "计划状态保留为 EXECUTING（否则 resume 扫不到它）"
assert_eq "$POST_STEP0"  "SUCCESS"   "第 1 步的完成状态已落盘"
assert_eq "$POST_LOGS"   "1"         "第 1 步的执行日志已落盘"
assert_eq "$POST_KEYS"   "1"         "第 1 步的幂等键已落盘（这是防重复执行的关键）"
assert_eq "$POST_ORDER_A" "CANCELLED" "第 1 步的业务副作用已生效"

step "6. 重启应用并续跑"
start_app || exit 1
RESUMED=$(curl_local -m 20 -X POST "${BASE}/api/executor/resume")
echo "    POST /api/executor/resume -> $RESUMED"

step "7. 续跑后的库内现场"
dump_state "$PLAN_ID"
FINAL_PLAN=$(q "SELECT status FROM plans WHERE id=$PLAN_ID")
LOGS_STEP0=$(q "SELECT COUNT(*) FROM execution_log l JOIN plan_steps s ON s.id=l.step_id WHERE l.plan_id=$PLAN_ID AND s.seq=0")
LOGS_STEP1=$(q "SELECT COUNT(*) FROM execution_log l JOIN plan_steps s ON s.id=l.step_id WHERE l.plan_id=$PLAN_ID AND s.seq=1")
FINAL_ORDER_B=$(q "SELECT status FROM orders WHERE order_no='$ORDER_B'")

assert_eq "$RESUMED"     '{"resumed":1}' "续跑扫到并接管了 1 个中断计划"
assert_eq "$FINAL_PLAN"  "COMPLETED"     "计划已跑到完成"
assert_eq "$LOGS_STEP0"  "1"             "第 1 步没有被重复执行（日志仍只有 1 条）"
assert_eq "$LOGS_STEP1"  "1"             "第 2 步被执行了一次"
assert_eq "$FINAL_ORDER_B" "CANCELLED"   "第 2 步的业务副作用已生效"

step "结果"
if [[ "$FAILURES" -eq 0 ]]; then
  printf '  \033[32m全部断言通过：崩溃不丢进度、续跑不重跑已完成步骤。\033[0m\n'
  printf '  应用仍在端口 %s 运行 (pid %s)，演示数据可用 scripts/reset_demo_data.sh 复位。\n' "$APP_PORT" "$(app_pid)"
  exit 0
else
  printf '  \033[31m%d 条断言失败。\033[0m\n' "$FAILURES"
  exit 1
fi
