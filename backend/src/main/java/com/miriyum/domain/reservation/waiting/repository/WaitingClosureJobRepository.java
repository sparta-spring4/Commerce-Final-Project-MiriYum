package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingClosureJob;
import com.miriyum.domain.reservation.waiting.entity.WaitingClosureJobStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 활성 팀 종결 작업의 조회와 worker claim 잠금을 제공한다. */
public interface WaitingClosureJobRepository extends JpaRepository<WaitingClosureJob, Long> {

    Optional<WaitingClosureJob> findByIdAndStoreId(long id, long storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from WaitingClosureJob job where job.id = :id")
    Optional<WaitingClosureJob> findByIdForUpdate(@Param("id") long id);

    Optional<WaitingClosureJob> findByStoreIdAndSettingsVersion(
            long storeId,
            long settingsVersion
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job
            from WaitingClosureJob job
            where job.status = :status
            order by job.createdAt asc, job.id asc
            """)
    List<WaitingClosureJob> findClaimableForUpdate(
            @Param("status") WaitingClosureJobStatus status,
            Pageable pageable
    );
}
