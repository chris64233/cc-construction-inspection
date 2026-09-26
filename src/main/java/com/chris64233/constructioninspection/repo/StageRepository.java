package com.chris64233.constructioninspection.repo;

import com.chris64233.constructioninspection.domain.Stage;
import com.chris64233.constructioninspection.domain.StageStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StageRepository extends JpaRepository<Stage, Long> {

    /**
     * 检查提交、整改提交、阶段验收都通过此锁串行化，
     * 保证验收读取的版本与结论不会被并发修改（不会基于过期结果验收）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stage s join fetch s.items where s.permit.id = :permitId and s.sequence = :sequence")
    Optional<Stage> findByPermitIdAndSequenceForUpdate(@Param("permitId") Long permitId,
                                                       @Param("sequence") int sequence);

    @EntityGraph(attributePaths = "items")
    List<Stage> findByPermitIdOrderBySequenceAsc(Long permitId);

    boolean existsByPermitIdAndSequenceLessThanAndStatusNot(Long permitId, int sequence, StageStatus status);

    long countByPermitIdAndStatusNot(Long permitId, StageStatus status);
}
