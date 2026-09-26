package com.chris64233.constructioninspection.repository;

import com.chris64233.constructioninspection.domain.InspectionItemDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InspectionItemDefinitionRepository extends JpaRepository<InspectionItemDefinition, Long> {

    List<InspectionItemDefinition> findByStageIdOrderById(Long stageId);
}
