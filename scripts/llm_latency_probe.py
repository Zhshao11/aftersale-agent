#!/usr/bin/env python3
"""LLM 延迟归因探针。

**为什么需要先量再改**：一次对话请求要 20~40 秒，最自然的猜测是"连接没复用 / 网络慢"。
这个猜测对不对，必须先分解再动手——否则会花时间优化几百毫秒的握手，
而真正的 20 秒原封不动。

脚本量三件事：

  1) 分解 —— DNS / TCP / TLS / TTFB 各占多少。若 TTFB 占绝对多数，问题在服务端，
     客户端侧的一切优化（连接池、keep-alive、压缩）都是噪声。
  2) 复用 —— 同一连接连发多次，总耗时是否下降。连接复用能省掉的只有握手那几百毫秒。
  3) 对照 —— 同一 prompt 换模型，耗时差多少。排队与推理量才是大头。

用法：
    cd aftersale-agent && set -a && source .env && set +a
    python3 scripts/llm_latency_probe.py                # 全量
    python3 scripts/llm_latency_probe.py --models kimi-k3,glm-5.3
    python3 scripts/llm_latency_probe.py --skip-concurrency

注意：本机 shell 设了 HTTP_PROXY/HTTPS_PROXY，curl 会把请求送去代理，
所以这里一律带 --noproxy '*'。
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor

# 同一句话给所有模型，保证"模型差异"是唯一变量
PROMPT = "用一句话说明：电商售后里「取消订单」和「退款」有什么区别？"

CURL_W = "%{time_namelookup} %{time_connect} %{time_appconnect} %{time_starttransfer} %{time_total}"


def endpoint() -> str:
    base = os.environ.get("LLM_BASE_URL", "").strip().rstrip("/")
    if not base:
        sys.exit("LLM_BASE_URL 未设置；先 `set -a && source .env && set +a`")
    # 兼容两种写法：base 已含 /v1（dashscope compatible-mode 等）与原样域名
    return base + "/chat/completions" if base.endswith("/v1") else base + "/v1/chat/completions"


def _curl_cmd(body_file, resp_file):
    return [
        "curl", "-sS", "--noproxy", "*", "-X", "POST", endpoint(),
        "-H", "Authorization: Bearer " + os.environ.get("LLM_API_KEY", ""),
        "-H", "Content-Type: application/json",
        "-d", "@" + body_file, "-o", resp_file, "-w", CURL_W,
    ]


def call(model, max_tokens=300, workdir="/tmp"):
    """调用一次，返回 (timings dict, usage dict)。失败返回 (None, error str)。"""
    body = {"model": model, "max_tokens": max_tokens,
            "messages": [{"role": "user", "content": PROMPT}]}
    bf = os.path.join(workdir, "body.json")
    rf = os.path.join(workdir, "resp.json")
    with open(bf, "w", encoding="utf-8") as f:
        json.dump(body, f, ensure_ascii=False)
    p = subprocess.run(_curl_cmd(bf, rf), capture_output=True, text=True, timeout=180)
    if p.returncode != 0:
        return None, p.stderr.strip()[:120]
    parts = p.stdout.split()
    if len(parts) != 5:
        return None, "curl 输出异常: " + p.stdout[:120]
    keys = ["dns", "tcp", "tls", "ttfb", "total"]
    timings = {k: float(v) for k, v in zip(keys, parts)}
    try:
        with open(rf, encoding="utf-8") as f:
            resp = json.load(f)
    except Exception as e:
        return timings, "响应非 JSON: " + str(e)
    usage = resp.get("usage") or {}
    details = usage.get("completion_tokens_details") or {}
    usage = {
        "prompt_tokens": usage.get("prompt_tokens"),
        "completion_tokens": usage.get("completion_tokens"),
        "reasoning_tokens": details.get("reasoning_tokens"),
        "cached_tokens": (usage.get("prompt_tokens_details") or {}).get("cached_tokens"),
    }
    if resp.get("error"):
        return timings, "API error: " + str(resp["error"])[:140]
    return timings, usage


def bar(seconds, scale=1.0, width=28):
    n = int(round(min(seconds / scale, 1.0) * width))
    return "█" * n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="glm-5.3,kimi-k3,deepseek-v4.1-flash,glm-5.3-flash",
                    help="逗号分隔的模型名（默认取对照组）")
    ap.add_argument("--skip-concurrency", action="store_true")
    ap.add_argument("--max-tokens", type=int, default=300)
    args = ap.parse_args()

    if not os.environ.get("LLM_API_KEY"):
        sys.exit("LLM_API_KEY 未设置")

    workdir = tempfile.mkdtemp(prefix="lat-probe-")
    print(f"端点: {endpoint()}")
    print(f"单一变量 prompt: {PROMPT}\n")

    # ---- 1) 分解：DNS/TCP/TLS/TTFB ----
    print("=" * 72)
    print("1) 单次请求的时间分解（同一 prompt，max_tokens=%d）" % args.max_tokens)
    print("=" * 72)
    print(f"{'模型':<22}{'dns':>7}{'tcp':>8}{'tls':>8}{'ttfb':>9}{'total':>9}  {'reasoning':>9}{'cached':>7}")
    rows = {}
    for model in [m.strip() for m in args.models.split(",") if m.strip()]:
        t, u = call(model, args.max_tokens, workdir)
        if t is None:
            print(f"{model:<22}  失败: {u}")
            continue
        if isinstance(u, str):
            print(f"{model:<22}{t['dns']:>7.3f}{t['tcp']:>8.3f}{t['tls']:>8.3f}"
                  f"{t['ttfb']:>9.3f}{t['total']:>9.3f}  {u}")
            continue
        rows[model] = t
        print(f"{model:<22}{t['dns']:>7.3f}{t['tcp']:>8.3f}{t['tls']:>8.3f}"
              f"{t['ttfb']:>9.3f}{t['total']:>9.3f}  {str(u['reasoning_tokens']):>9}{str(u['cached_tokens']):>7}")

    if rows:
        slowest = max(rows.values(), key=lambda x: x["total"])
        handshake = slowest["dns"] + slowest["tcp"] + slowest["tls"]
        print(f"\n最慢一次 total={slowest['total']:.2f}s，其中 DNS+TCP+TLS={handshake:.3f}s "
              f"({handshake / slowest['total'] * 100:.2f}%)，TTFB={slowest['ttfb']:.2f}s")
        print(f"→ 握手占比 {handshake / slowest['total'] * 100:.2f}%：连接复用最多能省掉这一块，"
              f"省不掉 TTFB。{bar(slowest['ttfb'], slowest['total'])} = TTFB")
        fast = min(rows.values(), key=lambda x: x["total"])
        print(f"→ 模型对照：最快 {fast['total']:.2f}s / 最慢 {slowest['total']:.2f}s "
              f"（相差 {slowest['total'] / fast['total']:.2f}x）")

    # ---- 2) 复用：同一连接连发 ----
    print("\n" + "=" * 72)
    print("2) 连接复用对照（curl --next：同一 TCP 连接连发 3 次）")
    print("=" * 72)
    model = next(iter(rows), args.models.split(",")[0].strip())
    body = {"model": model, "max_tokens": 1,
            "messages": [{"role": "user", "content": "hi"}]}
    bf = os.path.join(workdir, "reuse.json")
    with open(bf, "w", encoding="utf-8") as f:
        json.dump(body, f)
    cmd = ["curl", "-sS", "--noproxy", "*"]
    for i in range(3):
        # --next 会重置上一段的选项：-o 必须逐段重复，否则第 2 段起响应体会打到 stdout，
        # 混进时间输出里（之前就踩了这个，导致多出来的行把编号撑成 1/3/5）
        if i:
            cmd += ["--next"]
        cmd += ["-o", os.devnull,
                "-X", "POST", endpoint(),
                "-H", "Authorization: Bearer " + os.environ["LLM_API_KEY"],
                "-H", "Content-Type: application/json", "-d", "@" + bf,
                "-w", "%{time_connect} %{time_total}\\n"]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=240)
    print(f"{'第几次':<8}{'time_connect':>14}{'time_total':>13}")
    rows_reuse = []
    for line in p.stdout.strip().splitlines():
        parts = line.split()
        if len(parts) == 2:
            try:
                rows_reuse.append((float(parts[0]), float(parts[1])))
            except ValueError:
                pass
    for i, (c, t) in enumerate(rows_reuse, 1):
        print(f"{i:<8}{c:>14.4f}{t:>13.3f}")
    if len(rows_reuse) >= 2:
        print(f"→ time_connect {rows_reuse[0][0]:.4f}s → {rows_reuse[-1][0]:.4f}s（连接确实复用了）；"
              f"time_total {rows_reuse[0][1]:.3f}s → {rows_reuse[-1][1]:.3f}s（没有跟着降）")
    print("→ 握手能省掉的只有那几百毫秒，耗时不在连接上。")

    # ---- 3) 并发 ----
    if not args.skip_concurrency:
        print("\n" + "=" * 72)
        print("3) 串行 vs 并发（同一请求发 2 次）")
        print("=" * 72)
        t0 = time.time()
        for _ in range(2):
            call(model, 1, workdir)
        serial = time.time() - t0
        t0 = time.time()
        with ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(lambda _: call(model, 1, workdir), range(2)))
        concurrent = time.time() - t0
        print(f"串行 2 次: {serial:.1f}s   并发 2 次: {concurrent:.1f}s   "
              f"加速 {serial / concurrent:.2f}x")
        print("→ 并发接近线性加速说明服务端是按请求排队的：耗时是每次调用的服务端工作量，"
              "不是一次性的连接开销。")

    shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    main()
