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

    List<ConstructionStage> findByPermitIdOrderBySeq(Long permitId);

    Optional<ConstructionStage> findByPermitIdAndSeq(Long permitId, int seq);

    long countByPermitIdAndStatusNot(Long permitId, StageStatus status);
}
