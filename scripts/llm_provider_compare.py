"""
对照实验：同一中转站上换模型，看 TTFT 是否同时下降。
目的：区分"慢"是【中转站/供应商的问题】还是【大模型本身的问题】。
如果换小模型 TTFT 不变 -> 慢在中转站，换供应商即可，不必本地部署。
如果 TTFT 显著下降     -> 慢在模型规模/负载，本地独占算力才有意义。
"""
import json
import os
import time
import urllib.request

# 仓库根 = scripts/ 的上一级。从脚本自身位置推导，避免把本机绝对路径写进公开仓库。
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
env = {}
with open(os.path.join(REPO, ".env"), encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")

BASE = env["LLM_BASE_URL"].rstrip("/")
URL = (BASE + "/chat/completions") if BASE.endswith("/v1") else (BASE + "/v1/chat/completions")
MODELS_URL = (BASE + "/models") if BASE.endswith("/v1") else (BASE + "/v1/models")
KEY = env["LLM_API_KEY"]
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def get(url):
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + KEY})
    return OPENER.open(req, timeout=30).read()


print("== 中转站声明的模型（前 40 个）==")
try:
    d = json.loads(get(MODELS_URL))
    ids = [m.get("id") for m in (d.get("data") or [])]
    print("  共 %d 个：" % len(ids))
    for i in range(0, min(len(ids), 40), 4):
        print("   " + "  ".join("%-26s" % x for x in ids[i:i + 4]))
except Exception as e:
    print("  取列表失败:", e)
    ids = []


def timed(model, user, max_tokens=24):
    body = {"model": model, "messages": [{"role": "user", "content": user}],
            "temperature": 0.1, "max_tokens": max_tokens}
    data = json.dumps(body).encode()
    req = urllib.request.Request(URL, data=data, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + KEY})
    t0 = time.perf_counter()
    try:
        raw = OPENER.open(req, timeout=120).read()
    except Exception as e:
        return None, str(e)[:60]
    dt = time.perf_counter() - t0
    try:
        u = json.loads(raw).get("usage") or {}
        return dt, (u.get("completion_tokens"), u.get("prompt_tokens"))
    except Exception:
        return dt, "?"


CAND = []
PREFER = ["glm-5.3-flash", "deepseek-v4.1-flash", "qwen3.8-flash", "deepseek-v4-flash",
          "glm-5.3", "qwen3.7-plus", "kimi-k2.6", "glm-5.2", "deepseek-v4-pro",
          "kimi-k3", "qwen3.8-max", "minimax-m3"]
for want in PREFER:
    if want in ids:
        CAND.append(want)
if not CAND:
    CAND = ids[:6]

print("\n== 同一提问、不同模型，输出上限 24 token（小模型 vs 大模型 vs flash）==")
print("  %-20s %-9s %-9s %s" % ("模型", "第1次", "第2次", "usage(ct,pt)"))
result = {}
for m in CAND[:8]:
    d1, info = timed(m, "只回答一个词：中国的首都是哪里？")
    d2, _ = timed(m, "只回答一个词：中国的首都是哪里？")
    result[m] = (d1, d2)
    print("  %-20s %-9s %-9s %s" % (
        m, ("%.2fs" % d1) if d1 else "失败", ("%.2fs" % d2) if d2 else "失败", info))

print("\n== 同时间窗内排序（注意：跨时间窗不可比，见 TECH_JOURNAL 第 12 条）==")
ok = {m: min(x for x in v if x) for m, v in result.items() if any(v)}
for m, t in sorted(ok.items(), key=lambda kv: kv[1]):
    print("  %-20s %.2fs" % (m, t))

print("\n== 判读 ==")
print("  若各模型 TTFT/总时长都在同一个量级（十几秒）-> 慢在中转站本身，")
print("    换供应商是最便宜的解法，本地部署收益不如换端点。")
print("  若随模型变小而显著下降 -> 慢在模型规模/排队，本地独占算力有意义。")
