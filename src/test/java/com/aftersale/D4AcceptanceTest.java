package com.aftersale;

import com.aftersale.agent.PlanManager;
import com.aftersale.agent.PlanService;
import com.aftersale.domain.ExecutionLogEntity;
import com.aftersale.domain.IdempotencyKeyEntity;
import com.aftersale.domain.OrderEntity;
import com.aftersale.domain.PlanEntity;
import com.aftersale.domain.PlanStepEntity;
import com.aftersale.enums.OrderStatus;
import com.aftersale.executor.Executor;
import com.aftersale.executor.FaultInjector;
import com.aftersale.repo.ExecutionLogRepository;
import com.aftersale.repo.IdempotencyKeyRepository;
import com.aftersale.repo.OrderRepository;
import com.aftersale.repo.PlanRepository;
import com.aftersale.repo.PlanStepRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class D4AcceptanceTest {

    @Autowired Executor executor;
    @Autowired PlanManager planManager;
    @Autowired PlanRepository planRepository;
    @Autowired PlanStepRepository planStepRepository;
    @Autowired ExecutionLogRepository executionLogRepository;
    @Autowired IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired OrderRepository orderRepository;

    private static final String U1 = "U001";

    /** 建一个已确认的取消计划（不依赖 LLM） */
    private PlanEntity confirmedCancelPlan(String orderNo) {
        OrderEntity order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        PlanEntity plan = new PlanEntity();
        plan.conversationId = 1L;
        plan.userId = U1;
        plan.orderNo = orderNo;
        plan.summary = "取消订单 " + orderNo;
        plan.estimatedAmountCents = null;
        plan.contextFingerprint = PlanService.fingerprintOf(order,
                new com.aftersale.agent.PlanGenerator.GeneratedPlan("s", orderNo,
                        List.of(new com.aftersale.agent.PlanGenerator.GeneratedPlan.Step("cancelOrder", "r"))));
        plan.status = "PENDING_CONFIRM";
        plan.secondConfirmRequired = false;
        plan = planRepository.saveAndFlush(plan);

        PlanStepEntity step = new PlanStepEntity();
        step.planId = plan.id;
        step.seq = 0;
        step.toolName = "cancelOrder";
        step.argsJson = "{\"orderNo\":\"" + orderNo + "\",\"userId\":\"" + U1 + "\",\"reason\":\"不想要了\"}";
        step.riskLevel = "WRITE";
        step.status = "PENDING";
        step.attempt = 0;
        planStepRepository.saveAndFlush(step);

        planManager.confirm(plan.id, U1);
        return planRepository.findById(plan.id).orElseThrow();
    }

    @Test
    void happyPath_execute_cancelConfirmed() {
        PlanEntity plan = confirmedCancelPlan("ORD20260901001");
        Executor.ExecuteResult r = executor.execute(plan.id, U1);

        assertEquals("COMPLETED", r.planStatus(), r.message());
        assertEquals("CANCELLED",
                orderRepository.findByOrderNo("ORD20260901001").orElseThrow().getStatus().name());
        assertEquals("SUCCESS",
                planStepRepository.findByPlanIdOrderBySeq(plan.id).get(0).status);
        // execution_log 记录成功 + 耗时
        List<ExecutionLogEntity> logs = executionLogRepository.findByPlanIdOrderByAttemptAscIdAsc(plan.id);
        assertEquals(1, logs.size());
        assertEquals("SUCCESS", logs.get(0).status);
        assertNotNull(logs.get(0).latencyMs);
        assertEquals(plan.id + ":" + logs.get(0).stepId + ":0", logs.get(0).idempotencyKey);
    }

    @Test
    void idempotentReentry_noDoubleExecution() {
        PlanEntity plan = confirmedCancelPlan("ORD20260901001");
        Executor.ExecuteResult first = executor.execute(plan.id, U1);
        assertEquals("COMPLETED", first.planStatus());

        // 第二次执行同一 Plan：幂等重入，不重复执行
        Executor.ExecuteResult second = executor.execute(plan.id, U1);
        assertTrue(second.planStatus().equals("COMPLETED"));
        // execution_log 不应有第二条真实执行记录
        List<ExecutionLogEntity> logs = executionLogRepository.findByPlanIdOrderByAttemptAscIdAsc(plan.id);
        assertEquals(1, logs.size(), "重复执行不应产生新的执行日志");
    }

    @Test
    void conflict_replaysRecordedSuccess() {
        PlanEntity plan = confirmedCancelPlan("ORD20260901001");
        PlanStepEntity step = planStepRepository.findByPlanIdOrderBySeq(plan.id).get(0);

        // 预置：上次 attempt=0 已成功占用幂等键
        IdempotencyKeyEntity rec = new IdempotencyKeyEntity();
        rec.idempotencyKey = plan.id + ":" + step.id + ":0";
        rec.planId = plan.id;
        rec.stepId = step.id;
        rec.attempt = 0;
        rec.resultStatus = "SUCCESS";
        rec.resultJson = "{\"orderNo\":\"ORD20260901001\",\"newStatus\":\"CANCELLED\"}";
        idempotencyKeyRepository.saveAndFlush(rec);
        // 模拟上次中断：订单确实已被取消
        orderRepository.findByOrderNo("ORD20260901001").orElseThrow().setStatus(OrderStatus.CANCELLED);
        orderRepository.saveAndFlush(orderRepository.findByOrderNo("ORD20260901001").orElseThrow());

        Executor.ExecuteResult r = executor.execute(plan.id, U1);
        assertEquals("COMPLETED", r.planStatus(), "幂等冲突应回放上次成功结果而不是重复执行");
    }

    @Test
    void failBeforeSend_retriesWithNewAttempt_thenSucceeds() {
        PlanEntity plan = confirmedCancelPlan("ORD20260901001");
        FaultInjector.setStatic(FaultInjector.Mode.FAIL_BEFORE_SEND);

        Executor.ExecuteResult r = executor.execute(plan.id, U1);
        assertEquals("COMPLETED", r.planStatus(), "明确失败应换新 attempt 重试成功: " + r.message());

        PlanStepEntity step = planStepRepository.findByPlanIdOrderBySeq(plan.id).get(0);
        assertEquals(1, step.attempt, "第二次尝试应使用 attempt=1");
        assertEquals("SUCCESS", step.status);

        // 日志：FAILED(attempt=0) + SUCCESS(attempt=1)
        List<ExecutionLogEntity> logs = executionLogRepository.findByPlanIdOrderByAttemptAscIdAsc(plan.id);
        assertEquals(2, logs.size());
        assertEquals("FAILED", logs.get(0).status);
        assertEquals("INJECTED_FAIL", logs.get(0).errorCode);
        assertEquals("SUCCESS", logs.get(1).status);
        assertEquals("CANCELLED",
                orderRepository.findByOrderNo("ORD20260901001").orElseThrow().getStatus().name());
    }

    @Test
    void timeoutUnknown_reconcileSuccess_whenOrderAlreadyChanged() {
        PlanEntity plan = confirmedCancelPlan("ORD20260901001");
        // 模拟：请求实际已生效（订单被取消），但响应超时
        orderRepository.findByOrderNo("ORD20260901001").orElseThrow().setStatus(OrderStatus.CANCELLED);
        orderRepository.saveAndFlush(orderRepository.findByOrderNo("ORD20260901001").orElseThrow());
        FaultInjector.setStatic(FaultInjector.Mode.TIMEOUT_UNKNOWN);

        Executor.ExecuteResult r = executor.execute(plan.id, U1);
        assertEquals("COMPLETED", r.planStatus(), "对账发现订单已到终态，应判成功");

        List<ExecutionLogEntity> logs = executionLogRepository.findByPlanIdOrderByAttemptAscIdAsc(plan.id);
        assertEquals(2, logs.size());
        assertEquals("UNKNOWN", logs.get(0).status);
        assertEquals("SUCCESS", logs.get(1).status);
        assertEquals("RECONCILED", logs.get(1).errorCode);
    }

    @Test
    void timeoutUnknown_noBlindRetry_marksForCompensation() {
        PlanEntity plan = confirmedCancelPlan("ORD20260901001");
        FaultInjector.setStatic(FaultInjector.Mode.TIMEOUT_UNKNOWN);

        Executor.ExecuteResult r = executor.execute(plan.id, U1);
        assertEquals("FAILED", r.planStatus(), "结果未知且对账未通过应标记失败待补偿");
        assertTrue(r.message().contains("禁止盲目重试"));

        // 关键断言：没有换 attempt 重试（attempt 保持 0）
        PlanStepEntity step = planStepRepository.findByPlanIdOrderBySeq(plan.id).get(0);
        assertEquals(0, step.attempt, "结果未知禁止盲目重试，attempt 不应增加");
        assertEquals("UNKNOWN", step.status);

        // 日志：UNKNOWN 一次 + 对账失败；无第二次执行
        List<ExecutionLogEntity> logs = executionLogRepository.findByPlanIdOrderByAttemptAscIdAsc(plan.id);
        assertEquals(2, logs.size());
        assertEquals("UNKNOWN", logs.get(0).status);
        assertEquals("UNKNOWN", logs.get(1).status);
        // 订单未被实际取消
        assertEquals(OrderStatus.PAID,
                orderRepository.findByOrderNo("ORD20260901001").orElseThrow().getStatus());
    }

    @Test
    void businessRejection_noBlindRetry() {
        // 已发货订单的取消计划（政策会在工具层拒绝）——直接构造已确认计划
        PlanEntity plan = confirmedCancelPlan("ORD202609050002"); // SHIPPED
        Executor.ExecuteResult r = executor.execute(plan.id, U1);

        assertEquals("FAILED", r.planStatus());
        PlanStepEntity step = planStepRepository.findByPlanIdOrderBySeq(plan.id).get(0);
        assertEquals(0, step.attempt, "业务拒绝（政策）不应触发盲目重试");
        assertEquals("FAILED", step.status);
    }

    @Test
    void resumeAll_continuesInterruptedPlan() {
        PlanEntity plan = confirmedCancelPlan("ORD20260901001");
        // 模拟崩溃现场：计划执行中、步骤仍 PENDING
        plan.status = "EXECUTING";
        planRepository.saveAndFlush(plan);

        int resumed = executor.resumeAll();
        assertEquals(1, resumed);
        assertEquals("COMPLETED", planRepository.findById(plan.id).orElseThrow().status);
        assertEquals("CANCELLED",
                orderRepository.findByOrderNo("ORD20260901001").orElseThrow().getStatus().name());
    }

    @Test
    void execute_guardChecks() {
        assertEquals("NOT_FOUND", executor.execute(99999L, U1).planStatus());
        PlanEntity plan = confirmedCancelPlan("ORD20260901001");
        assertEquals("FORBIDDEN", executor.execute(plan.id, "U002").planStatus());
        // 未确认的 Plan 不可执行
        OrderEntity o = orderRepository.findByOrderNo("ORD202609020008").orElseThrow();
        PlanEntity pending = new PlanEntity();
        pending.conversationId = 1L;
        pending.userId = U1;
        pending.orderNo = "ORD202609020008";
        pending.summary = "s";
        pending.contextFingerprint = "f";
        pending.status = "PENDING_CONFIRM";
        pending.secondConfirmRequired = false;
        pending = planRepository.saveAndFlush(pending);
        assertEquals("PENDING_CONFIRM", executor.execute(pending.id, U1).planStatus(),
                "未经确认的 Plan 必须拒绝执行");
    }
}
