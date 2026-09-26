#!/usr/bin/env python3
"""LLM 延迟瓶颈定位：把「慢」拆成 排队 / 预填充 / 生成 三段。

**为什么现有探针不够**：`llm_latency_probe.py` 只能分到 DNS/TCP/TLS/TTFB。
TTFB 在非流式请求里等于「服务端做完所有事」，它没法告诉你
服务端是在**排队等调度**、在**吃你的长 prompt（prefill）**、还是在**逐个吐 token（decode）**。
这三段的对策完全不同：

  排队慢  → 换通道 / 本地部署（本地没有排队）
  prefill → 缩短 prompt、减工具定义、开 prompt 缓存
  decode  → 换小模型、压输出长度、关思考链

**唯一能拆开它们的办法是流式**：第一个 chunk 到达的时间 = 排队 + prefill，
之后到最后一个 chunk = decode。非流式请求只能看到一个总数。

用法：
    cd aftersale-agent && set -a && . ./.env && set +a
    python3 scripts/llm_latency_bottleneck.py                 # 全量
    python3 scripts/llm_latency_bottleneck.py --skip-long     # 跳过长 prompt 对照
    python3 scripts/llm_latency_bottleneck.py --models glm-5.3,glm-5.3-flash

注意：本脚本用 http.client 裸连，**不走代理**（本机 HTTP_PROXY 会把 localhost/外网
请求都送去代理，症状是报 upstream connect failed 而不是真正的连接错误）。
"""
import argparse
import http.client
import json
import os
import socket
import ssl
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse

SHORT = "用一句话说明：电商售后里「取消订单」和「退款」有什么区别？"

# 模拟真实 agent 请求：系统提示 + 工具清单 + 多轮历史。
# 真实的意图路由 / ReAct 循环发的就是这种量级的东西，用短 prompt 测会低估 prefill。
TOOLS = "\n".join(
    "- %s(%s)：%s" % (n, p, d)
    for n, p, d in [
        ("getOrder", "orderNo", "按订单号查详情，含状态、金额、物流"),
        ("listMyOrders", "keyword?, status?, limit?", "列出当前用户订单，可按关键词模糊匹配"),
        ("getLogistics", "orderNo", "查物流轨迹与预计送达"),
        ("getPolicy", "orderNo", "查该订单的售后政策与可退金额"),
        ("cancelOrder", "orderNo, reason?", "取消订单，未发货可直接取消"),
        ("refundOrder", "orderNo, amountCents?", "发起退款，超阈值需二次确认"),
        ("createReturnRequest", "orderNo, reason", "创建退货申请单"),
        ("updateAddress", "orderNo, newAddress", "修改收货地址，已发货不可改"),
        ("listAftersaleTickets", "status?", "列出售后工单"),
        ("getUserInfo", "", "查当前用户昵称与会员等级"),
    ]
)
LONG = (
    "你是电商售后智能助手。必须先判断用户说的是查询还是写操作；写操作一律先定位订单、"
    "再生成执行计划、等用户确认后才执行。不得越权访问他人订单，不得编造订单号。"
    "可用工具如下：\n" + TOOLS + "\n\n"
    "以下是最近对话（节选）：\n"
    "用户：我上个月买的咖啡机还没到\n助手：已为您查询，订单 ORD20260901001 状态为已发货，预计 3 天后送达。\n"
    "用户：那耳机呢\n助手：订单 ORD202609160012 状态为待发货。\n"
    "用户：有没有金额超过 500 的订单\n助手：有，ORD20260901003 金额 899 元。\n\n"
    "当前用户 U001 说：帮我把订单 ORD20260901001 取消，这个不好用。\n"
    "请按规则判断意图并归一化诉求。"
)


def parse_endpoint():
    raw = os.environ.get("LLM_BASE_URL", "").strip().rstrip("/")
    if not raw:
        sys.exit("LLM_BASE_URL 未设置；先 `set -a && . ./.env && set +a`")
    u = urlparse(raw if "://" in raw else "https://" + raw)
    path = u.path.rstrip("/")
    if not path.endswith("/v1"):
        path += "/v1"
    return u.hostname, (u.port or 443), path + "/chat/completions", raw


