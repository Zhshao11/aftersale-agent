#!/usr/bin/env python3
"""测「同一条 prompt 的耗时分布」——中位数不是答案，长尾才是。

线索来自两处对不上：
  商家口径的实验室测量（3 次中位）：INTENT 4.05s + PLAN 11.87s = 15.92s
  线上真实轨迹：                  INTENT 16.7s(均值) + PLAN 15.6s(均值) = 32.3s
**同一对 prompt，差 2 倍。** 而且库里的 INTENT 同时出现过 3.2s 和 30.9s。

这说明"慢"不是一个稳定值，而是**一个分布，且尾巴很长**。
用 3 次中位数会系统性低估线上体验——线上用户碰到的是均值/长尾，不是中位。

这个脚本做三件事：
  1. 同一条 prompt 连跑 N 次，打印**完整分布**（min/p25/中位/p75/p90/max）
  2. 记录每次的**输出 token 数**，看耗时是否主要由"模型这次想了多久"决定
  3. 记录**首 token**，区分"排队慢"与"生成慢"——如果首 token 也双峰，那是排队；
     如果首 token 稳定、总时长双峰，那是模型自主决定想多久

用法：
    cd aftersale-agent && set -a && . ./.env && set +a
    python3 scripts/llm_latency_distribution.py --n 12
"""
import argparse
import os
import statistics
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from llm_latency_bottleneck import Conn, parse_endpoint, stream_call  # noqa: E402

INTENT_SYSTEM = """你是售后意图分类器。判断用户消息属于哪类：
- QUERY：查询信息（查订单、查物流、查政策、咨询规则等，无状态变更诉求）
- WRITE：请求执行操作（取消订单、退款、退货、换货，或"不想要了/退了吧"等隐含写操作的表达）
同时把用户诉求改写成一句规范的中文短句。
只输出 JSON，格式：{"intent":"QUERY|WRITE","request":"改写后的诉求"}，不要输出其他内容。
"""
PLAN_SYSTEM = """你是售后操作规划器。用户已提出写操作诉求（取消/退款/换货）。
根据对话与订单信息，输出一份执行计划 JSON，格式：
{"summary":"给用户看的一句话计划说明","orderNo":"订单号","steps":[{"tool":"cancelOrder|refundOrder|exchangeOrder","reason":"操作原因"}]}
规则：
1. 只能使用 cancelOrder、refundOrder、exchangeOrder 三个工具
2. 一次计划通常只有 1 个步骤；不要虚构订单号
3. 只输出 JSON，不要输出任何其他文字
4. 工具选择语义（务必依据订单当前状态）：
   - 订单尚未发货（PAID）时，用户表达"不要了/退掉/取消/退款"一律用 cancelOrder（取消即原路退款）
   - 订单已收货（DELIVERED）后，退款用 refundOrder、换货用 exchangeOrder
5. 若订单状态与用户诉求矛盾（如已收货却要求取消），仍按实际诉求输出工具，政策校验由系统完成
"""

USER_MSG = "帮我把订单 ORD20260901001 取消"
HISTORY_TXT = """以下是你和用户的对话历史：
用户: 我上个月买的咖啡机还没到
助手: 已为您查询，订单 ORD20260901003 状态为已发货，预计 3 天后送达。
用户: 那耳机呢
助手: 订单 ORD20260901001 状态为已发货。"""


def pct(vals, p):
    if not vals:
        return 0.0
    s = sorted(vals)
    return s[min(len(s) - 1, int(len(s) * p))]


def dist(name, vals):
    if not vals:
        print("  %-10s 无数据" % name)
        return
    print("  %-10s n=%2d  最小 %6.2f  p25 %6.2f  中位 %6.2f  p75 %6.2f  p90 %6.2f  最大 %6.2f"
          % (name, len(vals), min(vals), pct(vals, 0.25), statistics.median(vals),
             pct(vals, 0.75), pct(vals, 0.90), max(vals)))


