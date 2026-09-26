package com.chris64233.constructioninspection.repository;

import com.chris64233.constructioninspection.domain.FinalApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FinalApprovalRepository extends JpaRepository<FinalApproval, Long> {

    Optional<FinalApproval> findByPermitId(Long permitId);
}
