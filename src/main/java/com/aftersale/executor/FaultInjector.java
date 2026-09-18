package com.aftersale.executor;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 故障注入器（仅测试/评测用）：
 *  FAIL_BEFORE_SEND —— 模拟请求未发出即失败（明确失败，可安全重试）
 *  TIMEOUT_UNKNOWN   —— 模拟请求已发出但无响应（结果未知，禁止盲目重试）
 * 全局一次性消费：/api/fault 设置后，对下一次工具执行生效（跨请求线程）。
 */
@Component
public class FaultInjector {

    public enum Mode { NONE, FAIL_BEFORE_SEND, TIMEOUT_UNKNOWN }

    private final AtomicReference<Mode> pending = new AtomicReference<>(Mode.NONE);

    public static void setStatic(Mode mode) {
        // 保留给同线程测试场景；HTTP 场景走 inject()
        HOLDER.set(mode);
    }

    private static final ThreadLocal<Mode> HOLDER = new ThreadLocal<>();

    public void inject(Mode mode) {
        pending.set(mode);
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
        return pending.getAndSet(Mode.NONE);
    }
}
