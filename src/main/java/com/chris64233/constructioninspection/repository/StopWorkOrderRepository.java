package com.chris64233.constructioninspection.repository;

import com.chris64233.constructioninspection.domain.StopWorkOrder;
import com.chris64233.constructioninspection.domain.StopWorkOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StopWorkOrderRepository extends JpaRepository<StopWorkOrder, Long> {

    long countByPermitIdAndStatus(Long permitId, StopWorkOrderStatus status);

    List<StopWorkOrder> findByPermitIdOrderById(Long permitId);
}
