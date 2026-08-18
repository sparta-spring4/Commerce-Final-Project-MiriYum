package com.miriyum.domain.analytics.repository;

import com.miriyum.domain.analytics.entity.DashboardMetricSnapshot;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DashboardMetricSnapshotRepository
        extends JpaRepository<DashboardMetricSnapshot, Long> {

    List<DashboardMetricSnapshot> findAllByDashboardSnapshotIdOrderByMetricKeyAsc(
            long dashboardSnapshotId
    );

    long countByDashboardSnapshotId(long dashboardSnapshotId);
}
