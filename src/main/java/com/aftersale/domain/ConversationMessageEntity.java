package com.aftersale.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "conversation_messages")
public class ConversationMessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "conversation_id", nullable = false)
    public Long conversationId;

    @Column(nullable = false, length = 16)
    public String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String content;

    @Column(name = "created_at", insertable = false, updatable = false)
    public LocalDateTime createdAt;
}
