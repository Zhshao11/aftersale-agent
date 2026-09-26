#!/usr/bin/env python3
"""生产 faithful 对照：为什么探针测 7.4s、生产 trace 却是 32.4s？

背景（第 18 条的延续）
----------------------
把夹具改保真之后，PLAN 的探针数字掉到中位 7.37s / p90 13.14s。
但生产 `agent_trace` 里 PLAN 的真实数字是 **均值 34.1s、最大 93.4s**
（11 次，`/1000` 后为 14.1/21.3/21.6/32.4/36.7/38.4/51.9/65.5/93.4）。
差 **4.4 倍**。夹具已经对齐了，所以差异只可能在**调用方式**或**提示词内容**：

生产 SpringAiLlmPort（LlmPort.java 第 55-59 行）：
    chatClientBuilder.build().prompt().system(systemPrompt)
        .user(userMessage).call().content()
    → **非流式**、`temperature=0.1`（application.yml 第 31 行）、
      **不设 max_tokens**（用 provider 默认）

我的探针：
    → **流式**（stream:true）、**不设 temperature**（默认 1.0）、`max_tokens=512`

三个变量都能解释"生成更多 token"（实测生产 ct≈355，探针 ct≈44~100，差 5 倍）。
本探针逐个打开，找出到底是哪一个——**不能靠猜**。

四臂设计（同夹具、同提示词、逐轮交替）：
  A STREAM+512      ：我探针的配置
  B NONSTREAM+512   ：只把流式换成非流式
  C NONSTREAM+T0.1  ：再加 temperature=0.1（生产值）
  D PROD-LIKE       ：C + 去掉 max_tokens（完全按生产的调用形状）

若 D ≈ 32s，则根因是**调用形状**；若 D 仍 ≈ 7s，
则根因在**提示词内容**（生产的真实对话历史比我探针的长/杂），需要从 DB 取真实历史。

用法：
    cd aftersale-agent && set -a && . ./.env && set +a
    python3 scripts/llm_prod_fidelity_probe.py --repeat 6
"""
import argparse
import http.client
import json
import os
import ssl
import statistics
import sys
import time
from urllib.parse import urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from llm_latency_bottleneck import parse_endpoint  # noqa: E402
from llm_prompt_variant_probe import (  # noqa: E402
    PLAN_SYSTEM, HISTORY, USER_MSG, ORDER_CTX, parse_plan)


