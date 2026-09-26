#!/usr/bin/env python3
"""探针夹具保真度实验：我上一轮的 「PLAN 85% 是思考 token」有多少是我自己的架子造成的。

背景 —— 一次必须承认的自我错误
------------------------------
诊断模型为什么慢时，我测出 PLAN 的输出 85% 是 reasoning token（236/278、273/317），
并据此写进了 TECH_JOURNAL 第 16/17 条，结论是「模型忍不住分析」。

但刚才核对生产代码 PlanService.singleOrderContext 时发现，
**我的探针夹具不是生产的那个**：

    生产（PlanService 第 235-239 行，4 个字段）：
        {"orderNo":"...","status":"SHIPPED","itemName":"...","amountUsd":89.99}

    我的探针夹具（7 个字段）：
        {"orderNo":"...","status":"已发货","statusCode":"SHIPPED",
         "productName":"无线降噪耳机","amountCents":59900,
         "createdAt":"...","carrier":"顺丰","trackingNo":"..."}

差异有两处是**致命的**，且都朝"制造思考"的方向偏：

  1. status 写成了中文「已发货」，而 PLAN 提示词规则 4 用的是**代码**
     （PAID / DELIVERED）。于是提示词里那两个 token 在订单上下文里**根本找不到**，
     还有第三条状态 SHIPPED 是规则没提的。模型被迫去猜"已发货该归哪一类"。
  2. 我额外塞了 statusCode:"SHIPPED"——生产中**不存在**这个字段。
     它恰好是全上下文里唯一出现 SHIPPED 的地方。

也就是说：我可能不是在观测"模型的习惯"，而是在观测"一个含糊的提示词会被模型追问"。
这两个结论的可迁移性完全不同——前者是模型的锅，后者是**我的夹具和提示词的锅**。

本实验就是把这两件事分开。方法：同一提示词、同一模型、同一时刻，
**只换订单上下文**（legacy 含糊版 vs real 保真版），看思考 token 怎么变。

另外顺带纠正一个被测对象的事实：
ORD20260901001 在库里是 **PAID**（¥89.99，无线蓝牙耳机），不是 SHIPPED。
所以我的 legacy 夹具连**订单状态都是编的**。

用法：
    cd aftersale-agent && set -a && . ./.env && set +a
    python3 scripts/llm_fixture_fidelity_probe.py --repeat 6
"""
import argparse
import json
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from llm_latency_bottleneck import Conn, parse_endpoint, stream_call  # noqa: E402
from llm_prompt_variant_probe import (  # noqa: E402
    PLAN_SYSTEM, HISTORY, USER_MSG, parse_plan, pct, extract_balanced)

# ---- 保真夹具：逐字对齐 PlanService.singleOrderContext（4 字段，状态用代码）----
# 订单事实取自 DB，不是我编的：ORD20260901001 / PAID / 无线蓝牙耳机 / 89.99
ORDER_CTX_REAL = json.dumps({
    "orderNo": "ORD20260901001",
    "status": "PAID",
    "itemName": "无线蓝牙耳机",
    "amountUsd": 89.99,
}, ensure_ascii=False)

# ---- 旧夹具（保留，作为对照臂）。这就是我上一轮实际用的那个 ----
ORDER_CTX_LEGACY = json.dumps({
    "orderNo": "ORD20260901001", "status": "已发货", "statusCode": "SHIPPED",
    "productName": "无线降噪耳机", "amountCents": 59900,
    "createdAt": "2026-09-01T10:00:00", "carrier": "顺丰", "trackingNo": "SF1234567890",
}, ensure_ascii=False)

# ---- 第三臂：保真夹具 + 把"状态代码只在规则里出现"这件事说清楚 ----
# 如果这一臂思考 token 也降下来，说明根因是"提示词没交代状态取值域"，
# 改提示词即可，不必动架构。
PLAN_SYSTEM_STATED = PLAN_SYSTEM.replace(
    "4. 工具选择语义（务必依据订单当前状态）：",
    "4. 订单状态字段的取值只可能是 PAID（未发货）、SHIPPED（已发货未收货）、"
    "DELIVERED（已收货）三者之一。工具选择语义："
)


