package com.miriyum.domain.store.schedule.repository;

import com.miriyum.domain.store.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationScheduleVersionRepository
        extends JpaRepository<ReservationScheduleVersion, Long> {

    List<ReservationScheduleVersion> findAllByStoreIdOrderByVersionNumber(long storeId);

    Optional<ReservationScheduleVersion> findByStoreIdAndVersionNumber(
            long storeId,
            long versionNumber);

    List<ReservationScheduleVersion>
            findTop100ByStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                    ScheduleVersionStatus status,
                    Instant effectiveAt);

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
