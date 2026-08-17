package com.miriyum.domain.analytics.service;

import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.CountMetricResponse;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardMetricDraft;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardMetricsResponse;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardSnapshotDraft;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardSnapshotResponse;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricMetadata;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.NoShowMetricResponse;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.NoShowValue;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.RateMetricResponse;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.RateValue;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.WaitingMetricResponse;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.WaitingValue;
import com.miriyum.domain.analytics.entity.DashboardMetricSnapshot;
import com.miriyum.domain.analytics.entity.DashboardSnapshot;
import com.miriyum.domain.analytics.repository.DashboardMetricSnapshotRepository;
import com.miriyum.domain.analytics.repository.DashboardSnapshotRepository;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** header와 정확히 여섯 metric cell을 한 MySQL transaction으로 게시한다. */
@Component
public class DashboardSnapshotTransactionExecutor {

    private final DashboardSnapshotRepository snapshotRepository;
    private final DashboardMetricSnapshotRepository metricRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DashboardSnapshotTransactionExecutor(
            DashboardSnapshotRepository snapshotRepository,
            DashboardMetricSnapshotRepository metricRepository,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.snapshotRepository = snapshotRepository;
        this.metricRepository = metricRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public DashboardSnapshotResponse publish(DashboardSnapshotDraft draft) {
        DashboardSnapshot candidate = DashboardSnapshot.create(draft);
        lockStore(draft.storeId());
        DashboardSnapshot existing = snapshotRepository
                .findByStoreIdAndBusinessDateAndAsOfAndStoreAuthorityVersion(
                        draft.storeId(), draft.businessDate(), draft.asOf(),
                        draft.storeAuthorityVersion())
                .orElse(null);
        if (existing != null) {
            return storedResponse(existing);
        }

        jdbcTemplate.update("""
                UPDATE dashboard_analytics_snapshots
                SET latest_marker = NULL, updated_at = UTC_TIMESTAMP(6)
                WHERE store_id = ? AND business_date = ? AND latest_marker = TRUE
                """, draft.storeId(), draft.businessDate());

        UUID snapshotId = candidate.publicId();
        long aggregationVersion = draft.metrics().stream()
                .mapToLong(metric -> metric.metadata().aggregationVersion())
                .max()
                .orElseThrow();
        int inserted = jdbcTemplate.update("""
                INSERT INTO dashboard_analytics_snapshots (
                    public_snapshot_id, store_id, business_date, time_zone_id,
                    as_of, generated_at, store_authority_version, aggregation_version,
                    latest_marker, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, snapshotId.toString(), draft.storeId(), draft.businessDate(),
                draft.timeZoneId(), utc(draft.asOf()), utc(draft.generatedAt()),
                draft.storeAuthorityVersion(), aggregationVersion);

        if (inserted != 1) {
            throw new IllegalStateException("dashboard snapshot insert invariant violated");
        }

        long databaseId = jdbcTemplate.queryForObject(
                "SELECT dashboard_snapshot_id FROM dashboard_analytics_snapshots "
                        + "WHERE public_snapshot_id = ?",
                Long.class,
                snapshotId.toString());
        insertMetrics(databaseId, draft.metrics());
        return response(snapshotId, draft.generatedAt(), draft);
    }

    private void lockStore(long storeId) {
        Long lockedStoreId = jdbcTemplate.queryForObject(
                "SELECT store_id FROM stores WHERE store_id = ? FOR UPDATE",
                Long.class,
                storeId);
        if (lockedStoreId == null || lockedStoreId != storeId) {
            throw new IllegalStateException("dashboard store lock invariant violated");
        }
    }

    private void insertMetrics(long dashboardSnapshotId, List<DashboardMetricDraft> metrics) {
        List<DashboardMetricDraft> ordered = metrics.stream()
                .sorted(Comparator.comparing(DashboardMetricDraft::metricKey))
                .toList();
        jdbcTemplate.batchUpdate("""
                INSERT INTO dashboard_analytics_metric_snapshots (
                    dashboard_snapshot_id, metric_key, definition_version,
                    aggregation_version, as_of, data_through, input_checkpoint,
                    completeness, corrected, reason_code, value_json,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, ordered, ordered.size(), (PreparedStatement statement, DashboardMetricDraft metric) -> {
                    statement.setLong(1, dashboardSnapshotId);
                    statement.setString(2, metric.metricKey());
                    statement.setString(3, metric.metadata().definitionVersion());
                    statement.setLong(4, metric.metadata().aggregationVersion());
                    statement.setObject(5, utc(metric.metadata().asOf()));
                    setTimestamp(statement, 6, metric.metadata().dataThrough());
                    statement.setString(7, metric.metadata().inputCheckpoint());
                    statement.setString(8, metric.metadata().completeness().name());
                    statement.setBoolean(9, metric.metadata().corrected());
                    statement.setString(10, metric.metadata().reasonCode() == null
                            ? null : metric.metadata().reasonCode().name());
                    statement.setString(11, metric.value() == null
                            ? null : objectMapper.writeValueAsString(metric.value()));
                });
    }

    private DashboardSnapshotResponse storedResponse(DashboardSnapshot existing) {
        List<DashboardMetricSnapshot> stored = metricRepository
                .findAllByDashboardSnapshotIdOrderByMetricKeyAsc(existing.getId());
        if (stored.size() != DashboardSnapshot.REQUIRED_METRIC_KEYS.size()) {
            throw new IllegalStateException("stored dashboard snapshot is incomplete");
        }
        List<DashboardMetricDraft> metrics = stored.stream()
                .map(this::storedDraft)
                .toList();
        DashboardSnapshotDraft storedSnapshot = new DashboardSnapshotDraft(
                existing.getStoreId(),
                existing.getBusinessDate(),
                existing.getTimeZoneId(),
                existing.getAsOf(),
                existing.getGeneratedAt(),
                existing.getStoreAuthorityVersion(),
                metrics);
        return response(existing.publicId(), existing.getGeneratedAt(), storedSnapshot);
    }

    private DashboardMetricDraft storedDraft(DashboardMetricSnapshot metric) {
        MetricMetadata metadata = new MetricMetadata(
                metric.getDefinitionVersion(),
                metric.getAggregationVersion(),
                metric.getAsOf(),
                metric.getDataThrough(),
                metric.getInputCheckpoint(),
                metric.getCompleteness(),
                metric.isCorrected(),
                metric.getReasonCode());
        return new DashboardMetricDraft(
                metric.getMetricKey(),
                metric.getValueJson() == null
                        ? null : objectMapper.readTree(metric.getValueJson()),
                metadata);
    }

    private DashboardSnapshotResponse response(
            UUID snapshotId,
            Instant generatedAt,
            DashboardSnapshotDraft draft
    ) {
        Map<String, DashboardMetricDraft> metrics = draft.metrics().stream()
                .collect(Collectors.toMap(DashboardMetricDraft::metricKey, Function.identity()));
        return new DashboardSnapshotResponse(
                snapshotId,
                Long.toString(draft.storeId()),
                draft.businessDate(),
                draft.timeZoneId(),
                draft.asOf(),
                generatedAt,
                draft.storeAuthorityVersion(),
                new DashboardMetricsResponse(
                        count(metrics.get("TODAY_RESERVATION_TEAMS")),
                        rate(metrics.get("RESERVATION_RATE")),
                        rate(metrics.get("TEAM_CAPACITY_UTILIZATION")),
                        rate(metrics.get("CANCELLATION_RATE")),
                        waiting(metrics.get("WAITING_STATUS")),
                        noShow(metrics.get("NO_SHOW_STATUS"))));
    }

    private CountMetricResponse count(DashboardMetricDraft metric) {
        return new CountMetricResponse(
                metric.value() == null ? null : metric.value().longValue(),
                metric.metadata());
    }

    private RateMetricResponse rate(DashboardMetricDraft metric) {
        return new RateMetricResponse(
                convert(metric.value(), RateValue.class), metric.metadata());
    }

    private WaitingMetricResponse waiting(DashboardMetricDraft metric) {
        return new WaitingMetricResponse(
                convert(metric.value(), WaitingValue.class), metric.metadata());
    }

    private NoShowMetricResponse noShow(DashboardMetricDraft metric) {
        return new NoShowMetricResponse(
                convert(metric.value(), NoShowValue.class), metric.metadata());
    }

    private <T> T convert(JsonNode value, Class<T> type) {
        return value == null ? null : objectMapper.treeToValue(value, type);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void setTimestamp(PreparedStatement statement, int index, Instant instant)
            throws java.sql.SQLException {
        if (instant == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setObject(index, utc(instant));
        }
    }
}
