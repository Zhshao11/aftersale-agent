package com.aftersale.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "policy_rules")
public class PolicyRuleEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "rule_code", nullable = false, unique = true, length = 64)
    public String ruleCode;

    @Column(nullable = false, length = 16)
    public String scope;

    @Column(name = "rule_name", nullable = false, length = 128)
    public String ruleName;

    @Column(name = "allowed_statuses", nullable = false, length = 128)
    public String allowedStatuses;

    @Column(name = "within_days")
    public Integer withinDays;

    @Column(nullable = false)
    public boolean enabled = true;
}
