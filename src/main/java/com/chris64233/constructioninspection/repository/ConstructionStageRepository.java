package com.chris64233.constructioninspection.repository;

import com.chris64233.constructioninspection.domain.ConstructionStage;
import com.chris64233.constructioninspection.domain.StageStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConstructionStageRepository extends JpaRepository<ConstructionStage, Long> {

    /** 检查提交、整改关闭、阶段验收共用此行级悲观锁，保证验收不会基于过期结果 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ConstructionStage s where s.id = :id")
    Optional<ConstructionStage> findByIdForUpdate(@Param("id") Long id);

    /**
     * 方案变更批准一次性按 seq 升序锁定许可下全部阶段行：
     * 与检查提交/整改/验收串行，且首次读取即为加锁定的最新已提交状态，
     * 避免先无锁读、后加锁拿到持久化上下文陈旧实体而丢失回退更新。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ConstructionStage s where s.permit.id = :permitId order by s.seq asc")
    List<ConstructionStage> findByPermitIdOrderBySeqForUpdate(@Param("permitId") Long permitId);

    List<ConstructionStage> findByPermitIdOrderBySeq(Long permitId);

    Optional<ConstructionStage> findByPermitIdAndSeq(Long permitId, int seq);

    long countByPermitIdAndStatusNot(Long permitId, StageStatus status);
}
