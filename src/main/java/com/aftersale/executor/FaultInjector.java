package com.aftersale.executor;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 故障注入器（仅测试/评测用）：
 *  FAIL_BEFORE_SEND —— 模拟请求未发出即失败（明确失败，可安全重试）
 *  TIMEOUT_UNKNOWN   —— 模拟请求已发出但无响应（结果未知，禁止盲目重试）
 *  HANG              —— 真实阻塞 N 毫秒，模拟下游长时间无响应（可被外部 kill 打断，用于崩溃恢复实验）
 *
 * 注入分两类：
 *  - 通用注入（inject）：下一次工具执行即生效，不限目标
 *  - 定向注入（injectAt）：只在指定 plan + step 上生效
 *
 * 定向注入存在的理由：崩溃恢复实验需要"前一步已执行完、下一步卡住"的现场。
 * 通用注入只有一次性语义，无法指定"卡在第二步"，所以必须支持按目标匹配。
 */
@Component
public class FaultInjector {

    public enum Mode { NONE, FAIL_BEFORE_SEND, TIMEOUT_UNKNOWN, HANG }

    /** 一次待生效的注入。planId / stepSeq 为 null 表示不限定目标。 */
    public record Injection(Mode mode, Long planId, Integer stepSeq, long hangMs) {
        public static final Injection NONE = new Injection(Mode.NONE, null, null, 0);
    }

    private final AtomicReference<Injection> pending = new AtomicReference<>(Injection.NONE);

    private static final ThreadLocal<Mode> HOLDER = new ThreadLocal<>();

    public static void setStatic(Mode mode) {
        // 保留给同线程测试场景；HTTP 场景走 inject()
        HOLDER.set(mode);
    }

    /** 通用注入：下一次工具执行生效（不限 plan / step） */
    public void inject(Mode mode) {
        pending.set(new Injection(mode, null, null, 0));
    }

    /** 定向注入：只在指定 plan 的指定 step 上生效。hangMs 仅 HANG 模式使用 */
    public void injectAt(Long planId, Integer stepSeq, Mode mode, long hangMs) {
        pending.set(new Injection(mode, planId, stepSeq, hangMs));
    }

    /** 取走并清除（一次性；线程本地优先，其次全局） */
    public Mode takeOnce() {
        Mode local = HOLDER.get();
        if (local != null) {
            HOLDER.remove();
            if (local != Mode.NONE) {
                return local;
            }
        }
        Injection cur = pending.get();
        if (cur.mode() == Mode.NONE) {
            return Mode.NONE;
        }
        // 定向注入不在通用通道消费，否则会被第一个执行的步骤误吃掉
        if (cur.planId() != null || cur.stepSeq() != null) {
            return Mode.NONE;
        }
        return pending.compareAndSet(cur, Injection.NONE) ? cur.mode() : Mode.NONE;
    }

    /** 按执行目标取走：定向注入命中则消费，未命中回退通用通道 */
    public Injection takeFor(Long planId, int stepSeq) {
        Mode local = HOLDER.get();
        if (local != null) {
            HOLDER.remove();
            if (local != Mode.NONE) {
                return new Injection(local, null, null, 0);
            }
        }
        Injection cur = pending.get();
        if (cur.mode() != Mode.NONE
                && (cur.planId() == null || cur.planId().equals(planId))
                && (cur.stepSeq() == null || cur.stepSeq() == stepSeq)) {
            if (pending.compareAndSet(cur, Injection.NONE)) {
                return cur;
            }
        }
        return Injection.NONE;
    }
}
