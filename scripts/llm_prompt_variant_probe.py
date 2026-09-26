#!/usr/bin/env python3
"""提示词变体探针：一次调用里省下的时间，有多少是「纯废话」。

为什么单独做这个实验
--------------------
前面已经把「慢」归因到三层（第 16/17 条）：
  第 1 层 模型：PLAN 的输出里 **85% 是 reasoning token**，而 reasoning 跑得最慢（~9 tok/s）
  第 2 层 链路：INTENT + PLAN 两次串行，固定开销付两遍
  第 3 层 分布：用户撞的是 p90（max 61.78s = 中位的 3.7 倍）

第 2/3 层的解法（合并调用、换模型）都会**改变模型行为**，因而会作废 42/43 的消融基线；
第 1 层里却藏着一个**不改行为**的解法：让模型别写那段分析。

这段分析是**中间产物**——它不进 JSON、不被任何代码读取、对答案零贡献，
但按 token 计费、按秒占用 decode。所以「禁止分析」是纯收益，
前提是它能做到两件事：
  (a) 真的变快（否则模型没听）
  (b) 解析成功率不掉（这才是「保证回答效果」的硬约束）

本探针只回答这两个问题。**它不动生产代码**——先量，再决定要不要改。

对照组设计
----------
两个调用点各测两臂，两臂**只差一句指令**（其余指令逐字相同，避免"顺手改了别处"的混淆）：

  BASELINE : 与 IntentRouter.SYSTEM / PlanGenerator.SYSTEM 逐字一致
  NOWORK   : 同一条 SYSTEM + 一句「禁止输出推理过程，直接给 JSON」

之所以用「追加一句」而不是「重写提示词」，是为了让差异可归因：
如果结果变好，功劳一定在那句话上，不是因为我顺手把提示词润色了一遍。

再解读时注意两件事
------------------
1. **必须交错跑**：本链路的吞吐会随时间漂移（实测同一 prompt 12.2 / 42.2 tok/s，3.5 倍）。
   「上午测基线、下午测变体」这种比法测出来的是时段，不是提示词。所以这里两臂逐轮交替。
2. **看 p90 与中位一起看**：用户抱怨的是尾部。只看中位会得到"优化了"的假象。

用法：
    cd aftersale-agent && set -a && . ./.env && set +a
    python3 scripts/llm_prompt_variant_probe.py --repeat 6
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

# 追加的那一句。刻意写得**具体**：不说"简洁一点"（模型会当成建议），
# 而是明确指定"不要输出分析过程"这个可检查的动作。
NOWORK_LINE = """
重要：请在内部完成判断，**不要输出任何分析、推理或思考过程**，直接输出最终 JSON。
你的回复必须能被我 json.loads() 直接解析。
"""

INTENT_NOWORK = INTENT_SYSTEM + NOWORK_LINE
PLAN_NOWORK = PLAN_SYSTEM + NOWORK_LINE

USER_MSG = "帮我把订单 ORD20260901001 取消"

# ⚠️ 保真夹具：逐字对齐 PlanService.singleOrderContext（第 235-239 行的 4 个字段）。
#
# 我第一版这里的夹具是**我自己编的**，7 个字段、status 写成中文「已发货」、
# 还多塞了一个生产里不存在的 statusCode。后果不是"差一点"，是**结论反了**：
# 提示词规则 4 只认 PAID/DELIVERED 两个代码，模型拿到规则里没有的中文状态
# 和第三个状态 SHIPPED，只能自己推理归类——实测思考 token 从 48 涨到 329（87%），
# 而我当时把这个数读成了"模型忍不住分析"。
# 见 scripts/llm_fixture_fidelity_probe.py 的三臂对照。
#
# 订单事实取自 DB，不是我编的：ORD20260901001 / PAID / 无线蓝牙耳机 / 89.99
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

ALLOWED_TOOLS = {"cancelOrder", "refundOrder", "exchangeOrder"}


def extract_balanced(s):
    """逐字复刻 PlanGenerator.extractBalancedJson。

    为什么不能图省事写 re.compile(r"\\{.*\\}")：
    模型若先解释一句再给 JSON，贪婪匹配会从**第一个 {** 跨到**最后一个 }**，
    把后半段多余内容一起圈进来，json.loads 报 "Extra data"。
    我上一轮就踩过这个坑——把测量工具的 bug 读成了被测对象的 bug，
    于是去修一个不存在的解析失败（详见 TECH_JOURNAL 第 17 条）。
    """
    if not s:
        return ""
    t = re.sub(r"^```(?:json)?|```$", "", s.strip(), flags=re.M).strip()
    start = t.find("{")
    if start < 0:
        return t
    depth, in_str, esc = 0, False, False
    for i in range(start, len(t)):
        c = t[i]
        if esc:
            esc = False
            continue
        if c == "\\":
            esc = True
            continue
        if c == '"':
            in_str = not in_str
            continue
        if in_str:
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return t[start:i + 1]
    return t


def parse_intent(text):
    j = extract_balanced(text)
    try:
        o = json.loads(j)
    except Exception:
        return None
    if o.get("intent") not in ("QUERY", "WRITE"):
        return None
    if not (o.get("request") or "").strip():
        return None
    return o


def parse_plan(text):
    j = extract_balanced(text)
    try:
        o = json.loads(j)
    except Exception:
        return None
    steps = o.get("steps")
    if not isinstance(steps, list) or not steps:
        return None
    if not (o.get("summary") or "").strip():
        return None
    if not (o.get("orderNo") or "").strip():
        return None
    if any(s.get("tool") not in ALLOWED_TOOLS for s in steps):
        return None
    return o


def one_arm(conn, system, prompt, max_tokens):
    r, err = stream_call(conn, MODEL, system + "\n" + prompt, max_tokens)
    if err:
        return {"err": err}
    u = r.get("usage") or {}
    return {
        "total": r["total_s"], "first": r.get("first_chunk_s") or 0,
        "gen": r.get("gen_s") or 0, "text": r.get("text") or "",
        "ct": u.get("completion_tokens"),
        "rt": (u.get("completion_tokens_details") or {}).get("reasoning_tokens"),
        "err": None,
    }


def pct(xs, p):
    """p 分位（线性插值）。样本少时也给出，但会标注 n。"""
    if not xs:
        return 0.0
    s = sorted(xs)
    if len(s) == 1:
        return s[0]
    k = (len(s) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def compare(conn, name, base_sys, nowork_sys, prompt, parser, repeat, max_tokens):
    """交错跑两臂。交错是刻意的——见文件头「必须交错跑」。"""
    print("=" * 84)
    print("%s   （每臂 %d 次，逐轮交替以抵消吞吐漂移）" % (name, repeat))
    print("=" * 84)
    arms = {"BASELINE": [], "NOWORK": []}
    sysmap = {"BASELINE": base_sys, "NOWORK": nowork_sys}
    for i in range(repeat):
        for tag in ("BASELINE", "NOWORK"):   # 交替
            res = one_arm(conn, sysmap[tag], prompt, max_tokens)
            arms[tag].append(res)
            mark = "✓" if (not res["err"] and parser(res["text"])) else "✗"
            if res["err"]:
                print("  第%d轮 %-9s 失败: %s" % (i + 1, tag, res["err"][:60]))
            else:
                print("  第%d轮 %-9s %6.2fs 首token %5.2fs  %s"
                      % (i + 1, tag, res["total"], res["first"], mark))

    out = {}
    for tag in ("BASELINE", "NOWORK"):
        ok = [a for a in arms[tag] if not a["err"]]
        tot = [a["total"] for a in ok]
        fir = [a["first"] for a in ok]
        rts = [a["rt"] for a in ok if a["rt"] is not None]
        parsed = [a for a in ok if parser(a["text"])]
        out[tag] = {
            "n": len(ok), "med": statistics.median(tot) if tot else 0,
            "p90": pct(tot, 0.9) if tot else 0,
            "first_med": statistics.median(fir) if fir else 0,
            "rt_med": statistics.median(rts) if rts else None,
            "parse": len(parsed), "tot_n": len(tot),
        }

    print()
    print("  %-9s %8s %8s %10s %10s %10s" %
          ("臂", "中位", "p90", "首token中位", "思考token", "解析通过"))
    for tag in ("BASELINE", "NOWORK"):
        d = out[tag]
        rt = "—" if d["rt_med"] is None else "%.0f" % d["rt_med"]
        print("  %-9s %7.2fs %7.2fs %9.2fs %10s %7d/%d"
              % (tag, d["med"], d["p90"], d["first_med"], rt, d["parse"], d["tot_n"]))

    b, n = out["BASELINE"], out["NOWORK"]
    if b["med"] and n["med"]:
        dmed = b["med"] - n["med"]
        print("\n  中位差  %.2fs（%.0f%%）   首token差 %.2fs   思考token差 %s"
              % (dmed, dmed / b["med"] * 100, b["first_med"] - n["first_med"],
                 "—" if (b["rt_med"] is None or n["rt_med"] is None)
                 else "%.0f" % (b["rt_med"] - n["rt_med"])))
        if n["parse"] < n["tot_n"]:
            print("  ⚠️ **解析成功率下降**（%d/%d → %d/%d）：这一档不能用，"
                  "提速换错判是负收益。"
                  % (b["parse"], b["tot_n"], n["parse"], n["tot_n"]))
        elif dmed > 0.5:
            print("  ✅ 解析已满（%d/%d），且中位降 %.2fs → 这一档可落地。"
                  % (n["parse"], n["tot_n"], dmed))
        else:
            print("  ⚠️ 没快（中位只差 %.2fs）：模型没听这一句，别改。" % dmed)
    return out


def main():
    global MODEL
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.environ.get("LLM_MODEL", "glm-5.3"))
    ap.add_argument("--repeat", type=int, default=6)
    ap.add_argument("--max-tokens", type=int, default=512,
                    help="两臂同值——比的是提示词，不是预算")
    args = ap.parse_args()
    MODEL = args.model

    host, port, path, raw = parse_endpoint()
    print("端点 %s   模型 %s   每臂 %d 次" % (raw, args.model, args.repeat))
    print("两臂唯一差异：NOWORK 追加一句「不要输出分析过程，直接给 JSON」\n")

    hist = "".join("\n%s: %s" % (r, c) for r, c in HISTORY)
    conn = Conn(host, port)
    conn.path = path

    r_intent = compare(
        conn, "A) 意图路由（IntentRouter）",
        INTENT_SYSTEM, INTENT_NOWORK,
        "%s\n\n用户消息：%s" % (hist, USER_MSG),
        parse_intent, args.repeat, args.max_tokens)

    r_plan = compare(
        conn, "B) 计划生成（PlanGenerator，实测这一段占了写路径的大头）",
        PLAN_SYSTEM, PLAN_NOWORK,
        "%s\n\n用户诉求: %s\n相关订单信息: %s" % (hist, USER_MSG, ORDER_CTX),
        parse_plan, args.repeat, args.max_tokens)

    print("\n" + "=" * 84)
    print("整条写路径合计（中位）")
    print("=" * 84)
    b = r_intent["BASELINE"]["med"] + r_plan["BASELINE"]["med"]
    n = r_intent["NOWORK"]["med"] + r_plan["NOWORK"]["med"]
    print("  现状（两臂基线相加）  %6.2fs" % b)
    print("  只改提示词            %6.2fs   省 %.2fs（%.0f%%）"
          % (n, b - n, (b - n) / b * 100 if b else 0))
    print("  合并调用（上一轮实测）  9.62s   省 40%，但改变模型行为")
    print()
    print("  注意这两个数的**代价不同**：")
    print("    · 改提示词：不改工具集、不改兜底策略、不新增算力 → 消融基线仍然有效")
    print("    · 合并调用：意图与计划在同一次生成里互相影响，")
    print("      且「解析失败默认 QUERY」这个安全兜底要重新设计 → 43 例消融必须重跑")
    print()
    print("  下一步：把可落地的那一档写进 PlanGenerator.SYSTEM，")
    print("  再用 scripts/run_eval.sh 跑一遍 43 例，确认 16 条安全用例全绿。")


if __name__ == "__main__":
    main()