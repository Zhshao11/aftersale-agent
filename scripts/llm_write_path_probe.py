#!/usr/bin/env python3
"""写路径延迟归因：区分「模型慢」与「链路结构慢」。

用户的问题：写请求的 Plan-and-Execute 链路是不是很慢？

这个问题有两种完全不同的答案，必须用实验分开：

  A. **链路的锅**：两次串行调用，每次都要重付一遍固定开销（排队+调度）。
     如果是这个 → 合并成一次调用就该省下近一半。
  B. **模型的锅**：每次调用本身就要 15s，因为模型 decode 慢。
     如果是这个 → 合并也只是把 2×15s 变成 1×20s，省不了多少。

判断办法：拿**真实的两个 prompt**（与 IntentRouter.SYSTEM / PlanGenerator.SYSTEM
逐字一致）分别计时，再测一个合并版的 prompt。三者一对比，答案就出来了。

用法：
    cd aftersale-agent && set -a && . ./.env && set +a
    python3 scripts/llm_write_path_probe.py --repeat 3
"""
import argparse
import json
import os
import re
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from llm_latency_bottleneck import Conn, parse_endpoint, stream_call  # noqa: E402

# ==== 逐字对齐 IntentRouter.SYSTEM ====
INTENT_SYSTEM = """你是售后意图分类器。判断用户消息属于哪类：
- QUERY：查询信息（查订单、查物流、查政策、咨询规则等，无状态变更诉求）
- WRITE：请求执行操作（取消订单、退款、退货、换货，或"不想要了/退了吧"等隐含写操作的表达）
同时把用户诉求改写成一句规范的中文短句。
只输出 JSON，格式：{"intent":"QUERY|WRITE","request":"改写后的诉求"}，不要输出其他内容。
"""

# ==== 逐字对齐 PlanGenerator.SYSTEM ====
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

# ⚠️ 保真夹具：逐字对齐 PlanService.singleOrderContext（4 字段，status 用代码）。
# 这里的旧版夹具是我自己编的（中文状态 + 生产里不存在的 statusCode），
# 实测会让思考 token 从 48 涨到 329——即"测得慢"里有一大半是我的夹具造成的。
# 详见 scripts/llm_fixture_fidelity_probe.py。
ORDER_CTX = json.dumps({
    "orderNo": "ORD20260901001",
    "status": "PAID",
    "itemName": "无线蓝牙耳机",
    "amountUsd": 89.99,
}, ensure_ascii=False)

HISTORY = [
    ("USER", "我上个月买的咖啡机还没到"),
    ("ASSISTANT", "已为您查询，订单 ORD20260901003 状态为已发货，预计 3 天后送达。"),
    ("USER", "那耳机呢"),
    ("ASSISTANT", "订单 ORD20260901001 状态为已发货。"),
]

# 合并版：一次调用同时产出 intent 与 plan。
# 这是 A 方案的实验体——测"把两次串行变成一次"到底值多少。
MERGED_SYSTEM = """你是售后助手。一次完成两件事：
(1) 判断用户消息属于 QUERY 还是 WRITE；
(2) 若是 WRITE，直接给出执行计划。
只输出 JSON：{"intent":"QUERY|WRITE","request":"规范化诉求","summary":"计划说明","orderNo":"订单号","steps":[{"tool":"cancelOrder|refundOrder|exchangeOrder","reason":"原因"}]}
规则：
1. 只能使用 cancelOrder、refundOrder、exchangeOrder 三个工具
2. 不要虚构订单号
3. 只输出 JSON，不要输出任何其他文字
4. 订单尚未发货（PAID）时用 cancelOrder；已收货（DELIVERED）后退款用 refundOrder、换货用 exchangeOrder
5. 若订单状态与诉求矛盾，仍按实际诉求输出工具，政策校验由系统完成
"""

JSON_BLOCK = re.compile(r"\{.*\}", re.S)


def parse_json(text):
    m = JSON_BLOCK.search(text or "")
    if not m:
        return None
    try:
        return json.loads(m.group())
    except Exception:
        return None


def timeit(conn, tag, prompt, system, max_tokens, repeat):
    """同一请求跑 repeat 次，返回中位数与正文。"""
    ts, firsts, gens = [], [], []
    text = None
    for _ in range(repeat):
        r, err = stream_call(conn, MODEL, system + "\n" + prompt, max_tokens)
        if err:
            print("    %s 失败: %s" % (tag, err[:70]))
            continue
        ts.append(r["total_s"])
        firsts.append(r.get("first_chunk_s") or 0)
        gens.append(r.get("gen_s") or 0)
        text = r.get("text")
    if not ts:
        return None
    return {"tag": tag, "total": statistics.median(ts),
            "first": statistics.median(firsts), "gen": statistics.median(gens),
            "n": len(ts), "text": text,
            "chars": len(text or "")}


