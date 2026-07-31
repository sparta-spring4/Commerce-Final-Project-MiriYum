package com.miriyum.domain.store.schedule.repository;

import com.miriyum.domain.store.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    List<OperatingScheduleVersion>
            findTop100ByStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                    ScheduleVersionStatus status,
                    Instant effectiveAt);

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
}
