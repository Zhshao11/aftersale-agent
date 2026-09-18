package com.aftersale.repo;

import com.aftersale.domain.ExecutionLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ExecutionLogRepository extends JpaRepository<ExecutionLogEntity, Long> {
    List<ExecutionLogEntity> findByPlanIdOrderByAttemptAscIdAsc(Long planId);
    List<ExecutionLogEntity> findByStepId(Long stepId);
}
