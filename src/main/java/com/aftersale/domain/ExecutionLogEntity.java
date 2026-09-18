package com.aftersale.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "execution_log")
public class ExecutionLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "plan_id", nullable = false)
    public Long planId;

    @Column(name = "step_id", nullable = false)
    public Long stepId;

    @Column(nullable = false)
    public int attempt;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    public String idempotencyKey;

    @Column(nullable = false, length = 16)
    public String status;

    @Column(name = "error_code", length = 64)
    public String errorCode;

    public String detail;

    @Column(name = "latency_ms")
    public Long latencyMs;

    @Column(name = "created_at", insertable = false, updatable = false)
    public LocalDateTime createdAt;
}
