package com.aftersale.repo;

import com.aftersale.domain.ConversationMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessageEntity, Long> {
    List<ConversationMessageEntity> findByConversationIdOrderByIdAsc(Long conversationId);
}
