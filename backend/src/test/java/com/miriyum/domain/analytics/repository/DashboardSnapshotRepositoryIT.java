package com.miriyum.domain.analytics.repository;

import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricCompleteness.COMPLETE;
import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricCompleteness.PARTIAL;
import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricCompleteness.UNAVAILABLE;
import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricReasonCode.SOURCE_CONTRACT_MISSING;
import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricReasonCode.SOURCE_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.CountMetricResponse;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardMetricDraft;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardSnapshotDraft;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardSnapshotResponse;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricCompleteness;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricMetadata;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.NoShowValue;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.RateValue;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.WaitingValue;
import com.miriyum.domain.analytics.service.DashboardSnapshotRetentionJob;
import com.miriyum.domain.analytics.service.DashboardSnapshotTransactionExecutor;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-b")
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.store.schedule.activation-delay-ms=600000",
        "miriyum.reservation.time-policy.activation-enabled=false",
        "miriyum.reservation.hold-expiration.enabled=false",
        "miriyum.waiting.closure.initial-delay-ms=600000",
        "miriyum.analytics.snapshot-retention.initial-delay-ms=600000"
})
@Import(DashboardSnapshotRepositoryIT.FixedClockConfig.class)
class DashboardSnapshotRepositoryIT {

    private static final Instant AS_OF = Instant.parse("2026-08-16T09:00:00Z");

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired DashboardSnapshotTransactionExecutor executor;
    @Autowired DashboardSnapshotRetentionJob retentionJob;
    @Autowired DashboardSnapshotRepository snapshotRepository;
    @Autowired DashboardMetricSnapshotRepository metricRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void fixture() {
        jdbc.execute("DROP TRIGGER IF EXISTS delay_dashboard_snapshot_insert");
        jdbc.execute("DELETE FROM dashboard_analytics_metric_snapshots");
        jdbc.execute("DELETE FROM dashboard_analytics_snapshots");
        jdbc.execute("DELETE FROM stores");
        jdbc.execute("DELETE FROM store_operator_accounts");
        jdbc.update("""
                INSERT INTO store_operator_accounts (
                    store_operator_account_id, email, password_hash, display_name, status,
                    created_at, updated_at
                ) VALUES (31, 'snapshot-owner@example.com', 'hash', '스냅샷 운영자',
                    'ACTIVE', NOW(6), NOW(6))
                """);
        jdbc.update("""
                INSERT INTO stores (
                    store_id, store_operator_account_id, business_registration_number,
                    name, description, region, address, time_zone_id,
                    applicant_self_attested_at, required_terms_agreed_at,
                    required_terms_version, store_category_code, verification_status,
                    operation_status, reservation_enabled, menu_hold_enabled, pickup_enabled,
                    created_at, updated_at
                ) VALUES (17, 31, '2700000019', '스냅샷 매장', '', 'SEOUL',
                    '서울시 중구', 'Asia/Seoul', NOW(6), NOW(6),
                    'STORE_ONBOARDING_REQUIRED_TERMS_V1', 'CAFE_BAKERY', 'APPROVED',
                    'OPEN', TRUE, TRUE, TRUE, NOW(6), NOW(6))
                """);
    }

