#!/usr/bin/env python3
"""τ-bench retail 跑分子集（自定义驱动，绕开官方 run.py 的 cost 计算与 gpt-4o 默认模拟器）。

依赖 sierra 官方 tau-bench 源码。三种提供方式，任选一种：
  1) --tau-bench-root /path/to/tau-bench
  2) export TAU_BENCH_ROOT=/path/to/tau-bench
  3) pip install -e /path/to/tau-bench（装进当前环境，无需指定路径）

获取源码：
  git clone https://github.com/sierra-research/tau-bench

用法:
  export TAU_BENCH_ROOT=/path/to/tau-bench
  OPENAI_API_KEY=... OPENAI_API_BASE=... python3 tau_run.py --n 10 --start 0

  --n:     跑前 n 个 test 任务（默认 10）
  --start: 起始任务下标（默认 0）
  --out-dir: 报告输出目录（默认当前目录）
"""
import argparse
import json
import os
import sys
import time

MODEL = os.environ.get("TAU_MODEL", "openai/glm-5.3")


def resolve_tau_bench_root(cli_value):
    """返回需要插入 sys.path 的目录；已可 import 时返回 None。

    不做静默兜底：找不到就直接报错并给出获取方式，比默认某个 /tmp 路径更可控
    （默认路径会让"跑起来"和"跑得对"变成两件事）。
    """
    root = cli_value or os.environ.get("TAU_BENCH_ROOT")
    if root:
        if not os.path.isdir(root):
            raise SystemExit(f"tau-bench 目录不存在: {root}")
        return root
    try:
        import tau_bench  # noqa: F401
        return None
    except ImportError:
        pass
    raise SystemExit(
        "找不到 tau-bench。请任选一种方式：\n"
        "  1) --tau-bench-root /path/to/tau-bench\n"
        "  2) export TAU_BENCH_ROOT=/path/to/tau-bench\n"
        "  3) pip install -e /path/to/tau-bench\n"
        "获取源码：git clone https://github.com/sierra-research/tau-bench"
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=10)
    ap.add_argument("--start", type=int, default=0)
    ap.add_argument("--tau-bench-root", default=None,
                    help="tau-bench 源码目录；也可用环境变量 TAU_BENCH_ROOT")
    ap.add_argument("--out-dir", default=".", help="报告输出目录")
    args = ap.parse_args()

    root = resolve_tau_bench_root(args.tau_bench_root)
    if root:
        sys.path.insert(0, os.path.abspath(root))
    os.environ.setdefault("LITELLM_LOCAL_MODEL_COST_MAP", "True")
    os.environ.setdefault("LITELLM_LOG", "WARNING")

    from tau_bench.agents.tool_calling_agent import ToolCallingAgent
    from tau_bench.envs.retail.env import MockRetailDomainEnv
    from tau_bench.envs.retail.tools import ALL_TOOLS
    from tau_bench.envs.retail.wiki import WIKI

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

    os.makedirs(args.out_dir, exist_ok=True)
    out = os.path.join(args.out_dir, f"tau_retail_{args.start}_{args.start + args.n}.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"model": MODEL, "results": results}, f, ensure_ascii=False, indent=2)
    print(f"报告: {out}")


if __name__ == "__main__":
    main()
