package com.aftersale.repo;

import com.aftersale.domain.PlanStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PlanStepRepository extends JpaRepository<PlanStepEntity, Long> {
    List<PlanStepEntity> findByPlanIdOrderBySeq(Long planId);
}
