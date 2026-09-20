package com.aftersale.api;

import com.aftersale.agent.AgentOrchestrator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话入口。
 *
 * 输入校验走 Bean Validation，不再手写 if 判空：手写判空只能覆盖必填，
 * 覆盖不了长度——超长输入会直接进模型和数据库，那是拿 token 和存储当免费资源用。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final AgentOrchestrator orchestrator;

    public ChatController(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public record ChatRequest(
            Long conversationId,
            @NotBlank(message = "userId 必填")
            @Size(max = 32, message = "userId 最长 32 字符")
            String userId,
            @NotBlank(message = "message 必填")
            @Size(max = 2000, message = "message 最长 2000 字符")
            String message) {
    }

    /**
     * POST /api/chat
     * {"userId":"U001","message":"我的耳机订单到哪了","conversationId":null}
     * → {"conversationId":1,"intent":"QUERY","reply":"...","planCard":null,
     *    "trace":{"traceId":"...","termination":"ANSWERED","steps":2,...}}
     *
     * 异常响应统一由 GlobalExceptionHandler 产出 {error, message, errorId}。
     */
    @PostMapping("/chat")
    public AgentOrchestrator.ChatResponse chat(@Valid @RequestBody ChatRequest req) {
        return orchestrator.chat(req.conversationId(), req.userId().trim(), req.message().trim());
    }
}
