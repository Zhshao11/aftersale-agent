package com.aftersale.repo;

import com.aftersale.domain.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {
    Optional<OrderEntity> findByOrderNo(String orderNo);
    List<OrderEntity> findByUserIdOrderByCreatedAtDesc(String userId);
}
