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
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Optional<DashboardSnapshotResponse> findStoredSnapshot(
            long storeId,
            LocalDate businessDate,
            Instant asOf,
            long storeAuthorityVersion
    ) {
        return snapshotRepository
                .findByStoreIdAndBusinessDateAndAsOfAndStoreAuthorityVersion(
                        storeId, businessDate, asOf, storeAuthorityVersion)
                .map(this::storedResponse);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public DashboardSnapshotResponse publish(DashboardSnapshotDraft draft) {
        LockedStoreAuthority currentAuthority = lockStore(draft.storeId());
        if (!currentAuthority.platformManagementAllowed()) {
            throw new ServiceException(StoreErrorCode.STORE_FEATURE_RESTRICTED);
        }
        if (currentAuthority.dashboardAuthorityVersion() != draft.storeAuthorityVersion()) {
            return snapshotRepository
                    .findByStoreIdAndBusinessDateAndAsOfAndStoreAuthorityVersion(
                            draft.storeId(), draft.businessDate(), draft.asOf(),
                            currentAuthority.dashboardAuthorityVersion())
                    .map(this::storedResponse)
                    .orElseThrow(() -> new ServiceException(
                            StoreErrorCode.STORE_ENFORCEMENT_VERSION_CONFLICT));
        }
        DashboardSnapshot existing = snapshotRepository
                .findByStoreIdAndBusinessDateAndAsOfAndStoreAuthorityVersion(
                        draft.storeId(), draft.businessDate(), draft.asOf(),
                        draft.storeAuthorityVersion())
                .orElse(null);
        if (existing != null) {
            return storedResponse(existing);
        }

        DashboardSnapshotDraft publishedDraft = withDurableAggregationVersions(draft);
        DashboardSnapshot candidate = DashboardSnapshot.create(publishedDraft);

        jdbcTemplate.update("""
                UPDATE dashboard_analytics_snapshots
                SET latest_marker = NULL, updated_at = UTC_TIMESTAMP(6)
                WHERE store_id = ? AND business_date = ? AND latest_marker = TRUE
                """, publishedDraft.storeId(), publishedDraft.businessDate());

        UUID snapshotId = candidate.publicId();
        long aggregationVersion = publishedDraft.metrics().stream()
                .mapToLong(metric -> metric.metadata().aggregationVersion())
                .max()
                .orElseThrow();
        int inserted = jdbcTemplate.update("""
                INSERT INTO dashboard_analytics_snapshots (
                    public_snapshot_id, store_id, business_date, time_zone_id,
                    as_of, generated_at, store_authority_version, aggregation_version,
                    latest_marker, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, snapshotId.toString(), publishedDraft.storeId(), publishedDraft.businessDate(),
                publishedDraft.timeZoneId(), utc(publishedDraft.asOf()),
                utc(publishedDraft.generatedAt()), publishedDraft.storeAuthorityVersion(),
                aggregationVersion);

        if (inserted != 1) {
            throw new IllegalStateException("dashboard snapshot insert invariant violated");
        }

        long databaseId = jdbcTemplate.queryForObject(
                "SELECT dashboard_snapshot_id FROM dashboard_analytics_snapshots "
                        + "WHERE public_snapshot_id = ?",
                Long.class,
                snapshotId.toString());
        insertMetrics(databaseId, publishedDraft.metrics());
        return response(snapshotId, publishedDraft.generatedAt(), publishedDraft);
    }

    private DashboardSnapshotDraft withDurableAggregationVersions(DashboardSnapshotDraft draft) {
        DashboardSnapshot latest = snapshotRepository
                .findByStoreIdAndBusinessDateAndLatestMarkerTrue(
                        draft.storeId(), draft.businessDate())
                .orElse(null);
        if (latest == null) {
            return draft;
        }
        List<DashboardMetricSnapshot> stored = metricRepository
                .findAllByDashboardSnapshotIdOrderByMetricKeyAsc(latest.getId());
        if (stored.size() != DashboardSnapshot.REQUIRED_METRIC_KEYS.size()) {
            throw new IllegalStateException("latest dashboard snapshot is incomplete");
        }
        Map<String, DashboardMetricDraft> previous = stored.stream()
                .map(this::storedDraft)
                .collect(Collectors.toMap(DashboardMetricDraft::metricKey, Function.identity()));
        List<DashboardMetricDraft> versioned = draft.metrics().stream()
                .map(metric -> withDurableAggregationVersion(
                        metric, previous.get(metric.metricKey())))
                .toList();
        return new DashboardSnapshotDraft(
                draft.storeId(), draft.businessDate(), draft.timeZoneId(), draft.asOf(),
                draft.generatedAt(), draft.storeAuthorityVersion(), versioned);
    }

    private DashboardMetricDraft withDurableAggregationVersion(
            DashboardMetricDraft incoming,
            DashboardMetricDraft previous
    ) {
        if (previous == null) {
            return incoming;
        }
        DashboardMetricDraft versionedIncoming = withDurableNoShowSubcells(incoming, previous);
        long previousVersion = previous.metadata().aggregationVersion();
        long durableVersion = durableAggregationVersion(
                versionedIncoming.metadata().aggregationVersion(),
                previousVersion,
                sameAggregationState(versionedIncoming, previous));
        if (durableVersion == versionedIncoming.metadata().aggregationVersion()) {
            return versionedIncoming;
        }
        MetricMetadata metadata = versionedIncoming.metadata();
        return new DashboardMetricDraft(
                versionedIncoming.metricKey(), versionedIncoming.value(),
                withAggregationVersion(metadata, durableVersion));
    }

    private DashboardMetricDraft withDurableNoShowSubcells(
            DashboardMetricDraft incoming,
            DashboardMetricDraft previous
    ) {
        if (!"NO_SHOW_STATUS".equals(incoming.metricKey())
                || incoming.value() == null || previous.value() == null) {
            return incoming;
        }
        NoShowValue incomingValue = convert(incoming.value(), NoShowValue.class);
        NoShowValue previousValue = convert(previous.value(), NoShowValue.class);
        NoShowValue versionedValue = new NoShowValue(
                withDurableAggregationVersion(
                        incomingValue.reservationCandidate(),
                        previousValue.reservationCandidate()),
                withDurableAggregationVersion(
                        incomingValue.reservationConfirmed(),
                        previousValue.reservationConfirmed()),
                withDurableAggregationVersion(
                        incomingValue.waitingConfirmed(),
                        previousValue.waitingConfirmed()));
        JsonNode versionedJson = objectMapper.valueToTree(versionedValue);
        return Objects.equals(versionedJson, incoming.value())
                ? incoming
                : new DashboardMetricDraft(
                        incoming.metricKey(), versionedJson, incoming.metadata());
    }

    private CountMetricResponse withDurableAggregationVersion(
            CountMetricResponse incoming,
            CountMetricResponse previous
    ) {
        long durableVersion = durableAggregationVersion(
                incoming.metadata().aggregationVersion(),
                previous.metadata().aggregationVersion(),
                sameAggregationState(incoming, previous));
        return durableVersion == incoming.metadata().aggregationVersion()
                ? incoming
                : new CountMetricResponse(
                        incoming.value(),
                        withAggregationVersion(incoming.metadata(), durableVersion));
    }

    private static long durableAggregationVersion(
            long incomingVersion,
            long previousVersion,
            boolean sameState
    ) {
        return sameState
                ? Math.max(incomingVersion, previousVersion)
                : Math.max(incomingVersion, Math.addExact(previousVersion, 1L));
    }

    private static MetricMetadata withAggregationVersion(
            MetricMetadata metadata,
            long aggregationVersion
    ) {
        return new MetricMetadata(
                metadata.definitionVersion(), aggregationVersion, metadata.asOf(),
                metadata.dataThrough(), metadata.inputCheckpoint(), metadata.completeness(),
                metadata.corrected(), metadata.reasonCode());
    }

    private static boolean sameAggregationState(
            DashboardMetricDraft incoming,
            DashboardMetricDraft previous
    ) {
        MetricMetadata left = incoming.metadata();
        MetricMetadata right = previous.metadata();
        return Objects.equals(incoming.value(), previous.value())
                && Objects.equals(left.definitionVersion(), right.definitionVersion())
                && Objects.equals(left.dataThrough(), right.dataThrough())
                && Objects.equals(left.inputCheckpoint(), right.inputCheckpoint())
                && left.completeness() == right.completeness()
                && left.corrected() == right.corrected()
                && left.reasonCode() == right.reasonCode();
    }

    private static boolean sameAggregationState(
            CountMetricResponse incoming,
            CountMetricResponse previous
    ) {
        MetricMetadata left = incoming.metadata();
        MetricMetadata right = previous.metadata();
        return Objects.equals(incoming.value(), previous.value())
                && Objects.equals(left.definitionVersion(), right.definitionVersion())
                && Objects.equals(left.dataThrough(), right.dataThrough())
                && Objects.equals(left.inputCheckpoint(), right.inputCheckpoint())
                && left.completeness() == right.completeness()
                && left.corrected() == right.corrected()
                && left.reasonCode() == right.reasonCode();
    }

    private LockedStoreAuthority lockStore(long storeId) {
        LockedStoreAuthority locked = jdbcTemplate.queryForObject("""
                SELECT store_id, dashboard_authority_version, platform_management_allowed
                FROM stores
                WHERE store_id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new LockedStoreAuthority(
                resultSet.getLong("store_id"),
                resultSet.getLong("dashboard_authority_version"),
                resultSet.getBoolean("platform_management_allowed")), storeId);
        if (locked == null || locked.storeId() != storeId) {
            throw new IllegalStateException("dashboard store lock invariant violated");
        }
        return locked;
    }

    private record LockedStoreAuthority(
            long storeId,
            long dashboardAuthorityVersion,
            boolean platformManagementAllowed
    ) {
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