def call_raw(host, port, path, model, prompt, max_tokens=None,
             temperature=None, stream=False, timeout=180):
    """一次调用，参数可控。返回值里带 total_s / usage / text。

    不复用 llm_latency_bottleneck.stream_call —— 那个写死了 stream=True，
    而这里要的正是**同一个调用在流式与非流式下的差异**。
    """
    body = {"model": model, "messages": [{"role": "user", "content": prompt}],
            "stream": stream}
    if max_tokens is not None:
        body["max_tokens"] = max_tokens
    if temperature is not None:
        body["temperature"] = temperature
    payload = json.dumps(body)
    headers = {"Content-Type": "application/json",
               "Authorization": "Bearer " + os.environ.get("LLM_API_KEY", ""),
               "Accept": "text/event-stream" if stream else "application/json"}

    ctx = ssl.create_default_context()
    conn = http.client.HTTPSConnection(host, port, timeout=timeout, context=ctx)
    t0 = time.time()
    first = None
    text_parts, usage = [], {}
    try:
        conn.request("POST", path, body=payload, headers=headers)
        resp = conn.getresponse()
        if resp.status >= 400:
            return {"err": "HTTP %d %s" % (resp.status, resp.read()[:120])}
        if stream:
            buf = b""
            while True:
                chunk = resp.read(1)
                if not chunk:
                    break
                buf += chunk
                if not buf.endswith(b"\n"):
                    continue
                line = buf.decode("utf-8", "ignore").strip()
                buf = b""
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                try:
                    d = json.loads(data)
                except Exception:
                    continue
                if first is None:
                    first = time.time() - t0
                if d.get("usage"):
                    usage = d["usage"]
                for ch in d.get("choices") or []:
                    delta = ch.get("delta") or {}
                    for k in ("content", "reasoning_content"):
                        if delta.get(k):
                            text_parts.append(delta[k])
        else:
            # 非流式：一次性拿到全部，没有中间观察点——
            # 这正是生产拿不到"首 token vs 生成"分解的原因。
            raw = resp.read().decode("utf-8", "ignore")
            d = json.loads(raw)
            usage = d.get("usage") or {}
            for ch in d.get("choices") or []:
                msg = ch.get("message") or {}
                text_parts.append(msg.get("content") or "")
                if msg.get("reasoning_content"):
                    text_parts.insert(0, msg["reasoning_content"])
        total = time.time() - t0
        return {"total": total, "first": first, "usage": usage,
                "text": "".join(text_parts), "err": None}
    except Exception as e:
        return {"err": "%s: %s" % (type(e).__name__, str(e)[:80])}
    finally:
        try:
            conn.close()
        except Exception:
            pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.environ.get("LLM_MODEL", "glm-5.3"))
    ap.add_argument("--repeat", type=int, default=6)
    args = ap.parse_args()

    host, port, path, raw = parse_endpoint()
    print("端点 %s   模型 %s   每臂 %d 次" % (raw, args.model, args.repeat))
    print("夹具：保真（PlanService 4 字段）   提示词：逐字复刻 PlanGenerator.SYSTEM\n")

    hist = "".join("\n%s: %s" % (r, c) for r, c in HISTORY)
    prompt = PLAN_SYSTEM + ("\n\n用户诉求: %s\n相关订单信息: %s"
                            % (hist + USER_MSG, ORDER_CTX))

    arms = [
        ("A 流式 + max512（我探针）",      dict(stream=True,  max_tokens=512, temperature=None)),
        ("B 非流式 + max512",              dict(stream=False, max_tokens=512, temperature=None)),
        ("C 非流式 + max512 + T0.1",       dict(stream=False, max_tokens=512, temperature=0.1)),
        ("D 生产形状（非流式/无max/T0.1）", dict(stream=False, max_tokens=None, temperature=0.1)),
    ]

    got = {}
    for i in range(args.repeat):
        for name, kw in arms:
            r = call_raw(host, port, path, args.model, prompt, **kw)
            got.setdefault(name, []).append(r)
            if r["err"]:
                print("  第%d轮 %-30s 失败 %s" % (i + 1, name, r["err"][:60]))
            else:
                u = r["usage"] or {}
                rt = (u.get("completion_tokens_details") or {}).get("reasoning_tokens")
                ok = "✓" if parse_plan(r["text"]) else "✗"
                fir = "%.2f" % r["first"] if r["first"] else "—"
                print("  第%d轮 %-30s %7.2fs 首token %6s  ct=%-4s rt=%-4s %s"
                      % (i + 1, name, r["total"], fir, u.get("completion_tokens"), rt, ok))

    print("\n" + "=" * 100)
    print("%-32s %8s %8s %9s %10s %9s" % ("臂", "中位", "p90", "ct中位", "rt中位", "解析"))
    print("=" * 100)
    for name, _ in arms:
        ok = [r for r in got[name] if not r["err"]]
        tot = [r["total"] for r in ok]
        cts = [(r["usage"] or {}).get("completion_tokens") for r in ok]
        cts = [c for c in cts if c]
        rts = [(r["usage"] or {}).get("completion_tokens_details", {}) or {}
               for r in ok]
        rts = [x.get("reasoning_tokens") for x in rts if x.get("reasoning_tokens") is not None]
        parsed = sum(1 for r in ok if parse_plan(r["text"]))
        print("%-32s %7.2fs %7.2fs %9s %10s %6d/%d"
              % (name, statistics.median(tot) if tot else 0,
                 sorted(tot)[int(len(tot) * 0.9)] if tot else 0,
                 "%.0f" % statistics.median(cts) if cts else "—",
                 "%.0f" % statistics.median(rts) if rts else "—",
                 parsed, len(ok)))

    print("\n" + "=" * 100)
    print("判读")
    print("=" * 100)
    A = got["A 流式 + max512（我探针）"]
    D = got["D 生产形状（非流式/无max/T0.1）"]
    a = statistics.median([r["total"] for r in A if not r["err"]] or [0])
    d = statistics.median([r["total"] for r in D if not r["err"]] or [0])
    print("  我探针形状 %.2fs  →  生产形状 %.2fs" % (a, d))
    if d > a * 2:
        print("  ⚠️ 生产形状显著更慢 → **根因是调用形状**（非流式 / 无 max_tokens / T0.1），")
        print("     而不是提示词内容。也就是说：")
        print("     · 我探针里那些「提示词优化」的数字，是在一个**比生产快 4 倍的配置**上测的；")
        print("     · 真正该问的问题变成了：**为什么非流式会比流式慢这么多？**")
        print("       若原因是「非流式下 provider 不做增量解码/无抢占」——这是可查的机制问题。")
    else:
        print("  两者接近 → 我探针的 7.4s 与生产的 32.4s 差异**不在调用形状**，")
        print("     而在提示词内容（真实对话历史长度）。需要从 DB 取真实 history 再测。")
    print()
    print("  生产 trace 的 PLAN 参考值（agent_trace, 11 次 SUCCESS）：")
    print("    均值 34.1s / 最大 93.4s；序列 14.1 21.3 21.6 32.4 36.7 38.4 51.9 65.5 93.4")
    print("    → 对照本表最后一列，看哪一臂能复现这个量级。")


if __name__ == "__main__":
    main()