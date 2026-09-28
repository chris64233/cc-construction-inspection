package com.chris64233.constructioninspection.repository;

import com.chris64233.constructioninspection.domain.Rectification;
import com.chris64233.constructioninspection.domain.RectificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RectificationRepository extends JpaRepository<Rectification, Long> {

    long countByStageIdAndStatus(Long stageId, RectificationStatus status);

    List<Rectification> findByStageIdOrderById(Long stageId);

    /**
     * 方案变更批准：取消受影响阶段下所有未关闭整改项，登记取消其的新方案版本号。
     * 批量 UPDATE 直接落库（自动 flush/clear），不遗漏并发检查提交在阶段锁临界区内新建的整改项。
     * 已关闭整改项作为历史整改链保留。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Rectification rc set rc.status = com.chris64233.constructioninspection.domain.RectificationStatus.CANCELLED, "
            + "rc.cancelledByPlanVersion = :planVersion, rc.closedAt = :closedAt "
            + "where rc.stage.id = :stageId and rc.status = com.chris64233.constructioninspection.domain.RectificationStatus.OPEN")
    int cancelOpenRectificationsOfStage(@Param("stageId") Long stageId,
                                        @Param("planVersion") int planVersion,
                                        @Param("closedAt") java.time.Instant closedAt);
}