def measure_dns_tcp_tls(host, port, timeout=15):
    """DNS / TCP / TLS 分开计时。这一段就是"连接复用最多能省掉的部分"。"""
    out = {}
    t0 = time.time()
    try:
        infos = socket.getaddrinfo(host, port, proto=socket.IPPROTO_TCP)
        out["dns"] = time.time() - t0
    except Exception as e:
        return {"dns": None, "tcp": None, "tls": None, "err": str(e)}
    addr = infos[0][4]
    t0 = time.time()
    try:
        s = socket.create_connection(addr, timeout=timeout)
        out["tcp"] = time.time() - t0
        t0 = time.time()
        ctx = ssl.create_default_context()
        s = ctx.wrap_socket(s, server_hostname=host)
        out["tls"] = time.time() - t0
        s.close()
    except Exception as e:
        out["err"] = str(e)
    return out


class Conn:
    """一条可复用的 HTTPS 连接。复用是为了测：省掉握手后总耗时降多少。"""

    def __init__(self, host, port, timeout=60):
        self.host, self.port, self.timeout = host, port, timeout
        self.h = None

    def _ensure(self):
        if self.h is None:
            t0 = time.time()
            self.h = http.client.HTTPSConnection(
                self.host, self.port, timeout=self.timeout,
                context=ssl.create_default_context())
            self.h.connect()
            self.connect_s = time.time() - t0
        return self.h

    def close(self):
        try:
            self.h.close()
        except Exception:
            pass
        self.h = None


def stream_call(conn, model, prompt, max_tokens=400, think=None, reuse=False):
    """流式一次调用，外面包一层"坏连接就丢弃重建"的重试。

    为什么必须重试：流式响应读完后服务端经常单方面关掉连接，
    下一次复用这条连接 `request()` 会抛 `Request-sent`。
    第一版脚本就是在这里翻车的——后面 5 组实验全部变成 request failed，
    而第 1 组数据其实是对的。**静默失败比报错更危险：它会让人以为"全部失败"。**
    """
    last = None
    for attempt in (0, 1):
        try:
            r, err = _stream_call_once(conn, model, prompt, max_tokens, think, reuse)
        except (http.client.HTTPException, OSError) as e:
            conn.close()
            last = "request failed: %s" % e
            if attempt == 1:
                return None, last
            continue
        # 连接坏了就换一条再来一次，不要把失败当成结论
        if err and conn.h is not None:
            conn.close()
            if attempt == 1:
                return None, err
            continue
        return r, err
    return None, last or "unknown"


def _stream_call_once(conn, model, prompt, max_tokens=400, think=None, reuse=False):
    """流式一次调用，返回分层计时 + usage。

    关键是 first_chunk 之后还要继续读，直到 [DONE]，
    这样才能把「首 token 之前」和「首 token 之后」分开。
    """
    body = {"model": model, "max_tokens": max_tokens, "stream": True,
            "messages": [{"role": "user", "content": prompt}]}
    # 部分网关支持思考预算；不支持就忽略这个字段
    if think is not None:
        body["thinking"] = {"type": "enabled" if think else "disabled"}
    payload = json.dumps(body, ensure_ascii=False).encode()

    h = conn._ensure()
    fresh = conn.connect_s if not reuse else None
    headers = {"Authorization": "Bearer " + os.environ.get("LLM_API_KEY", ""),
               "Content-Type": "application/json",
               "Accept": "text/event-stream", "Accept-Encoding": "identity"}
    t0 = time.time()
    h.request("POST", conn.path, body=payload, headers=headers)
    resp = h.getresponse()

    t_resp = time.time() - t0          # 到这里还没读到正文：只有响应头
    if resp.status != 200:
        err = resp.read()[:300].decode("utf-8", "replace")
        return {"connect_s": fresh, "header_s": t_resp,
                "first_chunk_s": None, "gen_s": None, "total_s": None}, \
               "HTTP %d %s" % (resp.status, err)

    t_first = None
    chunks = []
    while True:
        line = resp.readline()
        if not line:
            break
        if t_first is None and line.strip():
            t_first = time.time() - t0
        chunks.append(line)
        if line.startswith(b"data: [DONE]"):
            break
    t_total = time.time() - t0
    t_gen = (t_total - t_first) if t_first is not None else None

    # 从 chunk 里捞 usage 和思考 token（流式下 usage 通常在最后一个非空 chunk）
    usage = {}
    text_len = 0
    text_parts = []
    for raw in chunks:
        s = raw.decode("utf-8", "replace").strip()
        if not s.startswith("data:") or s.endswith("[DONE]"):
            continue
        try:
            d = json.loads(s[5:].strip())
        except Exception:
            continue
        if d.get("usage"):
            usage = d["usage"]
        for c in (d.get("choices") or []):
            delta = c.get("delta") or {}
            # 把正文拼出来：调用方要拿它判"答案对不对"，
            # 否则得再发一次非流式请求，耗时翻倍还容易踩连接复用。
            for k in ("content", "reasoning_content"):
                v = delta.get(k)
                if v:
                    text_parts.append(v)
                    text_len += len(v)
    return {"connect_s": fresh, "header_s": t_resp, "first_chunk_s": t_first,
            "gen_s": t_gen, "total_s": t_total, "chars": text_len,
            "text": "".join(text_parts), "usage": usage}, None


