package com.chris64233.constructioninspection.repository;

import com.chris64233.constructioninspection.domain.WorkVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkVersionRepository extends JpaRepository<WorkVersion, Long> {

    Optional<WorkVersion> findTopByStageIdOrderByVersionNumberDesc(Long stageId);

    List<WorkVersion> findByStageIdOrderByVersionNumber(Long stageId);

    @Query("select coalesce(max(v.versionNumber), 0) from WorkVersion v where v.stage.id = :stageId")
    int findMaxVersionNumber(@Param("stageId") Long stageId);
}
