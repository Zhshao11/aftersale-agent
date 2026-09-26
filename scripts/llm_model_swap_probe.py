#!/usr/bin/env python3
"""换模型的收益 vs 代价：不只量耗时，还要量**分类还对不对**。

前面的探针已经说明慢在 decode。但"换个小模型"是个有代价的方案——
快 7 倍却把意图判错，是净亏。所以这个脚本同时量两边：

  - 耗时：首 token / 生成 / 合计，多次取中位数（单次会漂移，见第 12 条日志）
  - 正确性：能不能解析出 intent、与期望是否一致

跑完就能回答"换模型值不值"，而不是只回答"换模型快不快"。

用法：
    cd aftersale-agent && set -a && . ./.env && set +a
    python3 scripts/llm_model_swap_probe.py
    python3 scripts/llm_model_swap_probe.py --models glm-5.3,glm-5.3-flash --repeat 3
"""
import argparse
import json
import os
import re
import statistics
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from llm_latency_bottleneck import Conn, parse_endpoint, stream_call  # noqa: E402

# 与 IntentRouter.SYSTEM 保持一致（改代码时这里要同步，否则测的不是真实 prompt）
SYSTEM = """你是售后意图分类器。判断用户消息属于哪类：
- QUERY：查询信息（查订单、查物流、查政策、咨询规则等，无状态变更诉求）
- WRITE：请求执行操作（取消订单、退款、退货、换货，或"不想要了/退了吧"等隐含写操作的表达）
同时把用户诉求改写成一句规范的中文短句。
只输出 JSON，格式：{"intent":"QUERY|WRITE","request":"改写后的诉求"}，不要输出其他内容。
"""

# 覆盖真实难点：显式写操作、隐含写操作、查询、以及容易被误判的边界
CASES = [
    ("帮我把订单 ORD20260901001 取消", "WRITE"),
    ("帮我查一下我买的咖啡机到哪了？", "QUERY"),
    ("这个耳机不好用，不想要了", "WRITE"),
    ("退货政策是怎么规定的？", "QUERY"),
    ("我的订单什么时候发货", "QUERY"),
    ("我要退款", "WRITE"),
]

JSON_BLOCK = re.compile(r'\{[^{}]*"intent"[^{}]*\}')


def parse_intent(text):
    m = JSON_BLOCK.search(text or "")
    if not m:
        return None
    try:
        node = json.loads(m.group())
    except Exception:
        return None
    v = str(node.get("intent", "")).strip().upper()
    return v or None


def run_one(conn, model, user, max_tokens, want):
    t0 = time.time()
    r, err = stream_call(conn, model, SYSTEM + "\n用户消息：" + user, max_tokens)
    wall = time.time() - t0
    if err:
        return {"ok": False, "err": err[:80], "wall": wall}
    got = parse_intent(json.dumps(r.get("usage") or {}))
    # usage 里没有正文，单独再取一次非流式拿内容（流式内容已被丢弃）
    return {"ok": True, "r": r, "wall": wall}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="glm-5.3,glm-5.3-flash")
    ap.add_argument("--repeat", type=int, default=3)
    ap.add_argument("--max-tokens", type=int, default=1024)
    args = ap.parse_args()

    host, port, path, raw = parse_endpoint()
    print("端点 %s   max_tokens=%d   每条用例每模型 %d 次\n"
          % (raw, args.max_tokens, args.repeat))
    models = [m.strip() for m in args.models.split(",") if m.strip()]

    summary = {}
    for model in models:
        conn = Conn(host, port)
        conn.path = path
        print("=" * 78)
        print("模型 %s" % model)
        print("=" * 78)
        print("  %-26s %-6s %8s %8s %8s  %s"
              % ("用例", "期望", "首token", "生成", "合计", "判出/正确"))
        all_t, all_first, all_gen, hit = [], [], [], 0
        for user, want in CASES:
            times, firsts, gens = [], [], []
            got_last, ok_last = None, False
            for _ in range(args.repeat):
                r, err = stream_call(conn, model, SYSTEM + "\n用户消息：" + user,
                                     args.max_tokens)
                if err:
                    print("    失败: %s" % err[:70])
                    continue
                times.append(r["total_s"])
                firsts.append(r.get("first_chunk_s") or 0)
                gens.append(r.get("gen_s") or 0)
                # 正文从流式 chunk 里拼，不再单独发一次非流式请求
                got_last = parse_intent(r.get("text"))
            ok_last = (got_last == want)
            hit += 1 if ok_last else 0
            if times:
                all_t += times
                all_first += firsts
                all_gen += gens
                print("  %-26s %-6s %8.2f %8.2f %8.2f  %s %s"
                      % (user[:24], want, statistics.median(firsts),
                         statistics.median(gens), statistics.median(times),
                         got_last or "-", "✅" if ok_last else "❌"))
            else:
                print("  %-26s %-6s %8s %8s %8s  全部失败"
                      % (user[:24], want, "-", "-", "-"))
        if all_t:
            summary[model] = {
                "median_total": statistics.median(all_t),
                "median_first": statistics.median(all_first),
                "median_gen": statistics.median(all_gen),
                "acc": hit / len(CASES), "n": len(all_t),
            }
            print("  → 中位：首token %.2fs / 生成 %.2fs / 合计 %.2fs；"
                  "分类正确 %d/%d" % (statistics.median(all_first),
                                      statistics.median(all_gen),
                                      statistics.median(all_t), hit, len(CASES)))
        conn.close()
        print()

    if len(summary) >= 2:
        print("=" * 78)
        print("结论对照")
        print("=" * 78)
        base = models[0]
        b = summary.get(base)
        for m in models[1:]:
            s = summary.get(m)
            if not (b and s):
                continue
            print("  %s → %s：合计 %.2fs → %.2fs（快 %.1fx）；"
                  "首 token %.2fs → %.2fs；正确率 %.0f%% → %.0f%%"
                  % (base, m, b["median_total"], s["median_total"],
                     b["median_total"] / s["median_total"],
                     b["median_first"], s["median_first"],
                     b["acc"] * 100, s["acc"] * 100))
            print("  → 提速来自哪：生成段 %.2fs → %.2fs（%.1fx），"
                  "首 token 段 %.2fs → %.2fs（%.1fx）"
                  % (b["median_gen"], s["median_gen"],
                     b["median_gen"] / max(s["median_gen"], 1e-6),
                     b["median_first"], s["median_first"],
                     b["median_first"] / max(s["median_first"], 1e-6)))
            if s["acc"] < b["acc"]:
                print("  ⚠️ 正确率下降：不要用提速换错判，先修 prompt 或换回原模型")


if __name__ == "__main__":
    main()
