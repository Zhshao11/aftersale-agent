#!/usr/bin/env bash
# 一键跑三配置消融评测（V0 / V1 / V2），统一同一份 43 用例口径。
#
# 为什么需要脚本：三个配置**不是** harness 侧切换，而是应用启动参数，
# 所以每个配置都要重启应用。手动做容易漏，且漏了不会报错——只是跑出一个错的数。
#
#   V2 完整版  ：默认（Plan-and-Execute + Plan 级确认 + 大额二次确认）
#   V1        ：--aftersale.agent.disable-confirm-gate=true
#   V0 基线    ：--aftersale.agent.react-baseline-mode=true
#
# 另外三个坑（本机实测踩过，别省）：
#   1. `.env` 不会被 Spring Boot 读取，必须手动 source，否则 LLM_API_KEY 为空；
#   2. 沙箱会注入 SERVER__PORT，会让应用绑到一个随机端口，必须显式 --server.port 覆盖；
#   3. 每个配置切换前必须等端口真正释放，否则下一个实例启动失败（而 harness 只会报"应用未就绪"）。
set -euo pipefail

cd "$(dirname "$0")/.."
set -a; source .env; set +a

# 工具路径：优先用环境变量覆盖，否则从 PATH 里找；不写死本机绝对路径。
MVN="${MVN:-$(command -v mvn || true)}"
PY="${PY:-$(command -v python3 || true)}"
if [ -z "${MVN}" ] || [ ! -x "${MVN}" ]; then
  echo "找不到 mvn：请把 Maven 放进 PATH，或用 MVN=/path/to/mvn 覆盖。" >&2
  exit 1
fi
if [ -z "${PY}" ] || [ ! -x "${PY}" ]; then
  echo "找不到 python3：请把 Python 放进 PATH，或用 PY=/path/to/python3 覆盖。" >&2
  exit 1
fi
PORT=8081
LOG="/tmp/aftersale-ablation.log"

stop_app() {
  local pid
  pid=$(lsof -nP -iTCP:"${PORT}" -sTCP:LISTEN 2>/dev/null | awk 'NR>1{print $2}' | head -1 || true)
  if [ -n "${pid}" ]; then kill "${pid}" 2>/dev/null || true; fi
  local i
  for i in $(seq 1 30); do
    lsof -nP -iTCP:"${PORT}" -sTCP:LISTEN >/dev/null 2>&1 || return 0
    sleep 1
  done
  echo "警告：端口 ${PORT} 未在 30s 内释放" >&2
}

wait_ready() {
  local i
  for i in $(seq 1 60); do
    if curl -s --noproxy '*' -f "http://localhost:${PORT}/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "应用未就绪，见 ${LOG}" >&2
  return 1
}

run_config() {
  local cfg="$1"; shift
  echo "=== ${cfg}  启动参数: ${*:-（默认）} ==="
  stop_app
  # shellcheck disable=SC2086
  nohup "${MVN}" -o spring-boot:run \
    -Dspring-boot.run.arguments="--server.port=${PORT} $*" >>"${LOG}" 2>&1 &
  wait_ready
  "${PY}" eval/run_eval.py --config "${cfg}" --base "http://localhost:${PORT}" \
    --out "eval/report_${cfg}_43cases.json"
  echo
}

: >"${LOG}"
run_config V1 "--aftersale.agent.disable-confirm-gate=true"
run_config V0 "--aftersale.agent.react-baseline-mode=true"
run_config V2 ""

echo "=== 三配置汇总（同一份用例口径）==="
"${PY}" - <<'PY'
import json
order = [("query", "查询"), ("cancel", "取消"), ("refund_exchange", "退换"),
         ("timeout", "故障处理"), ("privilege", "权限拦截")]
rows = {}
for cfg in ("V0", "V1", "V2"):
    try:
        rep = json.load(open(f"eval/report_{cfg}_43cases.json"))
    except FileNotFoundError:
        rows[cfg] = None
        continue
    cases = rep["cases"][0]
    agg = {}
    for c in cases:
        if c.get("pass") is None:
            continue
        d = agg.setdefault(c["category"], [0, 0])
        d[1] += 1
        d[0] += 1 if c["pass"] else 0
    total = [sum(v[0] for v in agg.values()), sum(v[1] for v in agg.values())]
    rows[cfg] = (agg, total)

head = f"{'维度':<10}" + "".join(f"{c:>14}" for c in ("V0", "V1", "V2"))
print(head)
print("-" * len(head))
for key, label in order:
    line = f"{label:<10}"
    for cfg in ("V0", "V1", "V2"):
        r = rows[cfg]
        if r is None or key not in r[0]:
            line += f"{'N/A':>14}"
        else:
            p, t = r[0][key]
            line += f"{f'{p}/{t} ({p / t * 100:.1f}%)':>14}"
    print(line)
print("-" * len(head))
line = f"{'总计':<10}"
for cfg in ("V0", "V1", "V2"):
    r = rows[cfg]
    if r is None:
        line += f"{'N/A':>14}"
    else:
        p, t = r[1]
        line += f"{f'{p}/{t} ({p / t * 100:.1f}%)':>14}"
print(line)
PY
