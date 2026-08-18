package com.miriyum.domain.analytics.entity;

import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardMetricDraft;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricCompleteness;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricReasonCode;
import com.miriyum.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** snapshot header에 속한 지표 하나의 불변 cell이다. */
@Entity
@Table(name = "dashboard_analytics_metric_snapshots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DashboardMetricSnapshot extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dashboard_metric_snapshot_id")
    private Long id;

    @Column(name = "dashboard_snapshot_id", nullable = false)
    private Long dashboardSnapshotId;

    @Column(name = "metric_key", nullable = false, length = 50)
    private String metricKey;

    @Column(name = "definition_version", nullable = false, length = 100)
    private String definitionVersion;

    @Column(name = "aggregation_version", nullable = false)
    private long aggregationVersion;

    @Column(name = "as_of", nullable = false)
    private Instant asOf;

    @Column(name = "data_through")
    private Instant dataThrough;

    @Column(name = "input_checkpoint", length = 255)
    private String inputCheckpoint;

    @Enumerated(EnumType.STRING)
    @Column(name = "completeness", nullable = false, length = 20)
    private MetricCompleteness completeness;

    @Column(name = "corrected", nullable = false)
    private boolean corrected;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 60)
    private MetricReasonCode reasonCode;

    @Column(name = "value_json", columnDefinition = "json")
    private String valueJson;

    public static DashboardMetricSnapshot create(
            long dashboardSnapshotId,
            Instant snapshotAsOf,
            DashboardMetricDraft draft
    ) {
        if (dashboardSnapshotId <= 0 || snapshotAsOf == null || draft == null
                || !snapshotAsOf.equals(draft.metadata().asOf())) {
            throw new IllegalArgumentException("metric must belong to the same snapshot asOf");
        }
        DashboardMetricSnapshot metric = new DashboardMetricSnapshot();
        metric.dashboardSnapshotId = dashboardSnapshotId;
        metric.metricKey = draft.metricKey();
        metric.definitionVersion = draft.metadata().definitionVersion();
        metric.aggregationVersion = draft.metadata().aggregationVersion();
        metric.asOf = draft.metadata().asOf();
        metric.dataThrough = draft.metadata().dataThrough();
        metric.inputCheckpoint = draft.metadata().inputCheckpoint();
        metric.completeness = draft.metadata().completeness();
        metric.corrected = draft.metadata().corrected();
        metric.reasonCode = draft.metadata().reasonCode();
        metric.valueJson = draft.value() == null ? null : draft.value().toString();
        return metric;
    }
}
