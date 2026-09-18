package com.aftersale.api;

import com.aftersale.agent.AgentOrchestrator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final AgentOrchestrator orchestrator;

    public ChatController(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public record ChatRequest(Long conversationId, String userId, String message) {}

    /**
     * POST /api/chat
     * {"userId":"U001","message":"我的耳机订单到哪了","conversationId":null}
     * → {"conversationId":1,"intent":"QUERY","reply":"...","planCard":null}
     */
    @PostMapping("/chat")
    public AgentOrchestrator.ChatResponse chat(@RequestBody ChatRequest req) {
        if (req.userId() == null || req.userId().isBlank() || req.message() == null || req.message().isBlank()) {
            throw new IllegalArgumentException("userId 与 message 必填");
        }
        return orchestrator.chat(req.conversationId(), req.userId().trim(), req.message().trim());
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public Map<String, Object> badRequest(IllegalArgumentException e) {
        return Map.of("error", "INVALID_ARGS", "message", e.getMessage());
    }
}
