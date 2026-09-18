package com.aftersale.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "plans")
public class PlanEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "conversation_id", nullable = false)
    public Long conversationId;

    @Column(name = "user_id", nullable = false, length = 32)
    public String userId;

    @Column(name = "order_no", length = 32)
    public String orderNo;

    @Column(nullable = false, length = 512)
    public String summary;

    @Column(name = "estimated_amount_cents")
    public Long estimatedAmountCents;

    @Column(name = "context_fingerprint", nullable = false, length = 128)
    public String contextFingerprint;

    @Column(nullable = false, length = 20)
    public String status;

    @Column(name = "second_confirm_required", nullable = false)
    public boolean secondConfirmRequired;

    @Column(name = "created_at", insertable = false, updatable = false)
    public LocalDateTime createdAt;

    @Column(name = "confirmed_at")
    public LocalDateTime confirmedAt;
}
