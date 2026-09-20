package com.aftersale.executor;

import com.aftersale.domain.PlanEntity;
import com.aftersale.repo.PlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 计划状态迁移的落盘者。
 *
 * 为什么单独一个 Bean：状态迁移必须**独立提交**。
 * 原实现把 `plan.status = "EXECUTING"` 和整个执行过程放在同一个事务里，
 * 于是崩溃后连"这个计划正在执行"这个事实都回滚掉了——
 * resume() 扫描的是 EXECUTING 计划，扫不到任何东西，续跑也就无从谈起。
 *
 * 崩溃恢复的第一前提不是"能重跑"，而是**"崩溃之后还知道有事没做完"**。
 * 这个类就是保证后面那半句。
 */
@Service
public class PlanStateWriter {

    private final PlanRepository planRepository;

    public PlanStateWriter(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    /** 置为 EXECUTING，并确认当前状态允许进入执行（防止并发把已终态的计划改回执行中） */
    @Transactional(propagation = Propagation.REQUIRED)
    public void markExecuting(Long planId) {
        planRepository.findById(planId).ifPresent(plan -> {
            if (List.of("CONFIRMED", "EXECUTING").contains(plan.status)) {
                plan.status = "EXECUTING";
                planRepository.save(plan);
            }
        });
    }

    /** 置为终态（COMPLETED / FAILED） */
    @Transactional(propagation = Propagation.REQUIRED)
    public void mark(Long planId, String status) {
        planRepository.findById(planId).ifPresent(plan -> {
            plan.status = status;
            planRepository.save(plan);
        });
    }

    /** 读取当前状态（独立短事务，避免读到别的会话未提交的中间态） */
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public String status(Long planId) {
        return planRepository.findById(planId).map(p -> p.status).orElse(null);
    }
}
