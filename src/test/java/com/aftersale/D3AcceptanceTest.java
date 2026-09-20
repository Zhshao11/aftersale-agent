package com.aftersale;

import com.aftersale.agent.AgentPropsProvider;
import com.aftersale.agent.LlmPort;
import com.aftersale.agent.PlanGenerator;
import com.aftersale.agent.PlanManager;
import com.aftersale.agent.PlanService;
import com.aftersale.domain.OrderEntity;
import com.aftersale.domain.PlanEntity;
import com.aftersale.domain.PlanStepEntity;
import com.aftersale.enums.OrderStatus;
import com.aftersale.repo.OrderRepository;
import com.aftersale.repo.PlanRepository;
import com.aftersale.repo.PlanStepRepository;
import com.aftersale.tools.write.CancelOrderTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class D3AcceptanceTest {

    /** 固定回复的 LLM 桩：无需 API key 即可测全链路 */
    @TestConfiguration
    static class StubLlmConfig {
        @Bean @Primary
        LlmPort stubLlm() {
            return new LlmPort() {
                @Override
                public String complete(String systemPrompt, List<org.springframework.ai.chat.messages.Message> history,
                                       String userMessage) {
                    if (systemPrompt.contains("规划器")) {
                        // 回显用户消息中的订单号（模拟 LLM 忠实规划）
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("ORD\\d{6,}").matcher(userMessage);
                        String orderNo = m.find() ? m.group() : "ORD20260901001";
                        return "{\"summary\":\"取消订单 " + orderNo + "\",\"orderNo\":\"" + orderNo + "\","
                                + "\"steps\":[{\"tool\":\"cancelOrder\",\"reason\":\"用户不想要了\"}]}";
                    }
                    return "{\"intent\":\"QUERY\",\"request\":\"查询\"}";
                }
                @Override
                public String completeWithTools(String systemPrompt, List<org.springframework.ai.chat.messages.Message> history,
                                                String userMessage, Object toolBundle,
                                                java.util.Map<String, Object> toolContext) {
                    return "stub-reply";
                }
                @Override
                public org.springframework.ai.chat.model.ChatResponse callOnce(
                        String systemPrompt, List<org.springframework.ai.chat.messages.Message> messages,
                        Object toolBundle, java.util.Map<String, Object> toolContext) {
                    // 只读循环的桩：返回一个不含工具调用的终止答复。
                    // 返回文本而不是抛异常，是为了让 D3/D4 万一走到只读路径时也能正常收尾，
                    // 不把"只测写路径"的用例变成因桩不全而失败。
                    var message = org.springframework.ai.chat.messages.AssistantMessage.builder()
                            .content("stub-reply").build();
                    return new org.springframework.ai.chat.model.ChatResponse(
                            java.util.List.of(new org.springframework.ai.chat.model.Generation(message)));
                }
            };
        }
    }

    @Autowired PlanService planService;
    @Autowired PlanManager planManager;
    @Autowired PlanRepository planRepository;
    @Autowired PlanStepRepository planStepRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired CancelOrderTool cancelOrderTool;
    @Autowired AgentPropsProvider props;

    private static final String U1 = "U001";

    // ---------- PlanGenerator 纯函数 ----------

    @Test
    void planParse_valid() {
        PlanGenerator.GeneratedPlan p = PlanGenerator.parse(
                "{\"summary\":\"取消订单\",\"orderNo\":\"ORD1\",\"steps\":[{\"tool\":\"cancelOrder\",\"reason\":\"不想要\"}]}");
        assertEquals("ORD1", p.orderNo());
        assertEquals(1, p.steps().size());
        assertEquals("cancelOrder", p.steps().get(0).tool());
    }

    @Test
    void planParse_codeFenceWrapped() {
        PlanGenerator.GeneratedPlan p = PlanGenerator.parse(
                "```json\n{\"summary\":\"s\",\"orderNo\":\"ORD1\",\"steps\":[{\"tool\":\"refundOrder\",\"reason\":\"r\"}]}\n```");
        assertEquals("refundOrder", p.steps().get(0).tool());
    }

    @Test
    void planParse_nonWhitelistedToolRejected() {
        assertThrows(PlanGenerator.PlanParseException.class, () ->
                PlanGenerator.parse("{\"summary\":\"s\",\"orderNo\":\"ORD1\","
                        + "\"steps\":[{\"tool\":\"deleteDatabase\",\"reason\":\"r\"}]}"));
    }

    @Test
    void planParse_emptyStepsOrMissingOrderRejected() {
        assertThrows(PlanGenerator.PlanParseException.class, () ->
                PlanGenerator.parse("{\"summary\":\"s\",\"orderNo\":\"\",\"steps\":[{\"tool\":\"cancelOrder\",\"reason\":\"r\"}]}"));
        assertThrows(PlanGenerator.PlanParseException.class, () ->
                PlanGenerator.parse("{\"summary\":\"s\",\"orderNo\":\"ORD1\",\"steps\":[]}"));
        assertThrows(PlanGenerator.PlanParseException.class, () ->
                PlanGenerator.parse("不是JSON"));
    }

    // ---------- createPlan（stub LLM 全链路）----------

    @Test
    void createPlan_happyPath_landsPendingConfirm() {
        PlanService.CreatePlanResult r = planService.createPlan(1L, U1,
                "帮我取消订单 ORD20260901001，我不想要了", List.of());
        assertFalse(r.refused(), "不应被拒绝: " + r.refusalMessage());
        assertNotNull(r.plan().id);
        assertEquals("PENDING_CONFIRM", r.plan().status);
        List<PlanStepEntity> steps = planStepRepository.findByPlanIdOrderBySeq(r.plan().id);
        assertEquals(1, steps.size());
        assertEquals("cancelOrder", steps.get(0).toolName);
        assertEquals("PENDING", steps.get(0).status);
    }

    @Test
    void createPlan_otherUsersOrder_refusedBeforeLlm() {
        PlanService.CreatePlanResult r = planService.createPlan(1L, U1,
                "取消订单 ORD202609060006", List.of());
        assertTrue(r.refused(), "越权订单必须直接拒绝");
        assertNull(r.plan());
    }

    @Test
    void createPlan_policyDenied_refused() {
        // 已发货订单取消 → 政策拒绝，不生成 Plan（stub 忠实回显订单号）
        PlanService.CreatePlanResult r = planService.createPlan(1L, U1,
                "取消订单 ORD202609050002，太慢了", List.of());
        assertTrue(r.refused(), "已发货订单的政策拒绝必须发生在 Plan 落库之前");
        assertNull(r.plan());
        assertTrue(r.refusalMessage().contains("政策"));
    }

    @Test
    void createPlan_orderNotFound_refused() {
        PlanService.CreatePlanResult r = planService.createPlan(1L, U1,
                "取消订单 ORD99999999999", List.of());
        assertTrue(r.refused());
        assertNull(r.plan());
        assertTrue(r.refusalMessage().contains("不存在"));
    }

    // ---------- PlanManager 状态机 ----------

    private PlanEntity savePlan(String orderNo, Long estimatedCents) {
        OrderEntity order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        PlanGenerator.GeneratedPlan gen = new PlanGenerator.GeneratedPlan(
                "test", orderNo, List.of(new PlanGenerator.GeneratedPlan.Step("cancelOrder", "r")));
        PlanEntity plan = new PlanEntity();
        plan.conversationId = 1L;
        plan.userId = U1;
        plan.orderNo = orderNo;
        plan.summary = "test plan";
        plan.estimatedAmountCents = estimatedCents;
        plan.contextFingerprint = PlanService.fingerprintOf(order, gen);
        plan.status = "PENDING_CONFIRM";
        plan.secondConfirmRequired = estimatedCents != null && estimatedCents >= props.confirmThresholdCents();
        plan = planRepository.saveAndFlush(plan);

        PlanStepEntity step = new PlanStepEntity();
        step.planId = plan.id;
        step.seq = 0;
        step.toolName = "cancelOrder";
        step.argsJson = "{\"orderNo\":\"" + orderNo + "\",\"userId\":\"" + U1 + "\",\"reason\":\"r\"}";
        step.riskLevel = "WRITE";
        step.status = "PENDING";
        step.attempt = 0;
        planStepRepository.save(step);
        return plan;
    }

    @Test
    void confirmFlow_smallAmount_singleConfirm() {
        PlanEntity plan = savePlan("ORD20260901001", null);
        PlanManager.ConfirmResult r = planManager.confirm(plan.id, U1);
        assertEquals("CONFIRMED", r.status());
        assertTrue(r.confirmed());
        assertEquals("CONFIRMED", planRepository.findById(plan.id).orElseThrow().status);
    }

    @Test
    void confirmFlow_largeAmount_requiresSecondConfirm() {
        PlanEntity plan = savePlan("ORD202609150011", 159900L); // $1599.00 ≥ $500
        assertTrue(plan.secondConfirmRequired);

        PlanManager.ConfirmResult first = planManager.confirm(plan.id, U1);
        assertEquals("AWAITING_SECOND_CONFIRM", first.status());
        assertFalse(first.confirmed(), "金额≥$500 第一次确认不应直接放行");

        PlanManager.ConfirmResult second = planManager.confirm(plan.id, U1);
        assertEquals("CONFIRMED", second.status());
        assertTrue(second.confirmed());
    }

    @Test
    void confirmFlow_fingerprintChange_expires() {
        PlanEntity plan = savePlan("ORD20260901001", null);
        // 用户 A 在确认前，订单状态被改变（模拟并发/条件修改）
        cancelOrderTool.cancelOrder("ORD20260901001", U1, "并发取消");

        PlanManager.ConfirmResult r = planManager.confirm(plan.id, U1);
        assertEquals("EXPIRED", r.status(), "订单状态变化后旧 Plan 必须失效");
        assertFalse(r.confirmed());
        assertEquals("EXPIRED", planRepository.findById(plan.id).orElseThrow().status);

        // 再次确认仍被拒
        assertEquals("EXPIRED", planManager.confirm(plan.id, U1).status());
    }

    @Test
    void confirmFlow_rejectClosesPlan() {
        PlanEntity plan = savePlan("ORD20260901001", null);
        PlanManager.ConfirmResult r = planManager.reject(plan.id, U1);
        assertEquals("CLOSED", r.status());
        assertEquals("CLOSED", planRepository.findById(plan.id).orElseThrow().status);
    }

    @Test
    void confirmFlow_forbiddenUser() {
        PlanEntity plan = savePlan("ORD20260901001", null);
        assertEquals("FORBIDDEN", planManager.confirm(plan.id, "U002").status());
        assertEquals("FORBIDDEN", planManager.reject(plan.id, "U002").status());
    }

    @Test
    void fingerprint_changesWithStatus() {
        OrderEntity order = orderRepository.findByOrderNo("ORD20260901001").orElseThrow();
        PlanGenerator.GeneratedPlan gen = new PlanGenerator.GeneratedPlan(
                "s", order.getOrderNo(), List.of(new PlanGenerator.GeneratedPlan.Step("cancelOrder", "r")));
        String before = PlanService.fingerprintOf(order, gen);

        order.setStatus(OrderStatus.CANCELLED);
        String after = PlanService.fingerprintOf(order, gen);
        assertNotEquals(before, after, "状态变化必须导致指纹变化");
    }

    // ---------- Plan 解析器容错（MiniMax-M3 / DeepSeek-R1 思考标签） ----------

    private static final String PLAN_JSON =
            "{\"summary\":\"取消订单\",\"orderNo\":\"ORD20260901001\","
                    + "\"steps\":[{\"tool\":\"cancelOrder\",\"reason\":\"不想要了\"}]}";

    @Test
    void planParser_toleratesThinkingTags() {
        String out = "Let me think about this.\n\n" + PLAN_JSON;
        assertEquals("ORD20260901001", PlanGenerator.parse(out).orderNo());
    }

    @Test
    void planParser_unclosedThinkingTag_failsClosed() {
        // 未闭合思考标签 = 输出被截断，无完整 JSON：失败关闭，不产出计划
        String out = "Let me think about this...";
        assertThrows(PlanGenerator.PlanParseException.class, () -> PlanGenerator.parse(out));
    }

    @Test
    void planParser_toleratesCodeFence() {
        String out = "```json\n" + PLAN_JSON + "\n```";
        assertEquals("ORD20260901001", PlanGenerator.parse(out).orderNo());
    }

    @Test
    void planParser_stillRejectsGarbage() {
        assertThrows(PlanGenerator.PlanParseException.class, () -> PlanGenerator.parse("我不会输出 JSON"));
    }
}
