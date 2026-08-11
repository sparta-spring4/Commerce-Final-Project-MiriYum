package com.miriyum.domain.schedule.repository;

import com.miriyum.domain.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.schedule.model.ScheduleVersionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperatingScheduleVersionRepository
        extends JpaRepository<OperatingScheduleVersion, Long> {

    List<OperatingScheduleVersion> findAllByStoreIdOrderByVersionNumber(long storeId);

    Optional<OperatingScheduleVersion> findByStoreIdAndVersionNumber(
            long storeId,
            long versionNumber);

    @Query("""
            select candidate
            from OperatingScheduleVersion candidate
            where candidate.status = :status
              and candidate.effectiveAt <= :effectiveAt
              and not exists (
                  select older.id
                  from OperatingScheduleVersion older
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
    List<OperatingScheduleVersion> findEarliestDuePerStore(
            @Param("status") ScheduleVersionStatus status,
            @Param("effectiveAt") Instant effectiveAt,
            Pageable pageable);

    Optional<OperatingScheduleVersion>
            findFirstByStoreIdAndStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                    long storeId,
                    ScheduleVersionStatus status,
                    Instant effectiveAt);

    @Query("""
            select version.storeId
            from OperatingScheduleVersion version
            where version.id = :id
            """)
    Optional<Long> findStoreIdById(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select version
            from OperatingScheduleVersion version
            where version.id = :id
            """)
    Optional<OperatingScheduleVersion> findForUpdateById(@Param("id") long id);

    @Query("""
            select distinct version from OperatingScheduleVersion version
            left join fetch version.entries
            where version.id in :ids and version.status = :status
            """)
    List<OperatingScheduleVersion> findActiveByIdsWithEntries(
            @Param("ids") Collection<Long> ids,
            @Param("status") ScheduleVersionStatus status);
}
