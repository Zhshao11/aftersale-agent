package com.aftersale;

import com.aftersale.agent.ConversationService;
import com.aftersale.agent.IntentRouter;
import com.aftersale.domain.ConversationEntity;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D2 无 LLM 测试：意图解析（纯函数）+ 会话持久化与历史装配。
 * 真实 LLM 链路（路由+ReAct 工具调用）需 API key，D4 冒烟评测时验证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class D2AcceptanceTest {

    @Autowired ConversationService conversationService;

    @Test
    void intentParse_queryVariants() {
        assertEquals(IntentRouter.Intent.QUERY,
                IntentRouter.parse("{\"intent\":\"QUERY\",\"request\":\"查询物流\"}", "查物流").intent());
        assertEquals(IntentRouter.Intent.QUERY,
                IntentRouter.parse("前置噪音 {\"intent\":\"query\",\"request\":\"查订单\"}", "查订单").intent());
    }

    @Test
    void intentParse_writeVariants() {
        assertEquals(IntentRouter.Intent.WRITE,
                IntentRouter.parse("{\"intent\":\"WRITE\",\"request\":\"取消订单 ORD001\"}", "取消订单").intent());
        assertEquals(IntentRouter.Intent.WRITE,
                IntentRouter.parse("{\"intent\":\"write\",\"request\":\"退款\"}", "退款").intent());
    }

    @Test
    void intentParse_garbageFallsBackToQuery() {
        assertEquals(IntentRouter.Intent.QUERY, IntentRouter.parse("完全不是JSON", "原话").intent());
        assertEquals(IntentRouter.Intent.QUERY, IntentRouter.parse(null, "原话").intent());
        assertEquals(IntentRouter.Intent.QUERY, IntentRouter.parse("{\"broken\"", "原话").intent());
    }

    @Test
    void intentParse_requestRewriteKeepsOriginalWhenEmpty() {
        IntentRouter.IntentResult r = IntentRouter.parse("{\"intent\":\"WRITE\",\"request\":\"\"}", "原始诉求");
        assertEquals("原始诉求", r.normalizedRequest());
    }

    @Test
    void conversationPersistAndHistory() {
        ConversationEntity conv = conversationService.getOrCreate(null, "U001");
        assertNotNull(conv.id);
        conversationService.append(conv.id, "USER", "我的订单到哪了");
        conversationService.append(conv.id, "ASSISTANT", "已为您查询物流…");
        conversationService.append(conv.id, "USER", "退款政策是什么");

        List<Message> history = conversationService.history(conv.id, 6);
        assertEquals(3, history.size());
        assertEquals("我的订单到哪了", history.get(0).getText());
        assertEquals("退款政策是什么", history.get(2).getText());

        // 复用同一会话
        ConversationEntity again = conversationService.getOrCreate(conv.id, "U001");
        assertEquals(conv.id, again.id);
    }
}
