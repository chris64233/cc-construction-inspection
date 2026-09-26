package com.chris64233.constructioninspection.repo;

import com.chris64233.constructioninspection.domain.StopOrderStatus;
import com.chris64233.constructioninspection.domain.StopWorkOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StopWorkOrderRepository extends JpaRepository<StopWorkOrder, Long> {

    Optional<StopWorkOrder> findByIdAndPermitId(Long id, Long permitId);

    boolean existsByPermitIdAndStatus(Long permitId, StopOrderStatus status);

    List<StopWorkOrder> findByPermitIdOrderByIssuedAtAscIdAsc(Long permitId);
}
