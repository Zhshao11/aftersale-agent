package com.aftersale.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 幂等键：主键即 planId:stepId:attempt，唯一索引构造性防重放 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyEntity {
    @Id
    @Column(name = "idempotency_key", length = 128)
    public String idempotencyKey;

    @Column(name = "plan_id", nullable = false)
    public Long planId;

    @Column(name = "step_id", nullable = false)
    public Long stepId;

    @Column(nullable = false)
    public int attempt;

    @Column(name = "result_status", length = 16)
    public String resultStatus;

    @Column(name = "result_json", columnDefinition = "JSON")
    public String resultJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    public LocalDateTime createdAt;
}
