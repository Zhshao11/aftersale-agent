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
            String message,
            /**
             * 可选：由**客户端**生成的 traceId。
             *
             * 为什么让客户端生成而不是服务端返回：一次只读请求要 20~40 秒，
             * 而前端只有一个"Agent 思考中…"的转圈。要让等待变得可见，前端必须在
             * **请求还没返回时**就能查到这次请求的轨迹——那就只能由它自己先定 id。
             * 服务端返回 id 的方案是自相矛盾的：拿到 id 的时候请求已经结束了。
             *
             * 不传则由服务端生成，行为与之前一致。
             */
            @Size(max = 32, message = "traceId 最长 32 字符")
            String traceId) {
    }

    /**
     * POST /api/chat
     * {"userId":"U001","message":"我的耳机订单到哪了","conversationId":null}
     * → {"conversationId":1,"intent":"QUERY","reply":"...","planCard":null,
     *    "trace":{"traceId":"...","termination":"ANSWERED","steps":2,...}}
     *
     * 传 {"traceId":"<16位>"} 可在请求进行中轮询 GET /api/trace/{traceId} 看进度。
     * 异常响应统一由 GlobalExceptionHandler 产出 {error, message, errorId}。
     */
    @PostMapping("/chat")
    public AgentOrchestrator.ChatResponse chat(@Valid @RequestBody ChatRequest req) {
        return orchestrator.chat(req.conversationId(), req.userId().trim(), req.message().trim(),
                req.traceId());
    }
}
