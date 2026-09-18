package com.aftersale.tools;

import com.aftersale.domain.OrderEntity;
import com.aftersale.domain.PolicyRuleEntity;
import com.aftersale.enums.OrderStatus;
import com.aftersale.repo.PolicyRuleRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 政策规则评估：基线（ReAct）与完整版（Plan-and-Execute）共用同一张规则表，
 * 保证消融实验中政策约束不是变量。
 */
@Service
public class PolicyService {

    private final PolicyRuleRepository policyRuleRepository;

    public PolicyService(PolicyRuleRepository policyRuleRepository) {
        this.policyRuleRepository = policyRuleRepository;
    }

    public record PolicyDecision(boolean allowed, String ruleCode, String reason) {}

    /** 按操作类型（CANCEL/REFUND/EXCHANGE）评估订单当前状态与送达窗口是否满足政策 */
    public PolicyDecision evaluate(String scope, OrderEntity order, LocalDateTime now) {
        List<PolicyRuleEntity> rules = policyRuleRepository.findByScopeAndEnabledTrue(scope.toUpperCase(Locale.ROOT));
        if (rules.isEmpty()) {
            return new PolicyDecision(false, "NO_RULE", "该操作类型没有可用政策规则，默认拒绝");
        }
        for (PolicyRuleEntity rule : rules) {
            boolean statusOk = List.of(rule.allowedStatuses.split(","))
                    .stream().map(String::trim).map(OrderStatus::valueOf)
                    .anyMatch(s -> s == order.getStatus());
            if (!statusOk) {
                continue;
            }
            if (rule.withinDays != null) {
                if (order.getDeliveredAt() == null) {
                    return new PolicyDecision(false, rule.ruleCode, "政策要求送达后 " + rule.withinDays + " 天内，但该订单未送达");
                }
                long days = Duration.between(order.getDeliveredAt(), now).toDays();
                if (days > rule.withinDays) {
                    return new PolicyDecision(false, rule.ruleCode,
                            "已超出政策窗口：送达后 " + days + " 天，超过 " + rule.withinDays + " 天上限");
                }
            }
            return new PolicyDecision(true, rule.ruleCode, rule.ruleName);
        }
        PolicyRuleEntity first = rules.get(0);
        return new PolicyDecision(false, first.ruleCode,
                "当前订单状态 " + order.getStatus() + " 不满足政策要求（" + first.ruleName + "）");
    }
}
