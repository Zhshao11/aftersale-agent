package com.aftersale.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "plan_steps")
public class PlanStepEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "plan_id", nullable = false)
    public Long planId;

    @Column(nullable = false)
    public int seq;

    @Column(name = "tool_name", nullable = false, length = 64)
    public String toolName;

    @Column(name = "args_json", nullable = false, columnDefinition = "JSON")
    public String argsJson;

    @Column(name = "risk_level", nullable = false, length = 16)
    public String riskLevel;

    @Column(nullable = false, length = 16)
    public String status;

    @Column(name = "result_json", columnDefinition = "JSON")
    public String resultJson;

    @Column(nullable = false)
    public int attempt;

    @Column(name = "created_at", insertable = false, updatable = false)
    public LocalDateTime createdAt;
}
