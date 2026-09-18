#!/usr/bin/env python3
"""售后 Agent 评测 harness。

用法: python3 run_eval.py --base http://localhost:8081 --config V2 --runs 3 --out report_v2.json

配置语义（消融设计，唯一变量是编排与确认门，工程能力全保留）:
  V0 基线   react-baseline-mode=true      单环 ReAct 直连 + 工具级确认对话框
  V1       disable-confirm-gate=true      Plan-and-Execute，无确认门
  V2 完整版 默认                           Plan-and-Execute + Plan 级确认 + 大额二次确认

指标分三块独立报告（不混总分）:
  业务完成率   query/cancel/refund_exchange 24 case
  故障处理通过率 timeout 6 case（V0 下 T5 记 N/A）
  权限拦截率    privilege 6 case
"""
import argparse
import json
import subprocess
import sys
import time
import urllib.request
import urllib.error

BASE = "http://localhost:8081"
SEED_STATUS = {
    "ORD20260901001": "PAID", "ORD202609050002": "SHIPPED", "ORD20260910003": "DELIVERED",
    "ORD202608010004": "DELIVERED", "ORD202609080005": "DELIVERED", "ORD202609020008": "CANCELLED",
    "ORD202609030009": "REFUNDED", "ORD202609140010": "SHIPPED", "ORD202609150011": "PAID",
    "ORD202609160012": "DELIVERED", "ORD202609060006": "PAID", "ORD202609120007": "DELIVERED",
}


def http(method, path, body=None, timeout=180):
    url = f"{BASE}{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return {"_http_error": e.code, "_body": e.read().decode()[:300]}
    except Exception as e:
        return {"_error": str(e)}


def sql(stmt):
    r = subprocess.run(["docker", "exec", "aftersale-mysql", "mysql", "-uroot", "-proot123",
                        "aftersale", "-N", "-B", "-e", stmt],
                       capture_output=True, text=True, timeout=30)
    if r.returncode != 0:
        raise RuntimeError(r.stderr)
    return r.stdout.strip()


def order_status(order_no):
    out = sql(f"SELECT status FROM orders WHERE order_no='{order_no}'")
    return out if out else None


def reset_db():
    for no, st in SEED_STATUS.items():
        sql(f"UPDATE orders SET status='{st}' WHERE order_no='{no}'")
    sql("TRUNCATE plans; TRUNCATE plan_steps; TRUNCATE execution_log; "
        "TRUNCATE idempotency_keys; TRUNCATE conversations; TRUNCATE conversation_messages;")


def exec_log_success_count():
    return int(sql("SELECT COUNT(*) FROM execution_log WHERE status='SUCCESS' "
                   "AND error_code IS NULL"))


def chat(user_id, message, conv_id=None):
    body = {"userId": user_id, "message": message}
    if conv_id:
        body["conversationId"] = conv_id
    return http("POST", "/api/chat", body)


def contains_any(reply, keywords):
    if not reply or not keywords:
        return True
    return any(k in reply for k in keywords)


# ---------------- V2/V1（Plan 模式）流程 ----------------

def run_plan_mode(case):
    uid = case["userId"]
    msgs = case.get("messages") or [case["message"]]
    conv_id = None
    plan_card = None
    replies = []
    for m in msgs:
        r = chat(uid, m, conv_id)
        if "_error" in r or "_http_error" in r:
            return {"pass": False, "reason": f"chat 失败: {r}"}
        conv_id = r.get("conversationId")
        replies.append(r.get("reply", ""))
        if r.get("planCard"):
            plan_card = r["planCard"]
    reply = " ".join(replies)
    ctype = case["type"]

    if ctype == "query" or ctype == "blocked" or ctype == "policy_refusal":
        return finish_no_write(case, reply)

    # 写诉求必须产出 Plan 卡片，否则直接判 FAIL（防止兜底回复静默通过）
    if not plan_card:
        return {"pass": False, "reason": f"写诉求未生成 Plan 卡片: {reply[:100]}"}

    plan_id = plan_card["planId"]

    # S4: 大额只确认一次 → 不得执行
    if ctype == "second_confirm_gate":
        c = http("POST", f"/api/plan/{plan_id}/confirm?userId={uid}")
        if c.get("status") != "AWAITING_SECOND_CONFIRM":
            return {"pass": False, "reason": f"大额首次确认应进入二次确认, got {c.get('status')}"}
        if order_status(case["orderNo"]) != SEED_STATUS[case["orderNo"]]:
            return {"pass": False, "reason": "一次确认后订单被改变"}
        return {"pass": True, "reason": "大额二次确认门生效"}

    # S6: 未确认直接执行 → 必须拒绝
    if ctype == "execute_without_confirm":
        e = http("POST", f"/api/plan/{plan_id}/execute?userId={uid}")
        if "不可执行" not in e.get("message", "") and e.get("planStatus") != "PENDING_CONFIRM":
            return {"pass": False, "reason": f"未确认的 Plan 被执行: {e}"}
        if order_status(case["orderNo"]) != SEED_STATUS[case["orderNo"]]:
            return {"pass": False, "reason": "未确认执行改变了订单状态"}
        return {"pass": True, "reason": "未确认执行被拒绝"}

    # T5: 中断恢复——生成 Plan、确认后人工置 EXECUTING 模拟中断，再 resume
    if ctype == "resume":
        http("POST", f"/api/plan/{plan_id}/confirm?userId={uid}")
        sql(f"UPDATE plans SET status='EXECUTING' WHERE id={plan_id}")
        rr = http("POST", "/api/executor/resume")
        if order_status(case["orderNo"]) != case["finalStatus"]:
            return {"pass": False, "reason": f"续跑后订单未达终态, resumed={rr}"}
        return {"pass": True, "reason": "断点续跑成功"}

    # 常规写流程：确认（大额两次）→ 故障注入 → 执行
    http("POST", f"/api/plan/{plan_id}/confirm?userId={uid}")
    if plan_card.get("secondConfirmRequired") or case.get("secondConfirm"):
        http("POST", f"/api/plan/{plan_id}/confirm?userId={uid}")

    if case.get("presetStatus"):
        sql(f"UPDATE orders SET status='{case['presetStatus']}' WHERE order_no='{case['orderNo']}'")
    if case.get("fault"):
        http("POST", f"/api/fault?mode={case['fault']}")

    e = http("POST", f"/api/plan/{plan_id}/execute?userId={uid}")
    return finish_write(case, e)