def main():
    global MODEL
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.environ.get("LLM_MODEL", "glm-5.3"))
    ap.add_argument("--repeat", type=int, default=3)
    ap.add_argument("--max-tokens", type=int, default=512)
    args = ap.parse_args()
    MODEL = args.model

    host, port, path, raw = parse_endpoint()
    print("端点 %s   模型 %s   每项 %d 次\n" % (raw, args.model, args.repeat))

    hist = "".join("\n%s: %s" % (r, c) for r, c in HISTORY)
    conn = Conn(host, port); conn.path = path

    print("=" * 80)
    print("A) 现状：两次串行调用（各自独立计时）")
    print("=" * 80)
    r1 = timeit(conn, "INTENT", "%s\n\n用户消息：%s"
                % (hist, USER_MSG), INTENT_SYSTEM, args.max_tokens, args.repeat)
    r2 = timeit(conn, "PLAN", "%s\n\n用户诉求: %s\n相关订单信息: %s"
                % (hist, USER_MSG, ORDER_CTX), PLAN_SYSTEM, args.max_tokens, args.repeat)
    for r in (r1, r2):
        if r:
            print("  %-8s 首token %6.2fs  生成 %6.2fs  合计 %6.2fs  （%d 次中位）"
                  % (r["tag"], r["first"], r["gen"], r["total"], r["n"]))
    if r1 and r2:
        serial = r1["total"] + r2["total"]
        print("  → 串行合计 **%.2fs**（另有工具调用 0.01s，可忽略）" % serial)
        # 正文拿来判"两件事是否都做对了"
        print("  INTENT 输出: %s" % (r1["text"] or "")[-100:].replace("\n", " "))
        print("  PLAN   输出: %s" % (r2["text"] or "")[-140:].replace("\n", " "))

    print("\n" + "=" * 80)
    print("B) 如果合并成一次调用")
    print("=" * 80)
    r3 = timeit(conn, "MERGED", "%s\n\n用户诉求: %s\n相关订单信息: %s"
                % (hist, USER_MSG, ORDER_CTX), MERGED_SYSTEM, args.max_tokens,
                args.repeat)
    if r3:
        print("  %-8s 首token %6.2fs  生成 %6.2fs  合计 %6.2fs  （%d 次中位）"
              % (r3["tag"], r3["first"], r3["gen"], r3["total"], r3["n"]))
        j = parse_json(r3["text"])
        print("  合并输出解析: %s" % ("✅ keys=%s" % list(j.keys()) if j else "❌ 解析失败"))
        print("  原始输出: %s" % (r3["text"] or "")[-160:].replace("\n", " "))

    print("\n" + "=" * 80)
    print("判读")
    print("=" * 80)
    if r1 and r2 and r3:
        serial = r1["total"] + r2["total"]
        save = serial - r3["total"]
        print("  现状串行  %6.2fs" % serial)
        print("  合并一次  %6.2fs   省 %.2fs（%.0f%%）" % (r3["total"], save, save / serial * 100))
        print()
        # 分解：省下来的是"固定开销"还是"真的少干活"
        fixed_each = min(r1["first"], r2["first"])
        print("  两次调用各自的**固定开销**（首token）: %.2fs + %.2fs = %.2fs"
              % (r1["first"], r2["first"], r1["first"] + r2["first"]))
        print("  合并后固定开销只付一次: %.2fs" % r3["first"])
        print("  → 合并省下的 %.2fs 里，有 %.2fs 来自少付一次固定开销"
              % (save, (r1["first"] + r2["first"] - r3["first"])))
        print("  → 剩下的 %.2fs 来自少生成一遍（合并后输出更长但只有一遍）"
              % (save - (r1["first"] + r2["first"] - r3["first"])))
        print()
        # 关键判断：合并后的总时长 vs 单次调用的总时长
        if r3["total"] < max(r1["total"], r2["total"]) * 1.3:
            print("  ✅ 合并后 ≈ 单次调用的量级 → **链路的锅占大头**："
                  "两次串行调用是主要成本来源，合并不增成本。")
        else:
            print("  ⚠️ 合并后仍接近两次之和 → **单次调用本身就贵**："
                  "链路的额外成本有限，真正贵的是每次调用都要 15s+。")
        print()
        print("  对照：单次调用生成段 %.2fs / %.2fs —— 这是模型 decode 的速度，"
              "合并动不了它。" % (r1["gen"], r2["gen"]))

    conn.close()


if __name__ == "__main__":
    main()