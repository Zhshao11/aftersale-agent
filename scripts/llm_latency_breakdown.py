"""
线上 LLM 延迟拆解探针

目的：回答"换本地部署会不会更快"。
前提必须先证明或证伪：10~30s 到底花在 网络 / 服务端排队 / 生成长度 哪一层。
只测总时长是没用的——总时长无法区分"网慢"和"模型慢"。

输出四组数：
  A. TCP 建连 + TLS 握手耗时              -> 纯网络往返的量级
  B. 最小请求（1 字提示 + 输出上限 16）    -> 服务端固定开销 + 排队
  C. 真实提示词、非流式                   -> 总时长 + 真实 completion token 数
  D. 真实提示词、流式                     -> 首 token 时间(TTFT) / 解码耗时 / tokens-per-s
"""
import json
import os
import socket
import ssl
import sys
import time
import urllib.request
from urllib.parse import urlparse

# 仓库根 = scripts/ 的上一级。从脚本自身位置推导，避免把本机绝对路径写进公开仓库。
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load_env():
    env = {}
    with open(os.path.join(REPO, ".env"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    return env


ENV = load_env()
BASE = ENV["LLM_BASE_URL"].rstrip("/")
MODEL = ENV.get("LLM_MODEL", "unknown")
KEY = ENV["LLM_API_KEY"]
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

# 端点路径探测：不同厂商的 OpenAI 兼容路径前缀不一样
if BASE.endswith("/v1"):
    URL = BASE + "/chat/completions"
else:
    URL = BASE + "/v1/chat/completions"

print("端点   : %s" % URL)
print("模型   : %s" % MODEL)
print("密钥   : %s****（长度 %d）" % (KEY[:4], len(KEY)))


# ── A. 纯网络：TCP + TLS ────────────────────────────────────────────────
def net_probe():
    u = urlparse(URL)
    host, port = u.hostname, (u.port or (443 if u.scheme == "https" else 80))
    ts = []
    for _ in range(3):
        t0 = time.perf_counter()
        s = socket.create_connection((host, port), timeout=10)
        t_tcp = time.perf_counter() - t0
        t_tls = 0.0
        if u.scheme == "https":
            t1 = time.perf_counter()
            ctx = ssl.create_default_context()
            s = ctx.wrap_socket(s, server_hostname=host)
            t_tls = time.perf_counter() - t1
        s.close()
        ts.append((t_tcp, t_tls))
    for i, (tcp, tls) in enumerate(ts, 1):
        print("  [A%d] TCP %.0fms + TLS %.0fms" % (i, tcp * 1000, tls * 1000))
    return ts


# ── B/C. 非流式一次调用 ────────────────────────────────────────────────
def call_once(messages, max_tokens=None, label=""):
    body = {"model": MODEL, "messages": messages, "temperature": 0.1}
    if max_tokens:
        body["max_tokens"] = max_tokens
    data = json.dumps(body).encode()
    req = urllib.request.Request(URL, data=data, headers={
        "Content-Type": "application/json",
        "Authorization": "Bearer " + KEY,
    })
    t0 = time.perf_counter()
    try:
        raw = OPENER.open(req, timeout=180).read()
    except Exception as e:
        print("  [%s] 失败: %s" % (label, e))
        return None
    dt = time.perf_counter() - t0
    try:
        d = json.loads(raw)
        usage = d.get("usage") or {}
        content = (d.get("choices") or [{}])[0].get("message", {}).get("content", "")
    except Exception:
        usage, content = {}, raw[:200].decode("utf-8", "replace")
    pt = usage.get("prompt_tokens")
    ct = usage.get("completion_tokens")
    print("  [%s] 总 %.2fs  prompt_tokens=%s completion_tokens=%s  输出=%r"
          % (label, dt, pt, ct, content[:60]))
    return {"sec": dt, "pt": pt, "ct": ct, "text": content}


# ── D. 流式：分离 TTFT 与解码 ───────────────────────────────────────────
def call_stream(messages, label=""):
    body = {"model": MODEL, "messages": messages, "temperature": 0.1, "stream": True}
    data = json.dumps(body).encode()
    req = urllib.request.Request(URL, data=data, headers={
        "Content-Type": "application/json",
        "Authorization": "Bearer " + KEY,
        "Accept": "text/event-stream",
    })
    t0 = time.perf_counter()
    ttft = None
    chunks = 0
    try:
        resp = OPENER.open(req, timeout=180)
        buf = b""
        while True:
            block = resp.read(1)
            if not block:
                break
            buf += block
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                line = line.strip()
                if not line.startswith(b"data:"):
                    continue
                payload = line[5:].strip()
                if payload == b"[DONE]":
                    break
                try:
                    obj = json.loads(payload)
                except Exception:
                    continue
                delta = (obj.get("choices") or [{}])[0].get("delta", {}).get("content")
                if delta:
                    if ttft is None:
                        ttft = time.perf_counter() - t0
                    chunks += 1
    except Exception as e:
        print("  [%s] 流式失败: %s" % (label, e))
        return None
    total = time.perf_counter() - t0
    dec = total - (ttft or 0)
    tps = (chunks / dec) if dec > 0 and chunks else 0
    print("  [%s] TTFT %.2fs  解码 %.2fs  片段=%d  约 %.1f tok/s  总计 %.2fs"
          % (label, ttft or -1, dec, chunks, tps, total))
    return {"ttft": ttft, "total": total, "chunks": chunks, "tps": tps}


INTENT_SYS = (
    "你是售后意图分类器。判断用户消息属于哪类：\n"
    "- QUERY：查询信息（查订单、查物流、查政策、咨询规则等，无状态变更诉求）\n"
    "- WRITE：请求执行操作（取消订单、退款、退货、换货，或\"不想要了/退了吧\"等隐含写操作的表达）\n"
    "同时把用户诉求改写成一句规范的中文短句。\n"
    "只输出 JSON，格式：{\"intent\":\"QUERY|WRITE\",\"request\":\"改写后的诉求\"}，不要输出其他内容。"
)
USER = "我想把耳机退掉，这个耳机不好用"

ROUNDS = int(sys.argv[1]) if len(sys.argv) > 1 else 3

print("\n== A. 纯网络（TCP + TLS 握手，3 次）==")
net = net_probe()
avg_net = sum(a + b for a, b in net) / len(net)
print("  平均握手 %.0fms" % (avg_net * 1000))

print("\n== B. 最小请求（输出上限 16 token），3 次 ==")
for i in range(1, 4):
    call_once([{"role": "user", "content": "1+1=?"}], max_tokens=16, label="B%d" % i)

print("\n== C. 真实意图识别提示词，非流式，%d 次 ==" % ROUNDS)
cs = []
for i in range(1, ROUNDS + 1):
    r = call_once([{"role": "system", "content": INTENT_SYS},
                   {"role": "user", "content": USER}], label="C%d" % i)
    if r:
        cs.append(r)

print("\n== D. 同一提示词，流式（分离 TTFT / 解码）==")
ds = []
for i in range(1, 3):
    r = call_stream([{"role": "system", "content": INTENT_SYS},
                     {"role": "user", "content": USER}], label="D%d" % i)
    if r:
        ds.append(r)

print("\n== 判读 ==")
if cs:
    tot = sum(c["sec"] for c in cs) / len(cs)
    cts = [c["ct"] for c in cs if c["ct"]]
    print("  真实请求平均总时长      : %.2fs" % tot)
    if cts:
        print("  平均 completion_tokens  : %.0f" % (sum(cts) / len(cts)))
    print("  纯网络握手占比          : %.2f%%  （%.0fms / %.0fms）"
          % (avg_net / tot * 100, avg_net * 1000, tot * 1000))
    spread = max(c["sec"] for c in cs) - min(c["sec"] for c in cs)
    print("  同请求时长极差          : %.2fs  （排队信号，不是算力信号）" % spread)
if ds:
    t = sum(d["ttft"] for d in ds) / len(ds)
    p = sum(d["total"] for d in ds) / len(ds)
    print("  平均 TTFT               : %.2fs  （首 token：排队 + 预填充）" % t)
    print("  平均总时长(流式)        : %.2fs" % p)
