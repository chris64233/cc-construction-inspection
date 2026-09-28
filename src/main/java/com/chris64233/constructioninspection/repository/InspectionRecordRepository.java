package com.chris64233.constructioninspection.repository;

import com.chris64233.constructioninspection.domain.InspectionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InspectionRecordRepository extends JpaRepository<InspectionRecord, Long> {

    Optional<InspectionRecord> findBySubmissionNo(String submissionNo);

    boolean existsByVersionIdAndItemDefinitionId(Long versionId, Long itemDefinitionId);

    List<InspectionRecord> findByVersionId(Long versionId);

    /** 当前有效（未因方案变更失效）的检查结果 */
    List<InspectionRecord> findByVersionIdAndInvalidatedFalse(Long versionId);

    /** 某阶段下所有仍有效的检查结果（跨其全部工程版本） */
    @Query("select r from InspectionRecord r where r.version.stage.id = :stageId and r.invalidated = false")
    List<InspectionRecord> findValidByStageId(@Param("stageId") Long stageId);

    List<InspectionRecord> findByVersionIdIn(Collection<Long> versionIds);

    /**
     * 方案变更批准：使某阶段下所有仍有效的检查结果失效，并登记使其失效的新方案版本号。
     * 仅对受影响阶段执行，未受影响阶段不触及。批量 UPDATE 直接落库（自动 flush/clear），
     * 不依赖持久化上下文，因此并发检查提交在阶段锁临界区内已提交的结果不会被漏失效。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update InspectionRecord r set r.invalidated = true, r.invalidatedByPlanVersion = :planVersion "
            + "where r.version.stage.id = :stageId and r.invalidated = false")
    int invalidateValidRecordsOfStage(@Param("stageId") Long stageId,
                                      @Param("planVersion") int planVersion);
}