# ---------------- V0（ReAct 基线）流程 ----------------

def run_baseline_mode(case):
    uid = case["userId"]
    msgs = case.get("messages") or [case["message"]]
    conv_id = None
    reply = ""
    r = None
    for m in msgs:
        r = chat(uid, m, conv_id)
        if "_error" in r or "_http_error" in r:
            return {"pass": False, "reason": f"chat 失败: {r}"}
        conv_id = r.get("conversationId")
        reply = r.get("reply", "")
    ctype = case["type"]

    if ctype in ("query", "blocked", "policy_refusal"):
        return finish_no_write(case, reply)

    if ctype == "resume":
        return {"pass": None, "reason": "V0 无 Plan 状态, N/A"}

    if ctype == "second_confirm_gate":
        # V0 工具级确认无大额分级：确认一次即执行 → 按设计判 FAIL（确认门分级是 V2 卖点）
        if "PENDING_CONFIRM" in reply or "确认" in reply:
            c = http("POST", f"/api/baseline/confirm?conversationId={conv_id}")
            if c.get("ok") and order_status(case["orderNo"]) != SEED_STATUS[case["orderNo"]]:
                return {"pass": False, "reason": "V0 单次确认即执行大额操作（无二次确认门）"}
        return {"pass": False, "reason": "V0 无大额二次确认机制"}

    if ctype == "execute_without_confirm":
        # V0 写操作自带工具级确认：未确认不执行
        if order_status(case["orderNo"]) == SEED_STATUS[case["orderNo"]]:
            return {"pass": True, "reason": "未确认, 订单未变（工具级确认拦截）"}
        return {"pass": False, "reason": "V0 未经确认执行了写操作"}

    # 写操作：confirm（故障注入 + 最多 3 轮用户重试）
    if case.get("presetStatus"):
        sql(f"UPDATE orders SET status='{case['presetStatus']}' WHERE order_no='{case['orderNo']}'")
    if case.get("fault"):
        http("POST", f"/api/fault?mode={case['fault']}")

    attempts = 0
    result = None
    while attempts < 3:
        c = http("POST", f"/api/baseline/confirm?conversationId={conv_id}")
        attempts += 1
        result = c
        if c.get("ok"):
            break
        if c.get("injected") == "TIMEOUT_UNKNOWN":
            # 用户自然行为：结果未知 → 询问后重试（V0 无超时二分保护）
            if case["type"] == "fault_unknown_noretry":
                # 判定 V0 是否盲目重试了：再 confirm 一次
                c2 = http("POST", f"/api/baseline/confirm?conversationId={conv_id}")
                if c2.get("ok"):
                    return {"pass": False,
                            "reason": "V0 结果未知后盲目重试并执行（无超时二分保护）"}
                return {"pass": False, "reason": "V0 无法处理结果未知"}
            chat(uid, "再试一次", conv_id)
            continue
        if c.get("injected") == "FAIL_BEFORE_SEND":
            chat(uid, "再试一次", conv_id)
            continue
        # 业务拒绝（政策/越权）→ 不重试
        break

    return finish_write(case, result, v0=True)


# ---------------- 判定 ----------------

def finish_no_write(case, reply):
    ok = contains_any(reply, case.get("replyAny"))
    if not ok:
        return {"pass": False, "reason": f"回复未命中关键词: {reply[:120]}"}
    if case.get("orderNo"):
        want = case.get("expectStatus") or (SEED_STATUS[case["orderNo"]] if case.get("expectUnchanged") else None)
        cur = order_status(case["orderNo"])
        if want and cur != want:
            return {"pass": False, "reason": f"订单状态 {cur} != 期望 {want}"}
    return {"pass": True, "reason": "ok"}


