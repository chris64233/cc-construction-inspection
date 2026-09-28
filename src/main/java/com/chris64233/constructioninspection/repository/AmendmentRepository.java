package com.chris64233.constructioninspection.repository;

import com.chris64233.constructioninspection.domain.Amendment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AmendmentRepository extends JpaRepository<Amendment, Long> {

    List<Amendment> findByPermitIdOrderById(Long permitId);
}
