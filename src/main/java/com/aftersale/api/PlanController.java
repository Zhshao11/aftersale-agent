package com.aftersale.api;

import com.aftersale.agent.PlanManager;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private final PlanManager planManager;

    public PlanController(PlanManager planManager) {
        this.planManager = planManager;
    }

    /** 查询 Plan（含步骤状态），用于前端渲染确认卡片 */
    @GetMapping("/{id}")
    public Object get(@PathVariable Long id, @RequestParam String userId) {
        return planManager.view(id, userId)
                .<Object>map(p -> p)
                .orElseGet(() -> Map.of("error", "NOT_FOUND", "message", "计划不存在"));
    }

    /** 确认：POST /api/plan/{id}/confirm?userId=U001（金额≥$500 需调两次） */
    @PostMapping("/{id}/confirm")
    public PlanManager.ConfirmResult confirm(@PathVariable Long id, @RequestParam String userId) {
        return planManager.confirm(id, userId);
    }

    /** 拒绝：POST /api/plan/{id}/reject?userId=U001 */
    @PostMapping("/{id}/reject")
    public PlanManager.ConfirmResult reject(@PathVariable Long id, @RequestParam String userId) {
        return planManager.reject(id, userId);
    }
}
