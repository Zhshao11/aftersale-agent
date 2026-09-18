#!/usr/bin/env python3
"""τ-bench retail 跑分子集（自定义驱动，绕开官方 run.py 的 cost 计算与 gpt-4o 默认模拟器）。

用法:
  OPENAI_API_KEY=... OPENAI_API_BASE=... python3 tau_run.py --n 10 --start 0
  --n: 跑前 n 个 test 任务（默认 10）; --start: 起始任务下标（默认 0）
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, "/tmp/tau-bench")
os.environ.setdefault("LITELLM_LOCAL_MODEL_COST_MAP", "True")
os.environ.setdefault("LITELLM_LOG", "WARNING")

from tau_bench.agents.tool_calling_agent import ToolCallingAgent  # noqa: E402
from tau_bench.envs.retail.env import MockRetailDomainEnv  # noqa: E402
from tau_bench.envs.retail.tools import ALL_TOOLS  # noqa: E402
from tau_bench.envs.retail.wiki import WIKI  # noqa: E402

MODEL = "openai/glm-5.3"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=10)
    ap.add_argument("--start", type=int, default=0)
    args = ap.parse_args()

    # 官方 tool-calling agent 的 cost 读取会 KeyError，monkey-patch 掉
    orig_completion = __import__("litellm").completion

    results = []
    env = MockRetailDomainEnv(
        user_strategy="llm",
        user_model=MODEL,
        user_provider="openai",
        task_split="test",
    )
    tools_info = [t.get_info() for t in ALL_TOOLS]
    agent = ToolCallingAgent(tools_info=tools_info, wiki=WIKI, model=MODEL,
                             provider="openai", temperature=0.0)

    total_t0 = time.time()
    for i in range(args.start, args.start + args.n):
        t0 = time.time()
        try:
            r = agent.solve(env, task_index=i, max_num_steps=30)
            reward = r.reward
            reason = "ok"
        except Exception as e:
            reward = 0.0
            reason = f"异常: {type(e).__name__}: {str(e)[:120]}"
        results.append({"task_index": i, "reward": reward, "reason": reason,
                        "latency": round(time.time() - t0, 1)})
        mark = "PASS" if reward > 0 else "FAIL"
        print(f"[task {i}] {mark} ({results[-1]['latency']}s) {reason}")

    passed = sum(1 for r in results if r["reward"] > 0)
    print(f"\nτ-bench retail subset: {passed}/{len(results)} = "
          f"{passed / len(results) * 100:.1f}%  总耗时 {round(time.time() - total_t0)}s")

    out = f"tau_retail_{args.start}_{args.start + args.n}.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"model": MODEL, "results": results}, f, ensure_ascii=False, indent=2)
    print(f"报告: {out}")


if __name__ == "__main__":
    main()
