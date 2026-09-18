package com.aftersale.repo;

import com.aftersale.domain.PlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PlanRepository extends JpaRepository<PlanEntity, Long> {
    List<PlanEntity> findByConversationIdOrderByIdDesc(Long conversationId);
    Optional<PlanEntity> findByIdAndUserId(Long id, String userId);
}
