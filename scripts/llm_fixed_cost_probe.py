#!/usr/bin/env python3
"""定量「每次调用都躲不掉的那一段」——本地部署能省掉的上界。

前面的实验暴露了一个矛盾：
  长输出场景（max_tokens=400）  glm-5.3 50.9s vs flash 6.6s  → 差 7.7x
  真实意图分类（输出 ~30 tok）  glm-5.3 7.3s  vs flash 6.7s  → 差 1.1x
差别不在模型，在**输出长度**。所以要分开问两件事：

  A. 固定开销：输出压到 1 个 token，剩下多少？这段与模型/输出都无关，
     是"通道税"——网络 + 网关调度 + 排队。**这是本地部署能省掉的全部。**
  B. 边际成本：每多输出一个 token 要多久？乘以真实输出长度就是生成段。

另外测历史消息的影响：真实请求带 6 轮 history，实验室常拿裸 prompt 测，
会低估 prefill。

用法：
    cd aftersale-agent && set -a && . ./.env && set +a
    python3 scripts/llm_fixed_cost_probe.py --repeat 8
"""
import argparse
import os
import statistics
import sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from llm_latency_bottleneck import (  # noqa: E402
    Conn, parse_endpoint, stream_call, LONG, SHORT,
)

HISTORY = """以下是最近对话：
用户：我上个月买的咖啡机还没到
助手：已为您查询，订单 ORD20260901001 状态为已发货，预计 3 天后送达。
用户：那耳机呢
助手：订单 ORD202609160012 状态为待发货。
用户：有没有金额超过 500 的订单
助手：有，ORD20260901003 金额 899 元。
用户：帮我看看退货政策
助手：未拆封商品 7 天无理由退货，已拆封需质检。
用户：那我这个能退吗
助手：需要看具体订单状态，请提供订单号。
用户：ORD20260901001
助手：该订单已发货，退货需先签收再申请。

当前用户说："""


def pct(vals, p):
    s = sorted(vals)
    return s[min(len(s) - 1, int(len(s) * p))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.environ.get("LLM_MODEL", "glm-5.3"))
    ap.add_argument("--repeat", type=int, default=8)
    args = ap.parse_args()

    host, port, path, raw = parse_endpoint()
    print("端点 %s   模型 %s   每组 %d 次\n" % (raw, args.model, args.repeat))

    # ---- A. 固定开销 ----
    print("=" * 78)
    print("A) 固定开销（max_tokens=1，prompt='hi'）—— 通道税")
    print("=" * 78)
    conn = Conn(host, port); conn.path = path
    firsts, totals = [], []
    for i in range(args.repeat):
        r, err = stream_call(conn, args.model, "hi", 1)
        if err:
            print("  第 %d 次失败: %s" % (i + 1, err[:70]))
            continue
        firsts.append(r["first_chunk_s"])
        totals.append(r["total_s"])
    if firsts:
        print("  首token  最小 %.2fs  中位 %.2fs  p90 %.2fs  最大 %.2fs"
              % (min(firsts), statistics.median(firsts), pct(firsts, 0.9), max(firsts)))
        print("  → **最小值才是「真·固定开销」**：中位数里混了排队，"
              "排队是波动的，不是固定的。")
    conn.close()

    # ---- B. 历史消息的影响（prefill 单价）----
    print("\n" + "=" * 78)
    print("B) 历史消息的影响（同样 max_tokens=1，只改 prompt 长度）")
    print("=" * 78)
    for tag, prompt in [("裸问题(8字)", "帮我取消订单"),
                        ("带6轮历史(%d字)" % len(HISTORY + "帮我取消订单"),
                         HISTORY + "帮我取消订单")]:
        conn = Conn(host, port); conn.path = path
        fs = []
        for _ in range(args.repeat):
            r, err = stream_call(conn, args.model, prompt, 1)
            if err:
                continue
            fs.append(r["first_chunk_s"])
        if fs:
            print("  %-22s 首token 最小 %6.2fs  中位 %6.2fs"
                  % (tag, min(fs), statistics.median(fs)))
        conn.close()

    # ---- C. 并发是否互相拖慢 ----
    print("\n" + "=" * 78)
    print("C) 并发 4 路（各 max_tokens=1）—— 通道是排队还是并行")
    print("=" * 78)
    conns = [Conn(host, port) for _ in range(4)]
    for c in conns:
        c.path = path
    with ThreadPoolExecutor(max_workers=4) as pool:
        res = list(pool.map(lambda c: stream_call(c, args.model, "hi", 1), conns))
    ok = [r for r, e in res if r and r.get("first_chunk_s")]
    if ok:
        fs = [r["first_chunk_s"] for r in ok]
        print("  并发 4 路首token：%s" % " ".join("%.2f" % x for x in fs))
        print("  中位 %.2fs（对比 A 组单路中位 %.2fs）"
              % (statistics.median(fs), statistics.median(firsts) if firsts else 0))
        print("  → 并发下**没有明显变慢**说明是并行处理、通道不是全局串行排队；"
              "\n    明显变慢才说明排队。")
    for c in conns:
        c.close()

    # ---- D. 边际成本：输出长度 vs 耗时 ----
    print("\n" + "=" * 78)
    print("D) 边际成本（同一 prompt，改 max_tokens）—— 每个 token 多少钱")
    print("=" * 78)
    conn = Conn(host, port); conn.path = path
    print("  %-14s %10s %10s %10s %12s" % ("max_tokens", "首token", "生成", "合计", "输出tok"))
    for mt in (1, 64, 256, 512):
        r, err = stream_call(conn, args.model, SHORT, mt)
        if err:
            print("  %-14s 失败: %s" % (mt, err[:60]))
            continue
        u = r.get("usage") or {}
        print("  %-14s %10.2f %10.2f %10.2f %12s"
              % (mt, r["first_chunk_s"] or 0, r["gen_s"] or 0, r["total_s"],
                 u.get("completion_tokens")))
    conn.close()
    print("  → 合计随 max_tokens 线性增长的那一段斜率的倒数就是 decode 吞吐；"
          "\n    截距就是固定开销。两者对不上前面的数说明通道在漂移。")


if __name__ == "__main__":
    main()