def one(conn, system, ctx, max_tokens, model):
    prompt = "%s\n\n用户诉求: %s\n相关订单信息: %s" % (
        "".join("\n%s: %s" % (r, c) for r, c in HISTORY), USER_MSG, ctx)
    r, err = stream_call(conn, model, system + "\n" + prompt, max_tokens)
    if err:
        return {"err": err}
    u = r.get("usage") or {}
    return {"total": r["total_s"], "first": r.get("first_chunk_s") or 0,
            "text": r.get("text") or "",
            "ct": u.get("completion_tokens"),
            "rt": (u.get("completion_tokens_details") or {}).get("reasoning_tokens"),
            "err": None}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.environ.get("LLM_MODEL", "glm-5.3"))
    ap.add_argument("--repeat", type=int, default=6)
    ap.add_argument("--max-tokens", type=int, default=512)
    args = ap.parse_args()

    host, port, path, raw = parse_endpoint()
    conn = Conn(host, port)
    conn.path = path
    print("端点 %s   模型 %s   每臂 %d 次\n" % (raw, args.model, args.repeat))

    arms = [
        ("LEGACY（我上一轮的夹具，status 是中文、多 3 个字段）", PLAN_SYSTEM, ORDER_CTX_LEGACY),
        ("REAL  （生产的 4 字段夹具，status=PAID）",             PLAN_SYSTEM, ORDER_CTX_REAL),
        ("REAL+提示词交代取值域",                                PLAN_SYSTEM_STATED, ORDER_CTX_REAL),
    ]

    got = {}
    for i in range(args.repeat):
        for name, system, ctx in arms:
            r = one(conn, system, ctx, args.max_tokens, args.model)
            got.setdefault(name, []).append(r)
            tag = "✓" if (not r["err"] and parse_plan(r["text"])) else "✗"
            if r["err"]:
                print("  第%d轮 %-42s 失败 %s" % (i + 1, name[:40], r["err"][:40]))
            else:
                print("  第%d轮 %-42s %7.2fs  思考%4s/%4s  %s"
                      % (i + 1, name[:40], r["total"], r["rt"], r["ct"], tag))

    print("\n" + "=" * 96)
    print("%-44s %8s %8s %10s %9s %8s" % ("臂", "中位", "p90", "思考占比", "解析", "n"))
    print("=" * 96)
    stat = {}
    for name, _, _ in arms:
        ok = [r for r in got[name] if not r["err"]]
        tot = [r["total"] for r in ok]
        # 只看跑完的样本算思考占比——被截断的（rt==ct）会把比例虚高
        rt = [r["rt"] for r in ok if r["rt"] is not None]
        ct = [r["ct"] for r in ok if r["ct"] is not None]
        parsed = [r for r in ok if parse_plan(r["text"])]
        frac = ("%.0f%%" % (statistics.median(rt) / statistics.median(ct) * 100)
                if rt and ct and statistics.median(ct) else "—")
        stat[name] = {"med": statistics.median(tot) if tot else 0,
                      "p90": pct(tot, 0.9) if tot else 0,
                      "rt": statistics.median(rt) if rt else None,
                      "ct": statistics.median(ct) if ct else None,
                      "parse": len(parsed), "n": len(ok)}
        print("%-44s %7.2fs %7.2fs %10s %5d/%d %8d"
              % (name[:44], stat[name]["med"], stat[name]["p90"], frac,
                 stat[name]["parse"], stat[name]["n"], len(ok)))

    L = stat[arms[0][0]]
    R = stat[arms[1][0]]
    S = stat[arms[2][0]]
    print("\n" + "=" * 96)
    print("判读")
    print("=" * 96)
    if L["rt"] and R["rt"]:
        print("  思考 token 中位：LEGACY %.0f  →  REAL %.0f   （降 %.0f）"
              % (L["rt"], R["rt"], L["rt"] - R["rt"]))
        if R["rt"] < L["rt"] * 0.6:
            print("  ⚠️ **降幅超过 40%**：说明我上一轮测到的「模型忍不住分析」，")
            print("     有相当一部分是**我的夹具把状态写成中文「已发货」**造成的——")
            print("     提示词规则只认 PAID/DELIVERED，模型拿到一个规则里没有的状态，")
            print("     只能自己推理归类。这是**提示词-夹具不匹配**，不是模型习惯。")
        else:
            print("  → 降幅不大：模型是真的在分析，与夹具字段无关。")
    if L["med"] and R["med"]:
        print("  中位耗时：LEGACY %.2fs → REAL %.2fs（%+.2fs）"
              % (L["med"], R["med"], R["med"] - L["med"]))
    if R["rt"] and S["rt"]:
        print("  提示词交代取值域后：思考 token REAL %.0f → REAL+说明 %.0f（%+.0f）"
              % (R["rt"], S["rt"], S["rt"] - R["rt"]))
    print()
    print("  无论哪个方向成立，有一条已经确定：**上一轮的写路径数字（15.92s / 17.17s）")
    print("  是在一个生产里不存在的提示词上测的**，不能直接引用。要引用必须先重测。")
    print("  这正是「探针夹具必须逐字复刻生产」不可跳过——否则测的是自己的虚构。")


if __name__ == "__main__":
    main()