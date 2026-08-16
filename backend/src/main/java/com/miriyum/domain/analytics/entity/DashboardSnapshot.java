package com.miriyum.domain.analytics.entity;

import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardMetricDraft;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardSnapshotDraft;
import com.miriyum.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 여섯 metric cell을 묶는 불변 dashboard snapshot header다. */
@Entity
@Table(name = "dashboard_analytics_snapshots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DashboardSnapshot extends BaseEntity {

    public static final Set<String> REQUIRED_METRIC_KEYS = Set.of(
            "TODAY_RESERVATION_TEAMS",
            "RESERVATION_RATE",
            "TEAM_CAPACITY_UTILIZATION",
            "CANCELLATION_RATE",
            "WAITING_STATUS",
            "NO_SHOW_STATUS");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dashboard_snapshot_id")
    private Long id;

    @Column(name = "public_snapshot_id", nullable = false, length = 36)
    private String publicSnapshotId;

    @Column(name = "store_id", nullable = false)
    private long storeId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "time_zone_id", nullable = false, length = 64)
    private String timeZoneId;

    @Column(name = "as_of", nullable = false)
    private Instant asOf;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "store_authority_version", nullable = false)
    private long storeAuthorityVersion;

    @Column(name = "aggregation_version", nullable = false)
    private long aggregationVersion;

    @Column(name = "replaces_dashboard_snapshot_id")
    private Long replacesDashboardSnapshotId;

    @Column(name = "latest_marker")
    private Boolean latestMarker;

    public static DashboardSnapshot create(DashboardSnapshotDraft draft) {
        validate(draft);
        DashboardSnapshot snapshot = new DashboardSnapshot();
        snapshot.publicSnapshotId = UUID.randomUUID().toString();
        snapshot.storeId = draft.storeId();
        snapshot.businessDate = draft.businessDate();
        snapshot.timeZoneId = draft.timeZoneId();
        snapshot.asOf = draft.asOf();
        snapshot.generatedAt = draft.generatedAt();
        snapshot.storeAuthorityVersion = draft.storeAuthorityVersion();
        snapshot.aggregationVersion = draft.metrics().stream()
                .mapToLong(metric -> metric.metadata().aggregationVersion())
                .max()
                .orElseThrow();
        snapshot.latestMarker = true;
        return snapshot;
    }

    public UUID publicId() {
        return UUID.fromString(publicSnapshotId);
    }

    private static void validate(DashboardSnapshotDraft draft) {
        if (draft == null) {
            throw new IllegalArgumentException("snapshot draft is required");
        }
        Set<String> keys = draft.metrics().stream()
                .map(DashboardMetricDraft::metricKey)
                .collect(Collectors.toSet());
        if (draft.metrics().size() != REQUIRED_METRIC_KEYS.size()
                || !keys.equals(REQUIRED_METRIC_KEYS)) {
            throw new IllegalArgumentException("exactly six unique metric keys are required");
        }
        if (draft.metrics().stream()
                .anyMatch(metric -> !draft.asOf().equals(metric.metadata().asOf()))) {
            throw new IllegalArgumentException("every metric must share the snapshot asOf");
        }
    }
}
