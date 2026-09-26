#!/usr/bin/env python3
"""真写路径计时：直接 POST /api/chat，读 agent_trace 的 INTENT / PLAN 真实耗时。

为什么必须做这一步
------------------
第 18 条把探针夹具改保真后，PLAN 只剩 7.4s；把调用形状也改成生产形状后，只剩 3.8s。
但**生产自己的 trace 表**里 PLAN 是均值 34.1s、最大 93.4s。
探针与生产差 4~9 倍——说明"探针能复现生产"这件事我一直没验证过。

这个脚本不再猜：直接打真实接口，然后从 `agent_trace` 里读这次的 INTENT/PLAN latency。
这一步同时验证三件事：
  1. 生产是否真的比探针慢（若真慢，探针的一切数字都不能引用）；
  2. 每轮 INTENT 是不是每次都**真的发生**（写路径有两轮模型调用，这是链路结构）；
  3. 各节点的占比，作为"改哪里收益最大"的分母。

用法：
    cd aftersale-agent && python3 scripts/live_write_path_probe.py --rounds 3
"""
import argparse
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8081"
# 代理环境变量会把 localhost 请求也送去代理 —— 必须显式绕过（本机踩过）
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
TOKEN = os.environ.get("AFTERSALE_TOKEN", "dev-user-token")


def post(path, body, timeout=300):
    req = urllib.request.Request(
        BASE + path, data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + TOKEN})
    t0 = time.time()
    try:
        with OPENER.open(req, timeout=timeout) as r:
            return time.time() - t0, json.loads(r.read().decode("utf-8")), None
    except urllib.error.HTTPError as e:
        return time.time() - t0, None, "HTTP %d %s" % (e.code, e.read()[:160])
    except Exception as e:
        return time.time() - t0, None, "%s: %s" % (type(e).__name__, str(e)[:120])


def sql(q):
    import subprocess
    p = subprocess.run(["docker", "exec", "aftersale-mysql", "mysql", "-uroot",
                        "-proot123", "aftersale", "--default-character-set=utf8mb4",
                        "-N", "-B", "-e", q],
                       capture_output=True, text=True)
    return [l.split("\t") for l in p.stdout.strip().split("\n") if l.strip()]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--user", default="U001")
    # 用 PAID 订单（ORD20260901001 是 PAID）走 cancelOrder —— 政策允许，
    # 所以能走到 PLAN 生成而不会在政策校验处提前拒绝。
    ap.add_argument("--msg", default="帮我取消订单 ORD20260901001，我不想要了")
    args = ap.parse_args()

    print("真写路径计时：POST /api/chat  ×%d\n" % args.rounds)
    print("  用户 %s   消息 %s\n" % (args.user, args.msg))

    rows = []
    for i in range(args.rounds):
        # 每轮用一个新会话，避免历史累积改变输入（历史会进 prompt）
        el, resp, err = post("/api/chat", {"userId": args.user, "message": args.msg})
        if err:
            print("  第%d轮 请求失败: %s" % (i + 1, err))
            continue
        # 响应形状：trace 嵌在 trace 对象里（trace.traceId / trace.elapsedMs），
        # 不是顶层。第一版按顶层读，三轮全部 traceId=None 而请求其实是成功的——
        # 又一次"测量工具自己错了"。这里两个来源都取，并对不上时明说。
        tr = (resp or {}).get("trace") or {}
        trace_id = tr.get("traceId") or (resp or {}).get("traceId")
        plan_id = (resp or {}).get("planId")
        status = (resp or {}).get("status") or tr.get("termination")
        intent = (resp or {}).get("intent")
        print("  第%d轮 HTTP 耗时 %.1fs   intent=%s  termination=%s  steps=%s"
              % (i + 1, el, intent, tr.get("termination"), tr.get("steps")))
        print("        traceId=%s  planId=%s  应用自报 elapsedMs=%s  服务端 ct=%s pt=%s"
              % (trace_id, plan_id, tr.get("elapsedMs"),
                 tr.get("completionTokens"), tr.get("promptTokens")))
        if tr.get("elapsedMs") and abs(tr["elapsedMs"] / 1000.0 - el) > 1.5:
            print("        ⚠️ 应用自报 %.1fs 与 HTTP 实测 %.1fs 差 >1.5s —— 说明响应外还有开销"
                  % (tr["elapsedMs"] / 1000.0, el))
        if trace_id:
            steps = sql("SELECT node,status,ROUND(latency_ms/1000,1),"
                        "COALESCE(completion_tokens,-1),COALESCE(prompt_tokens,-1) "
                        "FROM agent_trace WHERE trace_id='%s' ORDER BY id" % trace_id)
            nodes = {}
            for s in steps:
                if len(s) < 3:
                    continue
                node, st, sec = s[0], s[1], s[2]
                print("      %-9s %-12s %8ss  ct=%s pt=%s"
                      % (node, st, sec, s[3] if len(s) > 3 else "-",
                         s[4] if len(s) > 4 else "-"))
                if st == "SUCCESS" and sec not in ("NULL", "0.0"):
                    nodes[node] = nodes.get(node, 0) + float(sec)
            rows.append({"http": el, "nodes": nodes, "trace": trace_id})
        print()

    if not rows:
        sys.exit("没有任何成功轮次")

    print("=" * 78)
    print("按节点汇总（各轮相加；生产写的路径 INTENT 每轮会发生两次）")
    print("=" * 78)
    keys = ["RECEIVED", "INTENT", "TOOL", "PLAN", "MODEL"]
    print("  %-10s %8s %8s %8s" % ("节点", "均值", "最小", "最大"))
    for k in keys:
        vals = [r["nodes"].get(k) for r in rows if r["nodes"].get(k)]
        if not vals:
            continue
        print("  %-10s %7.1fs %7.1fs %7.1fs" % (k, statistics.mean(vals),
                                                min(vals), max(vals)))
    https = [r["http"] for r in rows]
    print("  %-10s %7.1fs %7.1fs %7.1fs" % ("HTTP总", statistics.mean(https),
                                            min(https), max(https)))
    llm = [sum(v for k, v in r["nodes"].items() if k in ("INTENT", "PLAN", "MODEL"))
           for r in rows]
    if llm:
        print("\n  每轮 LLM（INTENT+PLAN+MODEL）合计：均值 %.1fs  最小 %.1fs  最大 %.1fs"
              % (statistics.mean(llm), min(llm), max(llm)))
    tot = sum(sum(r["nodes"].values()) for r in rows)
    lsum = sum(llm)
    if tot:
        print("  → LLM 占全部已落库步骤耗时的 %.0f%%" % (lsum / tot * 100))
    print()
    print("  对照：探针测得 PLAN 中位 3.8~7.4s（保真夹具）。")
    print("  若上表 PLAN 均值远高于此，说明**探针复现不了生产**，")
    print("  则探针上的所有对比（提示词变体、模型替换、合并调用）都不能直接引用。")


if __name__ == "__main__":
    main()