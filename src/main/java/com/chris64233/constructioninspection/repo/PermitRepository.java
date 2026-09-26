package com.chris64233.constructioninspection.repo;

import com.chris64233.constructioninspection.domain.Permit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PermitRepository extends JpaRepository<Permit, Long> {

    /** 批准与停工令签发共用此锁串行化，保证并发时只形成一种结果 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Permit p where p.id = :id")
    Optional<Permit> findByIdForUpdate(@Param("id") Long id);

    boolean existsByPermitNo(String permitNo);
}
