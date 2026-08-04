package com.miriyum.domain.store.schedule.repository;

import com.miriyum.domain.store.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationScheduleVersionRepository
        extends JpaRepository<ReservationScheduleVersion, Long> {

    @Query("""
            select distinct version
            from ReservationScheduleVersion version
            left join fetch version.entries
            where version.id in :ids
              and version.status = :status
            """)
    List<ReservationScheduleVersion> findActiveByIdsWithEntries(
            @Param("ids") Collection<Long> ids,
            @Param("status") ScheduleVersionStatus status);

    List<ReservationScheduleVersion> findAllByStoreIdOrderByVersionNumber(long storeId);

    Optional<ReservationScheduleVersion> findByStoreIdAndVersionNumber(
            long storeId,
            long versionNumber);

    @Query("""
            select candidate
            from ReservationScheduleVersion candidate
            where candidate.status = :status
              and candidate.effectiveAt <= :effectiveAt
              and not exists (
                  select older.id
                  from ReservationScheduleVersion older
                  where older.storeId = candidate.storeId
                    and older.status = :status
                    and older.effectiveAt <= :effectiveAt
                    and (
                        older.effectiveAt < candidate.effectiveAt
                        or (
                            older.effectiveAt = candidate.effectiveAt
                            and older.versionNumber < candidate.versionNumber
                        )
                    )
              )
            order by candidate.effectiveAt asc, candidate.versionNumber asc
            """)
    List<ReservationScheduleVersion> findEarliestDuePerStore(
            @Param("status") ScheduleVersionStatus status,
            @Param("effectiveAt") Instant effectiveAt,
            Pageable pageable);

    Optional<ReservationScheduleVersion>
            findFirstByStoreIdAndStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                    long storeId,
                    ScheduleVersionStatus status,
                    Instant effectiveAt);

    @Query("""
            select version.storeId
            from ReservationScheduleVersion version
            where version.id = :id
            """)
    Optional<Long> findStoreIdById(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select version
            from ReservationScheduleVersion version
            where version.id = :id
            """)
    Optional<ReservationScheduleVersion> findForUpdateById(@Param("id") long id);
}