def fmt(v, w=8):
    return ("%*.2f" % (w, v)) if isinstance(v, (int, float)) else ("%*s" % (w, "-"))


def show(tag, r):
    u = r.get("usage") or {}
    ct = u.get("completion_tokens")
    rt = (u.get("completion_tokens_details") or {}).get("reasoning_tokens")
    tps = (ct / r["gen_s"]) if (ct and r.get("gen_s")) else None
    print("  %-14s 首token %s  生成 %s  合计 %s  输出tok %s  思考tok %s  %s tok/s"
          % (tag, fmt(r.get("first_chunk_s"), 7), fmt(r.get("gen_s"), 6),
             fmt(r.get("total_s"), 7), "%6s" % (ct if ct is not None else "-"),
             "%6s" % (rt if rt is not None else "-"),
             ("%.1f" % tps) if tps else "-"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default=os.environ.get("LLM_MODEL", "glm-5.3"))
    ap.add_argument("--max-tokens", type=int, default=400)
    ap.add_argument("--skip-long", action="store_true")
    ap.add_argument("--skip-concurrency", action="store_true")
    ap.add_argument("--repeat", type=int, default=3)
    args = ap.parse_args()

    if not os.environ.get("LLM_API_KEY"):
        sys.exit("LLM_API_KEY 未设置")
    host, port, path, raw = parse_endpoint()
    print("端点: %s   路径: %s" % (raw, path))
    print("模型: %s   max_tokens=%d\n" % (args.models, args.max_tokens))

    # ---- 1) 网络层：握手到底占多少 ----
    print("=" * 78)
    print("1) 网络层（这一段是连接复用 / 本地部署能省掉的全部）")
    print("=" * 78)
    hs = measure_dns_tcp_tls(host, port)
    if hs.get("err"):
        print("  握手失败: %s" % hs["err"])
    else:
        tot = (hs["dns"] or 0) + (hs["tcp"] or 0) + (hs["tls"] or 0)
        print("  DNS %s s   TCP %s s   TLS %s s   合计 %s s"
              % (fmt(hs.get("dns"), 6), fmt(hs.get("tcp"), 6),
                 fmt(hs.get("tls"), 6), fmt(tot, 6)))
        hs_total = tot
    print("  → 记住这个数：它通常是 <1s。如果请求要 20s，这一段根本不是瓶颈。")

    models = [m.strip() for m in args.models.split(",") if m.strip()]
    model = models[0]

    conn = Conn(host, port)
    conn.path = path

    # ---- 2) 短 prompt：拆 首token / 生成 ----
    print("\n" + "=" * 78)
    print("2) 短 prompt（%d 字）—— 拆「首 token 之前」与「首 token 之后」" % len(SHORT))
    print("=" * 78)
    r, err = stream_call(conn, model, SHORT, args.max_tokens)
    if err:
        print("  失败: %s" % err)
    else:
        show("短 prompt", r)
        base = r

    # ---- 2b) 思考开关：这是最可能的大头 ----
    print("\n" + "=" * 78)
    print("2b) 关掉思考链对照（thinking=disabled）—— 直接量「想」花了多少")
    print("=" * 78)
    r_off, err_off = stream_call(conn, model, SHORT, args.max_tokens, think=False)
    if err_off:
        print("  未支持（网关不接受 thinking 字段）: %s" % err_off[:100])
    else:
        show("关思考", r_off)
        if r.get("total_s") and r_off.get("total_s"):
            delta = r["total_s"] - r_off["total_s"]
            if delta > 0:
                print("  → 开思考 %.2fs vs 关思考 %.2fs：省掉 %.2fs（%.0f%%）"
                      % (r["total_s"], r_off["total_s"], delta, delta / r["total_s"] * 100))
            else:
                # 关思考反而更慢，有两种可能，不能只报一种：
                # ① 网关不认 thinking 字段，白跑一次还撞上排队；
                # ② 单次测量本身在漂移。**先重复测，别拿一次的结果下结论。**
                print("  → 开思考 %.2fs vs 关思考 %.2fs：反而慢 %.2fs。\n"
                      "     可能是网关不认 thinking 字段，也可能是单次漂移——"
                      "**注意首 token 那列**：它若暴涨说明这次撞上了排队，不代表关思考无效。"
                      % (r["total_s"], r_off["total_s"], -delta))

    # ---- 3) 长 prompt：prefill 成本 ----
    if not args.skip_long:
        print("\n" + "=" * 78)
        print("3) 长 prompt（%d 字，含 10 个工具定义 + 3 轮历史）—— prefill 增量" % len(LONG))
        print("=" * 78)
        r2, err = stream_call(conn, model, LONG, args.max_tokens)
        if err:
            print("  失败: %s" % err)
        else:
            show("长 prompt", r2)
            if not err and r.get("first_chunk_s") and r2.get("first_chunk_s"):
                d = r2["first_chunk_s"] - r["first_chunk_s"]
                print("  → prompt 变长 %d 字，首 token 晚 %.2fs：这就是 prefill 的单价"
                      % (len(LONG) - len(SHORT), d))

    # ---- 4) 极短输出：排队基线 ----
    print("\n" + "=" * 78)
    print("4) 排队基线（max_tokens=1, prompt='hi'）—— 生成几乎为 0 时的耗时")
    print("=" * 78)
    for i in range(args.repeat):
        r3, err = stream_call(conn, model, "hi", 1, reuse=True)
        if err:
            print("  第 %d 次失败: %s" % (i + 1, err))
        else:
            print("  第 %d 次  首token %s s  合计 %s s"
                  % (i + 1, fmt(r3.get("first_chunk_s"), 7), fmt(r3.get("total_s"), 7)))
    print("  → 如果连 max_tokens=1 都要等十几秒，那慢的是**排队/调度**，不是生成。")

    # ---- 5) 模型对照 ----
    if len(models) > 1:
        print("\n" + "=" * 78)
        print("5) 模型对照（同一长 prompt）—— 慢是通道的还是模型的")
        print("=" * 78)
        for m in models:
            rm, err = stream_call(conn, m, LONG, args.max_tokens, reuse=True)
            if err:
                print("  %-22s 失败: %s" % (m, err[:90]))
            else:
                show(m, rm)

    # ---- 6) 并发：是否被限速 ----
    if not args.skip_concurrency:
        print("\n" + "=" * 78)
        print("6) 串行 vs 并发（各 3 次 max_tokens=1）—— 通道是否限速")
        print("=" * 78)
        t0 = time.time()
        c1 = Conn(host, port); c1.path = path
        for _ in range(3):
            stream_call(c1, model, "hi", 1, reuse=True)
        serial = time.time() - t0

        conns = [Conn(host, port) for _ in range(3)]
        for c in conns:
            c.path = path
        t0 = time.time()
        with ThreadPoolExecutor(max_workers=3) as pool:
            pool.map(lambda c: stream_call(c, model, "hi", 1), conns)
        conc = time.time() - t0
        print("  串行 3 次 %s s   并发 3 次 %s s   加速 %.2fx"
              % (fmt(serial, 7), fmt(conc, 7), serial / conc if conc else 0))
        print("  → 接近 3x 说明服务端并行处理、没有全局排队；"
              "接近 1x 说明通道在串行排队（中转常见），本地部署收益最大。")

    conn.close()

    # ---- 结论判据 ----
    print("\n" + "=" * 78)
    print("判读（对照下面的门限）")
    print("=" * 78)
    print("""  首token > 总耗时的 70%  → 慢在 排队+prefill：本地部署**会**明显变快，
                              或者缩短 prompt / 开 prompt 缓存
  生成段 > 总耗时的 50%   → 慢在 decode：本地部署未必更快（取决于你的硬件吞吐），
                              先试试换小模型 / 关思考链 / 压 max_tokens
  思考 token 数很大        → 模型在"想"，不是网络在传：关思考或换非思考模型收益最大
  max_tokens=1 仍要 10s+   → 通道排队：本地部署收益最大""")


if __name__ == "__main__":
    main()
