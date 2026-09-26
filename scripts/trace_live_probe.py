#!/usr/bin/env python3
"""
轨迹实时性探针：验证"请求还在跑的时候，/api/trace/{traceId} 能不能看到已落库的步骤"。

为什么需要这个脚本：
  前端"思考过程实时更新"依赖一个不变量——**后端边跑边写，且写入对并发读可见**。
  这个不变量要么成立要么不成立，光看代码看不出来（事务边界、连接隔离级别、
  构建是否最新、浏览器缓存，任何一环都能让它静默失效）。
  所以这里用最朴素的办法验证：一边发请求，一边按时间戳轮询，把"第几秒能看到第几步"打出来。

用法：
  python3 scripts/trace_live_probe.py --message "我买的咖啡机到哪了？" --user U001
  python3 scripts/trace_live_probe.py --message "我想把耳机退掉" --user U001   # 走写路径

判读：
  - 若在请求返回**之前**就打印出 step>0，说明实时链路通；
  - 若所有 step 都在请求返回后才出现，说明轨迹是在请求结束后才可见的（实时性失效）；
  - 若始终为 0 而请求正常返回，说明 traceId 没对上（服务端另生成了 id）。
"""

import argparse
import json
import os
import socket
import threading
import time
import urllib.request

BASE = "http://127.0.0.1:8081"


def _opener():
    """显式禁用代理。本机设了 HTTP_PROXY，urllib 会把 127.0.0.1 也送去代理。"""
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


OPENER = _opener()


def call(method, path, body=None, timeout=180):
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with OPENER.open(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def new_trace_id():
    return os.urandom(8).hex()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--message", default="我买的咖啡机到哪了？")
    ap.add_argument("--user", default="U001")
    ap.add_argument("--interval", type=float, default=0.4, help="轮询间隔(秒)")
    args = ap.parse_args()

    tid = new_trace_id()
    print(f"traceId = {tid}")
    print(f"message = {args.message}")
    print()

    t0 = time.time()
    result = {}

    def do_chat():
        try:
            result["resp"] = call("POST", "/api/chat",
                                  {"userId": args.user, "message": args.message, "traceId": tid})
        except Exception as e:  # noqa: BLE001
            result["err"] = repr(e)
        result["done_at"] = time.time()

    th = threading.Thread(target=do_chat, daemon=True)
    th.start()

    # 边跑边轮询：把"第几秒看到第几步"逐行打出来
    seen_live_rows = 0
    while th.is_alive():
        el = time.time() - t0
        try:
            t = call("GET", f"/api/trace/{tid}", timeout=10)
            n = t.get("stepCount", 0)
            nodes = [s.get("node") for s in t.get("steps", [])]
            print(f"[{el:6.1f}s] 飞行中  stepCount={n:<2} {nodes}")
            seen_live_rows = max(seen_live_rows, n)
        except Exception as e:  # noqa: BLE001
            print(f"[{el:6.1f}s] 轮询失败 {e!r}")
        time.sleep(args.interval)

    th.join()
    total = result["done_at"] - t0
    print()
    print(f"=== 请求返回，用时 {total:.1f}s ===")

    # 返回后再查一次：这是"最终应有"的步数
    time.sleep(0.3)
    t = call("GET", f"/api/trace/{tid}")
    final_n = t.get("stepCount", 0)
    for s in t.get("steps", []):
        ms = s.get("latency_ms")
        print(f"  step{s.get('step_index')} {s.get('node'):9s} "
              f"{s.get('tool_name') or '-':16s} {s.get('status') or '-':8s} "
              f"{str(ms) + 'ms' if ms is not None else '-':>8}  "
              f"{str(s.get('detail') or '')[:60]}")

    print()
    print("=== 判读 ===")
    print(f"飞行中最多看到 {seen_live_rows} 步；请求结束时共 {final_n} 步。")
    if seen_live_rows > 0:
        print("✅ 实时链路通：请求返回前就能看到已落库的步骤。")
    elif final_n > 0:
        print("❌ 实时性失效：所有步骤都是请求结束后才可见的。")
    else:
        print("❌ 轨迹始终为空：traceId 未对上，或写入整条链路失败。")

    resp = result.get("resp")
    if resp:
        got = (resp.get("trace") or {}).get("traceId")
        print(f"响应里的 traceId = {got}  （{'一致' if got == tid else '不一致 ← 服务端另生成了 id'}）")
    if result.get("err"):
        print("请求异常:", result["err"])


if __name__ == "__main__":
    main()
