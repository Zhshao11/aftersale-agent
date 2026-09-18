package com.aftersale.tools.read;

import com.aftersale.domain.PolicyRuleEntity;
import com.aftersale.repo.PolicyRuleRepository;
import com.aftersale.tools.ToolErrorCode;
import com.aftersale.tools.ToolResult;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具契约 — getPolicy
 * 用途：查询售后政策规则（取消/退款/换货的前置条件）
 * 输入：scope: string（CANCEL/REFUND/EXCHANGE，可选，缺省返回全部）
 * 输出：规则列表 [{ruleCode, ruleName, allowedStatuses, withinDays}]
 * 错误码：INVALID_ARGS（scope 不合法）
 * 权限边界：只读；公开政策信息，无用户归属校验
 */
@Component
public class GetPolicyTool {

    private final PolicyRuleRepository policyRuleRepository;

    public GetPolicyTool(PolicyRuleRepository policyRuleRepository) {
        this.policyRuleRepository = policyRuleRepository;
    }

    public ToolResult getPolicy(String scope) {
        List<PolicyRuleEntity> rules;
        if (scope == null || scope.isBlank()) {
            rules = policyRuleRepository.findByScopeAndEnabledTrue("CANCEL");
            rules.addAll(policyRuleRepository.findByScopeAndEnabledTrue("REFUND"));
            rules.addAll(policyRuleRepository.findByScopeAndEnabledTrue("EXCHANGE"));
        } else {
            String s = scope.trim().toUpperCase(Locale.ROOT);
            if (!List.of("CANCEL", "REFUND", "EXCHANGE").contains(s)) {
                return ToolResult.failure(ToolErrorCode.INVALID_ARGS, "scope 必须为 CANCEL/REFUND/EXCHANGE");
            }
            rules = policyRuleRepository.findByScopeAndEnabledTrue(s);
        }
        List<Map<String, Object>> data = rules.stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("ruleCode", r.ruleCode);
            m.put("ruleName", r.ruleName);
            m.put("allowedStatuses", r.allowedStatuses);
            m.put("withinDays", r.withinDays);
            return m;
        }).toList();
        return ToolResult.success(data);
    }
}
