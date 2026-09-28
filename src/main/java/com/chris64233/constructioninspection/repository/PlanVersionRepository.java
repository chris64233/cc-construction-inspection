package com.chris64233.constructioninspection.repository;

import com.chris64233.constructioninspection.domain.PlanVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanVersionRepository extends JpaRepository<PlanVersion, Long> {

    Optional<PlanVersion> findTopByPermitIdOrderByVersionNumberDesc(Long permitId);

    List<PlanVersion> findByPermitIdOrderByVersionNumber(Long permitId);

    Optional<PlanVersion> findByPermitIdAndVersionNumber(Long permitId, int versionNumber);
}
