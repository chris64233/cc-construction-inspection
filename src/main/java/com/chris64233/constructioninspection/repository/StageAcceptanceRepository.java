package com.chris64233.constructioninspection.repository;

import com.chris64233.constructioninspection.domain.StageAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StageAcceptanceRepository extends JpaRepository<StageAcceptance, Long> {

    List<StageAcceptance> findByStageIdOrderByIdAsc(Long stageId);
}
