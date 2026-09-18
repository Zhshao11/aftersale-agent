package com.aftersale.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V0 基线（ReAct 直连）的工具级确认存储：
 * 写工具首次被调用 → 记录 pending → 返回"待确认"；
 * 用户确认（POST /api/baseline/confirm）→ 真正执行。
 * 每个会话同时最多一个 pending 写操作。
 */
@Component
public class PendingWriteStore {

    public record Pending(String conversationId, String toolName, String orderNo,
                          String userId, String reason) {}

    private final Map<String, Pending> store = new ConcurrentHashMap<>();

    public void put(Pending p) {
        store.put(p.conversationId(), p);
    }

    public Pending take(String conversationId) {
        return store.remove(conversationId);
    }

    public Pending peek(String conversationId) {
        return store.get(conversationId);
    }
}
