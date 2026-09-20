package com.aftersale.api;

import com.aftersale.executor.Executor;
import com.aftersale.executor.FaultInjector;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ExecuteController {

    private final Executor executor;
    private final FaultInjector faultInjector;

    public ExecuteController(Executor executor, FaultInjector faultInjector) {
        this.executor = executor;
        this.faultInjector = faultInjector;
    }

    /** 执行已确认的 Plan：POST /api/plan/{id}/execute?userId=U001（幂等，可重入） */
    @PostMapping("/plan/{id}/execute")
    public Executor.ExecuteResult execute(@PathVariable Long id, @RequestParam String userId) {
        return executor.execute(id, userId);
    }

    /** 断点续跑（应用重启后调用）：POST /api/executor/resume */
    @PostMapping("/executor/resume")
    public java.util.Map<String, Object> resume() {
        int n = executor.resumeAll();
        return java.util.Map.of("resumed", n);
    }

    /**
     * 故障注入（仅评测）：POST /api/fault?mode=TIMEOUT_UNKNOWN —— 对下一次工具执行生效一次。
     * 定向注入（用于崩溃恢复实验）：POST /api/fault?mode=HANG&planId=1&stepSeq=1&hangMs=60000
     */
    @PostMapping("/fault")
    public java.util.Map<String, Object> fault(@RequestParam String mode,
                                               @RequestParam(required = false) Long planId,
                                               @RequestParam(required = false) Integer stepSeq,
                                               @RequestParam(defaultValue = "0") long hangMs) {
        FaultInjector.Mode m = FaultInjector.Mode.valueOf(mode);
        if (planId != null || stepSeq != null) {
            faultInjector.injectAt(planId, stepSeq, m, hangMs);
        } else {
            faultInjector.inject(m);
        }
        java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("injected", m.name());
        r.put("planId", planId);
        r.put("stepSeq", stepSeq);
        r.put("hangMs", hangMs);
        return r;
    }
}
