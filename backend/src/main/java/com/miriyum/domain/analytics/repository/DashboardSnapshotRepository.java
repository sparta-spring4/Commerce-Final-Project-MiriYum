package com.miriyum.domain.analytics.repository;

import com.miriyum.domain.analytics.entity.DashboardSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DashboardSnapshotRepository extends JpaRepository<DashboardSnapshot, Long> {

    Optional<DashboardSnapshot> findByStoreIdAndBusinessDateAndAsOfAndStoreAuthorityVersion(
            long storeId,
            LocalDate businessDate,
            Instant asOf,
            long storeAuthorityVersion
    );

    Optional<DashboardSnapshot> findByStoreIdAndBusinessDateAndLatestMarkerTrue(
            long storeId,
            LocalDate businessDate
    );
}
