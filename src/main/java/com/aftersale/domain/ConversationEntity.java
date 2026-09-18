package com.aftersale.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "conversations")
public class ConversationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false, length = 32)
    public String userId;

    @Column(name = "created_at", insertable = false, updatable = false)
    public LocalDateTime createdAt;
}
