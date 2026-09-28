package com.chris64233.constructioninspection.repository;

import com.chris64233.constructioninspection.domain.Amendment;
import com.chris64233.constructioninspection.domain.AmendmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AmendmentRepository extends JpaRepository<Amendment, Long> {

    List<Amendment> findByPermitIdOrderById(Long permitId);

    Optional<Amendment> findFirstByPermitIdAndStatusOrderByIdDesc(Long permitId, AmendmentStatus status);

    long countByPermitIdAndStatus(Long permitId, AmendmentStatus status);
}