    @Test
    void duplicatePublishReplaysOneHeaderAndExactlySixMetricCells() {
        DashboardSnapshotDraft draft = draftWithSixCells();

        DashboardSnapshotResponse first = executor.publish(draft);
        DashboardSnapshotResponse replay = executor.publish(draft);

        long databaseId = snapshotRepository.findAll().getFirst().getId();
        assertThat(replay.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(snapshotRepository.count()).isEqualTo(1);
        assertThat(metricRepository.countByDashboardSnapshotId(databaseId)).isEqualTo(6);
    }

    @Test
    void sameSnapshotBucketReturnsTheFirstStoredCanonicalValue() {
        DashboardSnapshotDraft firstDraft = draftWithSixCells();
        DashboardSnapshotResponse first = executor.publish(firstDraft);
        DashboardSnapshotDraft changedSourceDraft = draftWithReservationCount(99L, "changed");

        DashboardSnapshotResponse replay = executor.publish(changedSourceDraft);

        assertThat(replay.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(replay.metrics().todayReservationTeams().value()).isEqualTo(4L);
        assertThat(snapshotRepository.count()).isEqualTo(1L);
    }

    @Test
    void staleAuthorityDraftCannotReplaceTheCurrentAuthoritySnapshot() {
        DashboardSnapshotDraft staleV1 = draftWithSixCells();
        jdbc.update("""
                UPDATE stores
                SET platform_management_allowed = TRUE,
                    dashboard_authority_version = 3
                WHERE store_id = 17
                """);
        DashboardSnapshotDraft currentV3 = withAuthorityVersion(
                draftWithReservationCount(5L, "current-v3"), 3L);
        DashboardSnapshotResponse current = executor.publish(currentV3);

        DashboardSnapshotResponse staleReplay = executor.publish(staleV1);

        assertThat(staleReplay.snapshotId()).isEqualTo(current.snapshotId());
        assertThat(staleReplay.storeAuthorityVersion()).isEqualTo(3L);
        assertThat(staleReplay.metrics().todayReservationTeams().value()).isEqualTo(5L);
        assertThat(snapshotRepository.count()).isEqualTo(1L);
        assertThat(snapshotRepository.findByStoreIdAndBusinessDateAndLatestMarkerTrue(
                17L, currentV3.businessDate()))
                .get()
                .extracting(snapshot -> snapshot.getStoreAuthorityVersion())
                .isEqualTo(3L);
    }

    @Test
    void staleAuthorityDraftWithoutCurrentSnapshotReturnsVersionConflict() {
        DashboardSnapshotDraft staleV1 = draftWithSixCells();
        jdbc.update("""
                UPDATE stores
                SET platform_management_allowed = TRUE,
                    dashboard_authority_version = 3
                WHERE store_id = 17
                """);

        assertThatThrownBy(() -> executor.publish(staleV1))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.STORE_ENFORCEMENT_VERSION_CONFLICT));
        assertThat(snapshotRepository.count()).isZero();
    }

