package com.aftersale.agent;

import com.aftersale.domain.ConversationEntity;
import com.aftersale.domain.ConversationMessageEntity;
import com.aftersale.repo.ConversationMessageRepository;
import com.aftersale.repo.ConversationRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 会话与消息持久化：为 LLM 提供历史上下文 */
@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;

    public ConversationService(ConversationRepository conversationRepository,
                               ConversationMessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public ConversationEntity getOrCreate(Long conversationId, String userId) {
        if (conversationId != null) {
            return conversationRepository.findById(conversationId)
                    .orElseGet(() -> create(userId));
        }
        return create(userId);
    }

    private ConversationEntity create(String userId) {
        ConversationEntity c = new ConversationEntity();
        c.userId = userId;
        return conversationRepository.save(c);
    }

    @Transactional
    public void append(Long conversationId, String role, String content) {
        ConversationMessageEntity m = new ConversationMessageEntity();
        m.conversationId = conversationId;
        m.role = role;
        m.content = content == null ? "" : content;
        messageRepository.save(m);
    }

    /** 载入历史（最近 N 轮），转成 Spring AI Message 列表 */
    @Transactional(readOnly = true)
    public List<Message> history(Long conversationId, int lastN) {
        List<ConversationMessageEntity> all =
                messageRepository.findByConversationIdOrderByIdAsc(conversationId);
        int from = Math.max(0, all.size() - lastN * 2);
        return all.subList(from, all.size()).stream()
                .map(m -> "USER".equals(m.role)
                        ? (Message) new UserMessage(m.content)
                        : (Message) new AssistantMessage(m.content))
                .toList();
    }
}
