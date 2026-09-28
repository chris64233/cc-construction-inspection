package com.chris64233.constructioninspection.repository;

import com.chris64233.constructioninspection.domain.Rectification;
import com.chris64233.constructioninspection.domain.RectificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RectificationRepository extends JpaRepository<Rectification, Long> {

    long countByStageIdAndStatus(Long stageId, RectificationStatus status);

    List<Rectification> findByStageIdAndStatus(Long stageId, RectificationStatus status);

    List<Rectification> findByStageIdOrderById(Long stageId);
}
