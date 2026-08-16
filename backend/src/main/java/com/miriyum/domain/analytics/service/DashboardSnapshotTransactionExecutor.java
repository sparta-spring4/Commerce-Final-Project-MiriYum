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
        DashboardSnapshot existing = snapshotRepository
                .findByStoreIdAndBusinessDateAndAsOfAndStoreAuthorityVersion(
                        draft.storeId(), draft.businessDate(), draft.asOf(),
                        draft.storeAuthorityVersion())
                .orElse(null);
        if (existing != null) {
            requireCanonicalReplay(existing, draft);
            return response(existing.publicId(), existing.getGeneratedAt(), draft);
        }

        Long replacedId = snapshotRepository.findByStoreIdAndBusinessDateAndLatestMarkerTrue(
                        draft.storeId(), draft.businessDate())
                .map(DashboardSnapshot::getId)
                .orElse(null);
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
                INSERT IGNORE INTO dashboard_analytics_snapshots (
                    public_snapshot_id, store_id, business_date, time_zone_id,
                    as_of, generated_at, store_authority_version, aggregation_version,
                    replaces_dashboard_snapshot_id, latest_marker, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, snapshotId.toString(), draft.storeId(), draft.businessDate(),
                draft.timeZoneId(), utc(draft.asOf()), utc(draft.generatedAt()),
                draft.storeAuthorityVersion(), aggregationVersion, replacedId);

        if (inserted == 0) {
            DashboardSnapshot winner = snapshotRepository
                    .findByStoreIdAndBusinessDateAndAsOfAndStoreAuthorityVersion(
                            draft.storeId(), draft.businessDate(), draft.asOf(),
                            draft.storeAuthorityVersion())
                    .orElseThrow(() -> new IllegalStateException(
                            "dashboard snapshot identity conflict"));
            requireCanonicalReplay(winner, draft);
            return response(winner.publicId(), winner.getGeneratedAt(), draft);
        }

        long databaseId = jdbcTemplate.queryForObject(
                "SELECT dashboard_snapshot_id FROM dashboard_analytics_snapshots "
                        + "WHERE public_snapshot_id = ?",
                Long.class,
                snapshotId.toString());
        insertMetrics(databaseId, draft.metrics());
        return response(snapshotId, draft.generatedAt(), draft);
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

    private void requireCanonicalReplay(
            DashboardSnapshot existing,
            DashboardSnapshotDraft draft
    ) {
        List<DashboardMetricSnapshot> stored = metricRepository
                .findAllByDashboardSnapshotIdOrderByMetricKeyAsc(existing.getId());
        Map<String, DashboardMetricDraft> requested = draft.metrics().stream()
                .collect(Collectors.toMap(DashboardMetricDraft::metricKey, Function.identity()));
        if (stored.size() != DashboardSnapshot.REQUIRED_METRIC_KEYS.size()) {
            throw new IllegalStateException("stored dashboard snapshot is incomplete");
        }
        for (DashboardMetricSnapshot metric : stored) {
            DashboardMetricDraft expected = requested.get(metric.getMetricKey());
            java.util.List<String> mismatches = new java.util.ArrayList<>();
            if (expected == null) {
                mismatches.add("key");
            } else {
                if (!metric.getDefinitionVersion().equals(
                        expected.metadata().definitionVersion())) mismatches.add("definition");
                if (metric.getAggregationVersion()
                        != expected.metadata().aggregationVersion()) mismatches.add("aggregation");
                if (!metric.getAsOf().equals(expected.metadata().asOf())) {
                    mismatches.add("asOf");
                }
                if (!java.util.Objects.equals(
                        metric.getDataThrough(), expected.metadata().dataThrough())) {
                    mismatches.add("dataThrough");
                }
                if (!java.util.Objects.equals(
                        metric.getInputCheckpoint(), expected.metadata().inputCheckpoint())) {
                    mismatches.add("checkpoint");
                }
                if (metric.getCompleteness() != expected.metadata().completeness()) {
                    mismatches.add("completeness");
                }
                if (metric.isCorrected() != expected.metadata().corrected()) {
                    mismatches.add("corrected");
                }
                if (metric.getReasonCode() != expected.metadata().reasonCode()) {
                    mismatches.add("reason");
                }
                if (!jsonEquivalent(
                        metric.getValueJson() == null
                                ? null : objectMapper.readTree(metric.getValueJson()),
                        expected.value())) {
                    mismatches.add("value");
                }
            }
            if (!mismatches.isEmpty()) {
                throw new IllegalStateException(
                        "dashboard snapshot replay input differs for "
                                + metric.getMetricKey() + ": " + mismatches);
            }
        }
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

    private static boolean jsonEquivalent(JsonNode stored, JsonNode requested) {
        if (stored == null || requested == null) {
            return stored == requested;
        }
        return stored.equals((left, right) -> {
            if (left.isNumber() && right.isNumber()) {
                return left.decimalValue().compareTo(right.decimalValue());
            }
            return left.equals(right) ? 0 : 1;
        }, requested);
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
