package com.miriyum.domain.schedule.closure.repository;

import com.miriyum.domain.schedule.closure.entity.RegularClosureVersion;
import com.miriyum.domain.schedule.model.ScheduleVersionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegularClosureVersionRepository extends JpaRepository<RegularClosureVersion, Long> {
    Optional<RegularClosureVersion> findByStoreIdAndVersionNumber(long storeId, long versionNumber);

    boolean existsByStoreIdAndEffectiveAt(long storeId, Instant effectiveAt);

    @Query("select v.storeId from RegularClosureVersion v where v.id = :id")
    Optional<Long> findStoreIdById(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from RegularClosureVersion v where v.id = :id")
    Optional<RegularClosureVersion> findForUpdateById(@Param("id") long id);

    Optional<RegularClosureVersion>
            findFirstByStoreIdAndStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                    long storeId, ScheduleVersionStatus status, Instant effectiveAt);

    @Query("""
            select candidate from RegularClosureVersion candidate
            where candidate.status = :status and candidate.effectiveAt <= :effectiveAt
              and not exists (select older.id from RegularClosureVersion older
                where older.storeId = candidate.storeId and older.status = :status
                  and older.effectiveAt <= :effectiveAt
                  and (older.effectiveAt < candidate.effectiveAt or
                    (older.effectiveAt = candidate.effectiveAt and older.versionNumber < candidate.versionNumber)))
            order by candidate.effectiveAt asc, candidate.versionNumber asc
            """)
    List<RegularClosureVersion> findEarliestDuePerStore(
            @Param("status") ScheduleVersionStatus status,
            @Param("effectiveAt") Instant effectiveAt,
            Pageable pageable);

    @Query("select distinct v from RegularClosureVersion v left join fetch v.entries where v.id in :ids and v.status = :status")
    List<RegularClosureVersion> findActiveByIdsWithEntries(
            @Param("ids") List<Long> ids, @Param("status") ScheduleVersionStatus status);
}
