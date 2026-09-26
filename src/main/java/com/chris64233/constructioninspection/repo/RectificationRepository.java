package com.chris64233.constructioninspection.repo;

import com.chris64233.constructioninspection.domain.Rectification;
import com.chris64233.constructioninspection.domain.RectificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RectificationRepository extends JpaRepository<Rectification, Long> {

    Optional<Rectification> findByIdAndStageId(Long id, Long stageId);

    List<Rectification> findByStageIdOrderByCreatedAtAscIdAsc(Long stageId);

    List<Rectification> findByStageIdAndItemCodeAndStatus(Long stageId, String itemCode,
                                                          RectificationStatus status);

    long countByStageIdAndStatusIn(Long stageId, Collection<RectificationStatus> statuses);
}