    @Test
    void draftCannotPublishWhileStoreManagementIsRestricted() {
        DashboardSnapshotDraft staleV1 = draftWithSixCells();
        jdbc.update("""
                UPDATE stores
                SET platform_management_allowed = FALSE,
                    dashboard_authority_version = 2
                WHERE store_id = 17
                """);

        assertThatThrownBy(() -> executor.publish(staleV1))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.STORE_FEATURE_RESTRICTED));
        assertThat(snapshotRepository.count()).isZero();
    }

    @Test
    void metricAggregationVersionRemainsMonotonicAcrossSourceFailureAndRecovery() {
        DashboardSnapshotResponse normal = executor.publish(draftWithMetricState(
                AS_OF, 27L, 4L, "reservation:7|waiting:20", COMPLETE));
        DashboardSnapshotResponse failed = executor.publish(draftWithMetricState(
                AS_OF.plusSeconds(60), 21L, null, null, UNAVAILABLE));
        DashboardSnapshotResponse recovered = executor.publish(draftWithMetricState(
                AS_OF.plusSeconds(120), 27L, 4L,
                "reservation:7|waiting:20", COMPLETE));

        assertThat(normal.metrics().todayReservationTeams().metadata().aggregationVersion())
                .isEqualTo(27L);
        assertThat(failed.metrics().todayReservationTeams().metadata().aggregationVersion())
                .isEqualTo(28L);
        assertThat(recovered.metrics().todayReservationTeams().metadata().aggregationVersion())
                .isEqualTo(29L);
    }

    @Test
    void reservationNoShowSubcellVersionRemainsMonotonicAcrossFailureAndRecovery() {
        DashboardSnapshotResponse normal = executor.publish(draftWithNoShowState(
                AS_OF, true, true));
        DashboardSnapshotResponse failed = executor.publish(draftWithNoShowState(
                AS_OF.plusSeconds(60), false, true));
        DashboardSnapshotResponse recovered = executor.publish(draftWithNoShowState(
                AS_OF.plusSeconds(120), true, true));

        assertThat(normal.metrics().noShow().value().reservationConfirmed()
                .metadata().aggregationVersion()).isEqualTo(7L);
        assertThat(failed.metrics().noShow().value().reservationConfirmed()
                .metadata().aggregationVersion()).isEqualTo(8L);
        assertThat(recovered.metrics().noShow().value().reservationConfirmed()
                .metadata().aggregationVersion()).isEqualTo(9L);
        assertThat(normal.metrics().noShow().value().waitingConfirmed()
                .metadata().aggregationVersion()).isEqualTo(20L);
        assertThat(failed.metrics().noShow().value().waitingConfirmed()
                .metadata().aggregationVersion()).isEqualTo(20L);
        assertThat(recovered.metrics().noShow().value().waitingConfirmed()
                .metadata().aggregationVersion()).isEqualTo(20L);
        assertThat(normal.metrics().noShow().metadata().aggregationVersion()).isEqualTo(27L);
        assertThat(failed.metrics().noShow().metadata().aggregationVersion()).isEqualTo(28L);
        assertThat(recovered.metrics().noShow().metadata().aggregationVersion()).isEqualTo(29L);
    }

    @Test
    void waitingNoShowSubcellVersionRemainsMonotonicAcrossFailureAndRecovery() {
        DashboardSnapshotResponse normal = executor.publish(draftWithNoShowState(
                AS_OF, true, true));
        DashboardSnapshotResponse failed = executor.publish(draftWithNoShowState(
                AS_OF.plusSeconds(60), true, false));
        DashboardSnapshotResponse recovered = executor.publish(draftWithNoShowState(
                AS_OF.plusSeconds(120), true, true));

        assertThat(normal.metrics().noShow().value().waitingConfirmed()
                .metadata().aggregationVersion()).isEqualTo(20L);
        assertThat(failed.metrics().noShow().value().waitingConfirmed()
                .metadata().aggregationVersion()).isEqualTo(21L);
        assertThat(recovered.metrics().noShow().value().waitingConfirmed()
                .metadata().aggregationVersion()).isEqualTo(22L);
        assertThat(normal.metrics().noShow().value().reservationConfirmed()
                .metadata().aggregationVersion()).isEqualTo(7L);
        assertThat(failed.metrics().noShow().value().reservationConfirmed()
                .metadata().aggregationVersion()).isEqualTo(7L);
        assertThat(recovered.metrics().noShow().value().reservationConfirmed()
                .metadata().aggregationVersion()).isEqualTo(7L);
    }

    @Test
    void concurrentFirstPublishesSerializeOnTheStoreAndLeaveOneLatestSnapshot()
            throws Exception {
        jdbc.execute("""
                CREATE TRIGGER delay_dashboard_snapshot_insert
                BEFORE INSERT ON dashboard_analytics_snapshots
                FOR EACH ROW SET @dashboard_delay = SLEEP(0.2)
                """);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<DashboardSnapshotResponse> first = pool.submit(() -> {
                ready.countDown();
                start.await();
                return executor.publish(draftAt(AS_OF, 4L, "first"));
            });
            Future<DashboardSnapshotResponse> second = pool.submit(() -> {
                ready.countDown();
                start.await();
                return executor.publish(draftAt(
                        AS_OF.plusSeconds(60), 5L, "second"));
            });
            ready.await();
            start.countDown();

            assertThat(first.get()).isNotNull();
            assertThat(second.get()).isNotNull();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS delay_dashboard_snapshot_insert");
        }

        assertThat(snapshotRepository.count()).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM dashboard_analytics_snapshots
                WHERE latest_marker = TRUE
                """, Long.class)).isEqualTo(1L);
        assertThat(metricRepository.count()).isEqualTo(12L);
    }

    @Test
    void publishingDoesNotRunRetentionInsideTheStoreLock() {
        executor.publish(draftAt(AS_OF.minusSeconds(32L * 24 * 60 * 60), 2L, "old"));
        executor.publish(draftAt(AS_OF, 4L, "current"));

        assertThat(snapshotRepository.count()).isEqualTo(2L);
        assertThat(metricRepository.count()).isEqualTo(12L);
    }

    @Test
    void retentionJobPrunesExpiredSnapshotsWithTheirMetrics() {
        executor.publish(draftAt(AS_OF.minusSeconds(32L * 24 * 60 * 60), 2L, "old"));
        executor.publish(draftAt(AS_OF, 4L, "current"));

        retentionJob.pruneExpiredSnapshots();

        assertThat(snapshotRepository.count()).isEqualTo(1L);
        assertThat(metricRepository.count()).isEqualTo(6L);
    }

    private DashboardSnapshotDraft draftWithSixCells() {
        return draftWithReservationCount(4L, "checkpoint");
    }

    private DashboardSnapshotDraft draftWithReservationCount(long reservationCount, String checkpoint) {
        return draftAt(AS_OF, reservationCount, checkpoint);
    }

    private static DashboardSnapshotDraft withAuthorityVersion(
            DashboardSnapshotDraft draft,
            long authorityVersion
    ) {
        return new DashboardSnapshotDraft(
                draft.storeId(), draft.businessDate(), draft.timeZoneId(), draft.asOf(),
                draft.generatedAt(), authorityVersion, draft.metrics());
    }

    private DashboardSnapshotDraft draftWithMetricState(
            Instant asOf,
            long sourceVersion,
            Long reservationCount,
            String checkpoint,
            MetricCompleteness completeness
    ) {
        DashboardSnapshotDraft base = draftAt(asOf, 4L, "base");
        MetricMetadata metadata = new MetricMetadata(
                "ANALYTICS-001-v1", sourceVersion, asOf,
                completeness == COMPLETE ? asOf.minusSeconds(1) : null,
                checkpoint, completeness, false,
                completeness == COMPLETE ? null : SOURCE_FAILED);
        List<DashboardMetricDraft> metrics = base.metrics().stream()
                .map(metric -> metric.metricKey().equals("TODAY_RESERVATION_TEAMS")
                        ? new DashboardMetricDraft(
                                metric.metricKey(),
                                reservationCount == null
                                        ? null : objectMapper.valueToTree(reservationCount),
                                metadata)
                        : metric)
                .toList();
        return new DashboardSnapshotDraft(
                base.storeId(), base.businessDate(), base.timeZoneId(), asOf,
                asOf.plusSeconds(1), base.storeAuthorityVersion(), metrics);
    }

    private DashboardSnapshotDraft draftWithNoShowState(
            Instant asOf,
            boolean reservationAvailable,
            boolean waitingAvailable
    ) {
        DashboardSnapshotDraft base = draftAt(asOf, 4L, "base");
        CountMetricResponse candidate = new CountMetricResponse(null, new MetricMetadata(
                "analytics-004-reservation-no-show-candidate-v1", 1L, asOf,
                null, null, UNAVAILABLE, false, SOURCE_CONTRACT_MISSING));
        CountMetricResponse reservation = noShowCount(
                "analytics-004-reservation-no-show-confirmed-v1",
                reservationAvailable ? 7L : 1L,
                reservationAvailable ? 1L : null,
                reservationAvailable ? "reservation:7" : null,
                asOf,
                reservationAvailable);
        CountMetricResponse waiting = noShowCount(
                "analytics-004-waiting-no-show-confirmed-v1",
                waitingAvailable ? 20L : 1L,
                waitingAvailable ? 2L : null,
                waitingAvailable ? "waiting:20" : null,
                asOf,
                waitingAvailable);
        MetricMetadata top = new MetricMetadata(
                "analytics-004-no-show-v2",
                Math.addExact(reservation.metadata().aggregationVersion(),
                        waiting.metadata().aggregationVersion()),
                asOf,
                reservationAvailable || waitingAvailable ? AS_OF.minusSeconds(1) : null,
                "reservation:" + reservationAvailable + "|waiting:" + waitingAvailable,
                PARTIAL,
                false,
                SOURCE_CONTRACT_MISSING);
        NoShowValue value = new NoShowValue(candidate, reservation, waiting);
        List<DashboardMetricDraft> metrics = base.metrics().stream()
                .map(metric -> metric.metricKey().equals("NO_SHOW_STATUS")
                        ? new DashboardMetricDraft(
                                metric.metricKey(), objectMapper.valueToTree(value), top)
                        : metric)
                .toList();
        return new DashboardSnapshotDraft(
                base.storeId(), base.businessDate(), base.timeZoneId(), asOf,
                asOf.plusSeconds(1), base.storeAuthorityVersion(), metrics);
    }

    private static CountMetricResponse noShowCount(
            String definitionVersion,
            long aggregationVersion,
            Long value,
            String checkpoint,
            Instant asOf,
            boolean available
    ) {
        return new CountMetricResponse(value, new MetricMetadata(
                definitionVersion,
                aggregationVersion,
                asOf,
                available ? AS_OF.minusSeconds(1) : null,
                checkpoint,
                available ? COMPLETE : UNAVAILABLE,
                false,
                available ? null : SOURCE_FAILED));
    }

    private DashboardSnapshotDraft draftAt(
            Instant asOf,
            long reservationCount,
            String checkpoint
    ) {
        MetricMetadata complete = new MetricMetadata(
                "ANALYTICS-v1", 3L, asOf, asOf.minusSeconds(1),
                checkpoint, COMPLETE, false, null);
        MetricMetadata partial = new MetricMetadata(
                "ANALYTICS-004-v1", 3L, asOf, asOf.minusSeconds(1),
                "checkpoint", PARTIAL, false, SOURCE_CONTRACT_MISSING);
        MetricMetadata unavailable = new MetricMetadata(
                "ANALYTICS-004-v1", 1L, asOf, null, null,
                UNAVAILABLE, false, SOURCE_CONTRACT_MISSING);
        NoShowValue noShow = new NoShowValue(
                new CountMetricResponse(null, unavailable),
                new CountMetricResponse(null, unavailable),
                new CountMetricResponse(2L, complete));
        return new DashboardSnapshotDraft(
                17L, LocalDate.ofInstant(asOf, java.time.ZoneId.of("Asia/Seoul")),
                "Asia/Seoul", asOf, asOf.plusSeconds(1), 1L, List.of(
                new DashboardMetricDraft("TODAY_RESERVATION_TEAMS",
                        objectMapper.valueToTree(reservationCount), complete),
                new DashboardMetricDraft("RESERVATION_RATE",
                        objectMapper.valueToTree(new RateValue(6, 10, new BigDecimal("0.6"))),
                        complete),
                new DashboardMetricDraft("TEAM_CAPACITY_UTILIZATION",
                        objectMapper.valueToTree(new RateValue(3, 5, new BigDecimal("0.6"))),
                        complete),
                new DashboardMetricDraft("CANCELLATION_RATE",
                        objectMapper.valueToTree(new RateValue(1, 4, new BigDecimal("0.25"))),
                        complete),
                new DashboardMetricDraft("WAITING_STATUS",
                        objectMapper.valueToTree(new WaitingValue(1, 0, 3, 0, 1800L)),
                        complete),
                new DashboardMetricDraft("NO_SHOW_STATUS",
                        objectMapper.valueToTree(noShow), partial)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock dashboardRetentionTestClock() {
            return Clock.fixed(AS_OF, ZoneOffset.UTC);
        }
    }
}