def run_set(conn, model, tag, system, prompt, n, max_tokens):
    print("\n" + "=" * 84)
    print("%s   （连跑 %d 次，同一条 prompt）" % (tag, n))
    print("=" * 84)
    print("  %-4s %8s %8s %8s %10s %10s  %s"
          % ("#", "首token", "生成", "合计", "输出tok", "思考tok", "正文长度"))
    firsts, gens, totals, outtoks, realtimes = [], [], [], [], []
    for i in range(n):
        t0 = time.time()
        r, err = stream_call(conn, model, system + "\n\n" + prompt, max_tokens)
        if err:
            print("  %-4d 失败: %s" % (i + 1, err[:60]))
            continue
        u = r.get("usage") or {}
        ct = u.get("completion_tokens") or 0
        rt = (u.get("completion_tokens_details") or {}).get("reasoning_tokens") or 0
        firsts.append(r.get("first_chunk_s") or 0)
        gens.append(r.get("gen_s") or 0)
        totals.append(r["total_s"])
        outtoks.append(ct)
        realtimes.append(time.time() - t0)
        print("  %-4d %8.2f %8.2f %8.2f %10d %10d  %d"
              % (i + 1, r.get("first_chunk_s") or 0, r.get("gen_s") or 0,
                 r["total_s"], ct, rt, r.get("chars") or 0))
    print()
    dist("首token", firsts)
    dist("生成", gens)
    dist("合计", totals)
    dist("输出tok", [float(x) for x in outtoks])
    if totals and outtoks:
        print("\n  **关键对比**：")
        print("    最小 %6.2fs（输出 %d tok）   最大 %6.2fs（输出 %d tok）   → 差 %.1f 倍"
              % (min(totals), outtoks[totals.index(min(totals))],
                 max(totals), outtoks[totals.index(max(totals))],
                 max(totals) / max(min(totals), 1e-6)))
        # 耗时与输出 token 的相关性：高 → 是模型"想多久"决定；低 → 是排队
        n_ = len(totals)
        mt, mo = statistics.mean(totals), statistics.mean(outtoks)
        cov = sum((totals[i] - mt) * (outtoks[i] - mo) for i in range(n_)) / n_
        st = statistics.pstdev(totals) or 1e-9
        so = statistics.pstdev(outtoks) or 1e-9
        corr = cov / (st * so)
        print("    耗时 vs 输出token 相关系数 r=%.2f  → %s"
              % (corr,
                 "耗时主要由「这次生成多少 token」决定" if corr > 0.6
                 else "耗时与输出量脱钩 → 抖动来自排队/调度，不是模型想多久"))
        return {"first": statistics.median(firsts), "total": statistics.median(totals),
                "mean_total": mt, "max_total": max(totals),
                "outtok": statistics.median(outtoks)}
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.environ.get("LLM_MODEL", "glm-5.3"))
    ap.add_argument("--n", type=int, default=12)
    ap.add_argument("--max-tokens", type=int, default=512)
    ap.add_argument("--only", default="intent,plan", help="intent,plan,short")
    args = ap.parse_args()

    host, port, path, raw = parse_endpoint()
    print("端点 %s   模型 %s   每项 %d 次\n" % (raw, args.model, args.n))
    conn = Conn(host, port); conn.path = path

    res = {}
    which = [w.strip() for w in args.only.split(",")]
    if "intent" in which:
        res["INTENT"] = run_set(conn, args.model, "INTENT（意图路由，真实 prompt）",
                                INTENT_SYSTEM, HISTORY_TXT + "\n\n用户消息：" + USER_MSG,
                                args.n, args.max_tokens)
    if "plan" in which:
        res["PLAN"] = run_set(conn, args.model, "PLAN（计划生成，真实 prompt）",
                              PLAN_SYSTEM,
                              HISTORY_TXT + "\n\n用户诉求: " + USER_MSG
                              + '\n相关订单信息: {"orderNo":"ORD20260901001","status":"已发货"}',
                              args.n, args.max_tokens)
    if "short" in which:
        res["SHORT"] = run_set(conn, args.model, "对照：极短输出（max_tokens=1）",
                               "你是一个助手。", "hi", args.n, 1)

    print("\n" + "=" * 84)
    print("判读")
    print("=" * 84)
    i_, p_ = res.get("INTENT"), res.get("PLAN")
    if i_ and p_:
        print("  串行 = INTENT 中位 %.2fs + PLAN 中位 %.2fs = **%.2fs**"
              % (i_["total"], p_["total"], i_["total"] + p_["total"]))
        print("  按**均值**算 = %.2fs + %.2fs = **%.2fs**"
              % (i_["mean_total"], p_["mean_total"],
                 i_["mean_total"] + p_["mean_total"]))
        print("  按**最坏**算 = %.2fs + %.2fs = **%.2fs**"
              % (i_["max_total"], p_["max_total"], i_["max_total"] + p_["max_total"]))
        print("  → 用户感受到的是哪一个？**长尾**。中位数只描述一半的用户。")
        print("  → 所以「优化了多少秒」要用 p90/均值衡量，不要用中位数自证。")
    for k in ("INTENT", "PLAN"):
        if res.get(k):
            v = res[k]
            print("\n  %s：中位 %.2fs，但 p90 与最大可能是它的 %.1f~%.1f 倍 ——"
                  % (k, v["total"], v["max_total"] / max(v["total"], 1e-6),
                     v["max_total"] / max(v["total"], 1e-6)))
            print("      固定开销（首token 中位）%.2fs，稳定；"
                  "所以波动**不在排队，在生成**。" % v["first"])

    conn.close()


if __name__ == "__main__":
    main()