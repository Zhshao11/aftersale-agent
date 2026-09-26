#!/usr/bin/env python3
"""验证「限制输出长度能否砍掉思考链」——一个可以立刻落地的优化。

上一步的发现：PLAN 的输出 192~512 tok，其中 **84% 是思考 token**
（427/469、512/512、311/364），真正的 JSON 只有几十个 token。
而且有 3 次恰好撞上 512 上限 —— 说明输出是被我设的上限截断的，
线上不设 max_tokens 时模型想得更久。

如果"思考 token 占大头"成立，那么把 max_tokens 压到只够输出 JSON 的量，
就应该**同时**达到两个效果：
  ① 耗时大幅下降（少生成 80% 的 token）
  ② JSON 仍然完整可解析（因为 JSON 本身很短）

这个实验必须同时验这两件事 —— 只验①会掉进"截断输出导致解析失败"的坑，
那看起来是"快了"，实际是把功能弄坏了。

用法：
    cd aftersale-agent && set -a && . ./.env && set +a
    python3 scripts/llm_cap_thinking_probe.py --repeat 5
"""
import argparse
import json
import os
import re
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from llm_latency_bottleneck import Conn, parse_endpoint, stream_call  # noqa: E402

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

# 命令式地禁止思考——很多推理模型的思考链是"默认愿意想"，把话说死能压一部分
PLAN_SYSTEM_TIGHT = PLAN_SYSTEM + """
6. **直接在 20 个 token 内输出 JSON，不要做任何分析、推理或解释。**
"""

PROMPT = """以下是你和用户的对话历史：
用户: 我上个月买的咖啡机还没到
助手: 已为您查询，订单 ORD20260901003 状态为已发货，预计 3 天后送达。
用户: 那耳机呢
助手: 订单 ORD20260901001 状态为已发货。

用户诉求: 帮我把订单 ORD20260901001 取消
相关订单信息: {"orderNo":"ORD20260901001","status":"已发货","statusCode":"SHIPPED","productName":"无线降噪耳机","amountCents":59900}"""

JSON_ANY = re.compile(r"\{.*\}", re.S)


def extract_balanced(s):
    """与 PlanGenerator.extractBalancedJson 同构：从第一个 { 数括号，配平即返回。

    为什么不能用贪婪正则 `\\{.*\\}`：模型经常先解释一句再输出 JSON，
    或者输出两个 JSON 对象。贪婪正则会从第一个 { 匹配到**最后一个** }，
    把多余内容也圈进去，于是 json.loads 报 "Extra data"。
    **我第一版就是这样，于是把 2 次成功误判成解析失败**——
    测量工具的缺陷会被读成被测代码的缺陷。
    """
    if not s:
        return ""
    start = s.find("{")
    if start < 0:
        return s
    depth, in_str, esc = 0, False, False
    for i in range(start, len(s)):
        c = s[i]
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
                return s[start:i + 1]
    return s


def parse_plan(text):
    """模拟 PlanGenerator.parse：必须能取出 summary / orderNo / steps。"""
    if not text:
        return None, "空输出"
    body = extract_balanced(text)
    if "{" not in body:
        return None, "找不到 JSON"
    try:
        j = json.loads(body)
    except Exception as e:
        return None, "JSON 解析失败: %s" % str(e)[:40]
    if not j.get("summary") or not j.get("orderNo"):
        return None, "缺 summary/orderNo"
    if not isinstance(j.get("steps"), list) or not j["steps"]:
        return None, "steps 为空"
    return j, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.environ.get("LLM_MODEL", "glm-5.3"))
    ap.add_argument("--repeat", type=int, default=5)
    args = ap.parse_args()

    host, port, path, raw = parse_endpoint()
    print("端点 %s   模型 %s   每组 %d 次\n" % (raw, args.model, args.repeat))

    conn = Conn(host, port); conn.path = path

    # (标签, system, max_tokens) —— max_tokens 按 JSON 实际需要量给
    groups = [
        ("现状 max=512（对照）", PLAN_SYSTEM, 512),
        ("收紧 max=160", PLAN_SYSTEM, 160),
        ("收紧 max=96", PLAN_SYSTEM, 96),
        ("收紧 max=64", PLAN_SYSTEM, 64),
        ("提示词禁止思考 max=512", PLAN_SYSTEM_TIGHT, 512),
    ]

    summary = []
    for tag, sysmsg, mt in groups:
        print("=" * 84)
        print("%s" % tag)
        print("=" * 84)
        print("  %-4s %8s %8s %8s %9s %9s  %s"
              % ("#", "首token", "生成", "合计", "输出tok", "思考tok", "解析"))
        totals, outtoks, thinks, oks = [], [], [], 0
        for i in range(args.repeat):
            r, err = stream_call(conn, args.model, sysmsg + "\n\n" + PROMPT, mt)
            if err:
                print("  %-4d 失败: %s" % (i + 1, err[:60]))
                continue
            u = r.get("usage") or {}
            ct = u.get("completion_tokens") or 0
            rt = (u.get("completion_tokens_details") or {}).get("reasoning_tokens") or 0
            j, perr = parse_plan(r.get("text"))
            ok = j is not None
            oks += 1 if ok else 0
            totals.append(r["total_s"])
            outtoks.append(ct)
            thinks.append(rt)
            print("  %-4d %8.2f %8.2f %8.2f %9d %9d  %s"
                  % (i + 1, r.get("first_chunk_s") or 0, r.get("gen_s") or 0,
                     r["total_s"], ct, rt,
                     "✅" if ok else "❌ " + (perr or "")))
        if totals:
            print()
            print("  合计  中位 %6.2fs   均值 %6.2fs   最大 %6.2fs"
                  % (statistics.median(totals), statistics.mean(totals), max(totals)))
            print("  输出tok 中位 %5.0f   思考tok 中位 %5.0f（占 %.0f%%）"
                  % (statistics.median(outtoks), statistics.median(thinks),
                     statistics.median(thinks) / max(statistics.median(outtoks), 1e-9) * 100))
            print("  **解析成功 %d/%d**" % (oks, len(totals)))
            summary.append((tag, statistics.median(totals), statistics.mean(totals),
                            oks, len(totals),
                            statistics.median(thinks) / max(statistics.median(outtoks), 1e-9) * 100))
        print()

    conn.close()

    print("=" * 84)
    print("判读")
    print("=" * 84)
    if summary:
        base = summary[0]
        print("  %-26s %10s %10s %10s %10s" % ("配置", "中位", "均值", "相对现状", "解析通过"))
        for tag, med, mean, oks, n, think_pct in summary:
            print("  %-26s %9.2fs %9.2fs %9.2fx %7d/%-3d"
                  % (tag, med, mean, med / max(base[1], 1e-9), oks, n))
        print()
        best = min(summary[1:], key=lambda x: x[1]) if len(summary) > 1 else None
        if best and best[3] == best[4]:
            print("  ✅ 有配置同时满足「更快」和「解析全通过」：%s" % best[0])
            print("     → max_tokens 压到只够输出 JSON，思考链就会被挤掉。")
            print("       这是零成本改动：只影响生成长度上限，不改模型、不换通道。")
        else:
            print("  ⚠️ 压 max_tokens 会让解析失败率上升 —— 不能只图快。")
            print("     需要改成「max_tokens 适中 + 解析失败重试一次」的组合策略。")
        print()
        print("  注意：思考 token 占比那一列才是根因指标。它高说明模型在想，"
              "\n        而这一段的 token 速率最慢（~9 tok/s），所以是延迟的主要来源。")


if __name__ == "__main__":
    main()