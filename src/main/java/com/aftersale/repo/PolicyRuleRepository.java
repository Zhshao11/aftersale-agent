package com.aftersale.repo;

import com.aftersale.domain.PolicyRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PolicyRuleRepository extends JpaRepository<PolicyRuleEntity, Long> {
    List<PolicyRuleEntity> findByScopeAndEnabledTrue(String scope);
}