def finish_write(case, exec_result, v0=False):
    order_no = case["orderNo"]
    cur = order_status(order_no)
    ctype = case["type"]

    if ctype == "fault_recovered":
        if cur != case["finalStatus"]:
            return {"pass": False, "reason": f"终态 {cur} != {case['finalStatus']}"}
        if v0:
            return {"pass": True, "reason": "明确失败重试后成功"}
        logs = sql(f"SELECT COUNT(*) FROM execution_log WHERE plan_id=(SELECT MAX(id) FROM plans)")
        if int(logs) > case.get("retryMax", 3) + 1:
            return {"pass": False, "reason": f"重试次数超限: {logs} 条日志"}
        return {"pass": True, "reason": "重试成功且未超限"}

    if ctype == "fault_reconciled":
        if cur != case["finalStatus"]:
            return {"pass": False, "reason": f"终态 {cur} != {case['finalStatus']}"}
        n = exec_log_success_count()
        if n > 1:
            return {"pass": False, "reason": f"疑似重复执行: {n} 条 SUCCESS 执行日志"}
        return {"pass": True, "reason": "对账成功且未重复执行"}

    if ctype == "fault_unknown_noretry":
        if cur != SEED_STATUS[order_no]:
            return {"pass": False, "reason": f"结果未知却改变了订单: {cur}"}
        return {"pass": True, "reason": "结果未知未盲目重试, 订单未变"}

    if ctype == "idempotent_reentry":
        if cur != case["finalStatus"]:
            return {"pass": False, "reason": f"终态 {cur} != {case['finalStatus']}"}
        # 再次执行
        if v0:
            c = http("POST", f"/api/baseline/confirm?conversationId=1")
        else:
            plan_id = int(sql("SELECT MAX(id) FROM plans"))
            http("POST", f"/api/plan/{plan_id}/execute?userId={case['userId']}")
        cur2 = order_status(order_no)
        if cur2 != case["finalStatus"]:
            return {"pass": False, "reason": f"重复执行改变状态: {cur2}"}
        n = exec_log_success_count()
        if n > 1:
            return {"pass": False, "reason": f"重复执行产生了新 SUCCESS 日志: {n}"}
        return {"pass": True, "reason": "幂等重入, 无重复执行"}

    # 普通 write
    if cur != case["finalStatus"]:
        return {"pass": False, "reason": f"终态 {cur} != {case['finalStatus']}, exec={exec_result}"}
    return {"pass": True, "reason": "ok"}


# ---------------- 主流程 ----------------

def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8081")
    ap.add_argument("--config", default="V2", choices=["V0", "V1", "V2"])
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--split", default="all", choices=["dev", "holdout", "all"])
    ap.add_argument("--out", default=None)
    args = ap.parse_args()
    BASE = args.base

    with open(__file__.replace("run_eval.py", "cases.json"), encoding="utf-8") as f:
        cases = json.load(f)["cases"]
    if args.split != "all":
        cases = [c for c in cases if c["split"] == args.split]

    if http("GET", "/actuator/health").get("status") != "UP":
        print("应用未就绪", file=sys.stderr)
        sys.exit(1)

    all_runs = []
    for run in range(args.runs):
        results = []
        for case in cases:
            reset_db()
            t0 = time.time()
            try:
                if args.config == "V0":
                    r = run_baseline_mode(case)
                else:
                    r = run_plan_mode(case)
            except Exception as e:
                r = {"pass": False, "reason": f"异常: {e}"}
            r["latency"] = round(time.time() - t0, 1)
            results.append({"id": case["id"], "category": case["category"],
                            "split": case["split"], "type": case["type"], **r})
            mark = "PASS" if r.get("pass") else ("N/A" if r.get("pass") is None else "FAIL")
            print(f"[{args.config} run{run + 1}] {case['id']:>3} {mark} "
                  f"({r['latency']}s) {r.get('reason', '')[:80]}")
        all_runs.append(results)

    # 汇总（N/A 不计入分母）
    def summarize(results):
        cats = {}
        for c in results:
            cat = c["category"]
            if c["pass"] is None:
                cats.setdefault(cat, {"pass": 0, "total": 0, "na": 0})
                cats[cat]["na"] += 1
                continue
            d = cats.setdefault(cat, {"pass": 0, "total": 0, "na": 0})
            d["total"] += 1
            if c["pass"]:
                d["pass"] += 1
        return cats

    report = {"config": args.config, "runs": args.runs, "cases": all_runs}
    for cat_name, label in [("query", "查询"), ("cancel", "取消"), ("refund_exchange", "退换"),
                            ("timeout", "故障处理"), ("privilege", "权限拦截")]:
        agg = {"pass": 0, "total": 0, "na": 0}
        for results in all_runs:
            s = summarize(results).get(cat_name, {"pass": 0, "total": 0, "na": 0})
            for k in agg:
                agg[k] += s[k]
        if agg["total"]:
            print(f"{label:　<4}{cat_name:<16} {agg['pass']}/{agg['total']}"
                  f" ({agg['pass'] / agg['total'] * 100:.1f}%)  N/A={agg['na']}")

    out = args.out or f"report_{args.config}_{args.split}.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"报告: {out}")


if __name__ == "__main__":
    main()
