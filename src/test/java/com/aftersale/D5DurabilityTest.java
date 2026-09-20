package com.aftersale;

import com.aftersale.domain.PlanEntity;
import com.aftersale.domain.PlanStepEntity;
import com.aftersale.executor.Executor;
import com.aftersale.executor.FaultInjector;
import com.aftersale.executor.PlanStateWriter;
import com.aftersale.executor.StepRunner;
import com.aftersale.repo.PlanRepository;
import com.aftersale.repo.PlanStepRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 崩溃恢复的**持久性**验收。
 *
 * 为什么这个类刻意不加 @Transactional：
 * 其余验收测试都用测试事务保证回滚，但测试事务会把"被测代码提交了什么"一并撤销——
 * 而本类要验证的恰恰是"提交有没有真的发生"。带测试事务测持久性，等于用橡皮擦去证明铅笔写过字。
 * 代价是必须自己清理数据（见 resetOrders/cleanUp）。
 *
 * 本类补的是原实现缺失的那块证据：原来的 resumeAll 测试是手工把 plan 改成 EXECUTING
 * 造出崩溃现场，从未验证过"崩溃后这些东西是否真的还在库里"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class D5DurabilityTest {

    @Autowired Executor executor;
    @Autowired StepRunner stepRunner;
    @Autowired PlanStateWriter planStateWriter;
    @Autowired PlanRepository planRepository;
    @Autowired PlanStepRepository planStepRepository;
    @Autowired FaultInjector faultInjector;
    @Autowired JdbcTemplate jdbc;

    private static final String U1 = "U001";
    private static final String ORDER_A = "ORD20260901001";
    private static final String ORDER_B = "ORD202609150011";

    /** 本类创建的 plan id，用于精确回收 */
    private final java.util.List<Long> createdPlanIds = new java.util.ArrayList<>();

    @BeforeEach
    void resetOrders() {
        jdbc.update("UPDATE orders SET status='PAID' WHERE order_no IN (?,?)", ORDER_A, ORDER_B);
    }

    @AfterEach
    void cleanUp() {
        // 本类不开测试事务，写入是真落库的，必须按 plan id 精确回收（不对库里其他数据做全局删除）
        for (Long planId : createdPlanIds) {
            jdbc.update("DELETE FROM execution_log WHERE plan_id=?", planId);
            jdbc.update("DELETE FROM idempotency_keys WHERE plan_id=?", planId);
            jdbc.update("DELETE FROM plan_steps WHERE plan_id=?", planId);
            jdbc.update("DELETE FROM plans WHERE id=?", planId);
        }
        createdPlanIds.clear();
        jdbc.update("UPDATE orders SET status='PAID' WHERE order_no IN (?,?)", ORDER_A, ORDER_B);
    }

    /** 造一个两步、已确认的取消计划（不依赖 LLM）；登记 id 供回收 */
    private PlanEntity seedConfirmedTwoStepPlan(String tag) {
        jdbc.update("INSERT INTO plans (conversation_id, user_id, order_no, summary, estimated_amount_cents, "
                        + "context_fingerprint, status, second_confirm_required, confirmed_at) "
                        + "VALUES (1,?,?,?,0,'durability-fp','CONFIRMED',0,NOW())",
                U1, ORDER_A, "持久性验收：" + tag);
        Long planId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        createdPlanIds.add(planId);
        jdbc.update("INSERT INTO plan_steps (plan_id, seq, tool_name, args_json, risk_level, status, attempt) "
                        + "VALUES (?,0,'cancelOrder',?, 'WRITE','PENDING',0)",
                planId, "{\"orderNo\":\"" + ORDER_A + "\",\"userId\":\"" + U1 + "\",\"reason\":\"持久性验收\"}");
        jdbc.update("INSERT INTO plan_steps (plan_id, seq, tool_name, args_json, risk_level, status, attempt) "
                        + "VALUES (?,1,'cancelOrder',?, 'WRITE','PENDING',0)",
                planId, "{\"orderNo\":\"" + ORDER_B + "\",\"userId\":\"" + U1 + "\",\"reason\":\"持久性验收\"}");
        return planRepository.findById(planId).orElseThrow();
    }

    private long logCount(Long planId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM execution_log WHERE plan_id=?", Long.class, planId);
    }

    private long logCountForStep(Long planId, int seq) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM execution_log l JOIN plan_steps s ON s.id = l.step_id "
                + "WHERE l.plan_id=? AND s.seq=?", Long.class, planId, seq);
    }

    private long idemCount(Long planId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE plan_id=?", Long.class, planId);
    }

    private String stepStatus(Long planId, int seq) {
        return jdbc.queryForObject("SELECT status FROM plan_steps WHERE plan_id=? AND seq=?", String.class,
                planId, seq);
    }

    /**
     * 核心断言：计划**还在执行中**的时候，已完成步骤的进度就已经能从另一个连接看到了。
     *
     * 这一条等价于"进程此刻被 kill -9，进度也不会丢"——因为它已经是提交过的数据。
     * 在整改前，整个计划共用一个事务，此处必然看不到任何东西。
     */
    @Test
    void stepProgressIsDurableWhilePlanIsStillRunning() throws Exception {
        PlanEntity plan = seedConfirmedTwoStepPlan("执行中进度可见");
        // 第 2 步（seq=1）真实阻塞 4 秒，制造一个"第 1 步已完成、第 2 步进行中"的窗口
        faultInjector.injectAt(plan.id, 1, FaultInjector.Mode.HANG, 4000);

        AtomicReference<Executor.ExecuteResult> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(executor.execute(plan.id, U1)), "durability-worker");
        worker.start();

        boolean observed = false;
        String observedStatus = null;
        String observedStep0 = null;
        long observedLogs = -1;
        long observedKeys = -1;
        long deadline = System.currentTimeMillis() + 3500;
        while (System.currentTimeMillis() < deadline) {
            observedStatus = planStateWriter.status(plan.id);
            observedStep0 = stepStatus(plan.id, 0);
            observedLogs = logCount(plan.id);
            observedKeys = idemCount(plan.id);
            if ("EXECUTING".equals(observedStatus) && "SUCCESS".equals(observedStep0)
                    && observedLogs >= 1 && observedKeys >= 1) {
                observed = true;
                break;
            }
            Thread.sleep(50);
        }

        assertTrue(observed, String.format(
                "计划仍在执行中时，第 1 步的进度就应该已提交（等价于崩溃不丢进度）。"
                        + "实际观察到 plan.status=%s step0=%s execution_log=%d idempotency_keys=%d",
                observedStatus, observedStep0, observedLogs, observedKeys));

        // 此刻第 2 步还在阻塞中，说明我们确实是在"计划未结束"时观察到的
        assertNotEquals("COMPLETED", planStateWriter.status(plan.id),
                "观察到进度时计划不应已经结束，否则不能证明'执行中落盘'");

        worker.join(15000);
        assertFalse(worker.isAlive(), "执行线程应在阻塞窗口结束后退出");
    }

    /**
     * 崩溃后重启续跑：已完成的步骤必须被跳过，而不是从头再执行一遍。
     * 这里用"先单独提交第 0 步 + 把计划置为 EXECUTING"来复现崩溃现场，
     * 与真实 kill -9 留下的库内状态完全一致（这正是整改后 kill -9 实验能观察到的东西）。
     */
    @Test
    void resumeSkipsAlreadyCommittedStep() {
        PlanEntity plan = seedConfirmedTwoStepPlan("续跑不重跑");
        PlanStepEntity step0 = planStepRepository.findByPlanIdOrderBySeq(plan.id).get(0);

        // 复现崩溃现场：第 0 步已提交成功，计划停在 EXECUTING，第 1 步未开始
        StepRunner.Outcome first = stepRunner.attempt(plan.id, step0.id, 0);
        assertEquals("SUCCESS", first.status());
        planStateWriter.markExecuting(plan.id);
        assertEquals(1, logCountForStep(plan.id, 0), "第 0 步应留下恰好 1 条执行记录");
        assertEquals(0, logCountForStep(plan.id, 1), "第 1 步崩溃前未开始被执行");

        // 重启后恢复
        int resumed = executor.resumeAll();
        assertEquals(1, resumed, "应恢复 1 个中断计划");
        assertEquals("COMPLETED", planStateWriter.status(plan.id));

        // 关键断言：第 0 步没有被重复执行（这正是崩溃后最危险的情况——重复退款/重复取消）
        assertEquals(1, logCountForStep(plan.id, 0),
                "续跑不应为已完成的步骤产生新的执行记录（否则就是重复退款/重复取消）");
        assertEquals(1, logCountForStep(plan.id, 1), "第 1 步应在续跑中被执行一次");
        assertEquals("SUCCESS", stepStatus(plan.id, 0));
        assertEquals("SUCCESS", stepStatus(plan.id, 1));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM orders WHERE order_no=?", String.class, ORDER_A));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM orders WHERE order_no=?", String.class, ORDER_B));
    }
}
