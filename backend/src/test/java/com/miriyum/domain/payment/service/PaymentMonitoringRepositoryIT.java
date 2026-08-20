package com.miriyum.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.payment.dto.PaymentMonitoringContracts;
import com.miriyum.domain.payment.port.PaymentProviderClient;
import com.miriyum.domain.payment.repository.PaymentMonitoringSnapshotRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.payment.cursor-secret=test-history-cursor-secret-with-enough-entropy",
            "miriyum.payment.portone.api-secret=test-api-secret",
            "miriyum.payment.portone.webhook-secret=whsec_dGVzdC1zZWNyZXQ=",
            "miriyum.payment.portone.store-id=store-1"
        }
)
class PaymentMonitoringRepositoryIT {

    private static final Instant HISTORY_CREATED =
            Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final Instant HISTORY_PAID = HISTORY_CREATED.plusSeconds(600);
    private static final Instant HISTORY_REFUNDED = HISTORY_PAID.plusSeconds(60);
    private static final Instant BULK_CHANGED = HISTORY_REFUNDED.plusSeconds(60);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PaymentMonitoringQueryService service;
    @Autowired PaymentMonitoringSnapshotRepository snapshotRepository;
    @MockitoBean PaymentProviderClient providerClient;

    @Test
    void reconstructsHistoricalStateAndPaginatesPastOneHundredWithStoreFilter() {
        insertConsumer();
        insertPayment("900000000000000001", "91", 12L, HISTORY_CREATED);
        updatePayment("900000000000000001", "PAID", 0L, HISTORY_PAID);
        updatePayment("900000000000000001", "REFUNDED", 10_000L, HISTORY_REFUNDED);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_monitoring_snapshots
                 WHERE payment_id = '900000000000000001'
                """, Long.class)).isEqualTo(3L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_monitoring_snapshots
                 WHERE payment_id = '900000000000000001'
                   AND status_changed_at <= ?
                """, Long.class, dbTimestamp(HISTORY_PAID.plusSeconds(1))))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM payment_monitoring_snapshots s
                 WHERE s.source_type = 'RESERVATION_DEPOSIT'
                   AND FIND_IN_SET(s.source_reference_id, '91') > 0
                   AND s.payment_monitoring_snapshot_id = (
                       SELECT latest.payment_monitoring_snapshot_id
                         FROM payment_monitoring_snapshots latest
                        WHERE latest.payment_pk = s.payment_pk
                          AND latest.status_changed_at <= ?
                        ORDER BY latest.status_changed_at DESC,
                                 latest.payment_monitoring_snapshot_id DESC
                        LIMIT 1
                   )
                """, Long.class, dbTimestamp(HISTORY_PAID.plusSeconds(1))))
                .isEqualTo(1L);
        assertThat(snapshotRepository.findLatestCases(
                "RESERVATION_HOLD", "91",
                LocalDateTime.ofInstant(HISTORY_PAID.plusSeconds(1), ZoneOffset.UTC)))
                .hasSize(1);
        PaymentMonitoringContracts.SourceCell historical = service.findCases(
                        new PaymentMonitoringContracts.BatchQuery(
                                HISTORY_PAID.plusSeconds(1), List.of("reservation-hold:91")))
                .cells().getFirst();

        assertThat(historical.state().sourceStatus()).isEqualTo("PAID");
        assertThat(historical.state().statusVersion()).isEqualTo(1L);
        assertThat(historical.state().statusChangedAt()).isEqualTo(HISTORY_PAID);

        List<Object[]> refunds = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            refunds.add(refundArguments(index));
        }
        jdbcTemplate.batchUpdate(insertRefundSql(), refunds);
        PaymentMonitoringContracts.Detail detail = service.findCase(
                new PaymentMonitoringContracts.DetailQuery(
                        HISTORY_REFUNDED.plusSeconds(1), "reservation-hold:91"))
                .orElseThrow();

        assertThat(detail.refunds()).hasSize(100);
        assertThat(detail.refundsTruncated()).isTrue();

        List<Object[]> batch = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            String paymentId = Long.toString(900_000_000_000_001_000L + index);
            String sourceReference = Integer.toString(1_000 + index);
            batch.add(paymentArguments(paymentId, sourceReference, 12L, BULK_CHANGED));
        }
        batch.add(paymentArguments(
                "900000000000000999", "9999", 13L, BULK_CHANGED));
        jdbcTemplate.batchUpdate(insertSql(), batch);

        PaymentMonitoringContracts.ChangeQuery firstQuery =
                new PaymentMonitoringContracts.ChangeQuery(
                        BULK_CHANGED.plusSeconds(1), BULK_CHANGED, BULK_CHANGED,
                        "12", Set.of("READY"), null, 100);
        PaymentMonitoringContracts.ReferencePage first = service.findChangedCases(firstQuery);

        assertThat(first.items()).hasSize(100);
        PaymentMonitoringContracts.CaseReference last = first.items().getLast();
        PaymentMonitoringContracts.ReferencePage second = service.findChangedCases(
                new PaymentMonitoringContracts.ChangeQuery(
                        firstQuery.asOf(), firstQuery.changedFrom(), firstQuery.changedTo(),
                        firstQuery.storeId(), firstQuery.sourceStatuses(),
                        new PaymentMonitoringContracts.Seek(
                                last.statusChangedAt(), last.caseId()),
                        100));

        assertThat(second.items()).hasSize(1);
        assertThat(first.items()).extracting(PaymentMonitoringContracts.CaseReference::caseId)
                .doesNotContain("reservation-hold:9999");
        assertThat(second.items()).extracting(PaymentMonitoringContracts.CaseReference::caseId)
                .doesNotContain("reservation-hold:9999");
    }

    @Test
    void directReservationCaseSupportsBatchAndDetailReads() {
        insertConsumer();
        jdbcTemplate.update("""
                INSERT INTO payments (
                    payment_id, source_type, source_reference_id, store_id,
                    monitoring_case_type, monitoring_case_reference_id,
                    source_policy_version, source_expires_at,
                    preparation_idempotency_key, preparation_request_fingerprint,
                    consumer_account_id, amount_minor, refunded_amount_minor, currency,
                    portone_payment_id, order_name, status, last_attempt_status,
                    created_at, updated_at, version
                ) VALUES (
                    '900000000000000888', 'RESERVATION_DEPOSIT', '92', 12,
                    'RESERVATION', '92', 1, ?,
                    '550e8400-e29b-41d4-a716-446655440888', REPEAT('a', 64),
                    10001, 10000, 0, 'KRW',
                    'payment-monitoring-direct-888', 'direct reservation monitoring',
                    'READY', 'NOT_STARTED', ?, ?, 0
                )
                """, dbTimestamp(HISTORY_CREATED.plusSeconds(3_600)),
                dbTimestamp(HISTORY_CREATED), dbTimestamp(HISTORY_CREATED));

        PaymentMonitoringContracts.SourceCell cell = service.findCases(
                        new PaymentMonitoringContracts.BatchQuery(
                                HISTORY_PAID.plusSeconds(1), List.of("reservation:92")))
                .cells().getFirst();
        PaymentMonitoringContracts.Detail detail = service.findCase(
                        new PaymentMonitoringContracts.DetailQuery(
                                HISTORY_PAID.plusSeconds(1), "reservation:92"))
                .orElseThrow();

        assertThat(cell.caseId()).isEqualTo("reservation:92");
        assertThat(cell.state().paymentId()).isEqualTo("900000000000000888");
        assertThat(detail.cell().caseId()).isEqualTo("reservation:92");
    }

    private void insertConsumer() {
        jdbcTemplate.update("""
                INSERT IGNORE INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name, status, created_at, updated_at
                ) VALUES (10001, 'monitoring-payment@example.com', 'hash', '결제회원',
                          'ACTIVE', NOW(6), NOW(6))
                """);
    }

    private void insertPayment(
            String paymentId,
            String sourceReference,
            long storeId,
            Instant createdAt
    ) {
        jdbcTemplate.update(insertSql(), paymentArguments(
                paymentId, sourceReference, storeId, createdAt));
    }

    private void updatePayment(
            String paymentId,
            String status,
            long refundedAmountMinor,
            Instant updatedAt
    ) {
        jdbcTemplate.update("""
                UPDATE payments
                   SET status = ?, refunded_amount_minor = ?, updated_at = ?, version = version + 1
                 WHERE payment_id = ?
                """, status, refundedAmountMinor, dbTimestamp(updatedAt), paymentId);
    }

    private static String insertSql() {
        return """
                INSERT INTO payments (
                    payment_id, source_type, source_reference_id, store_id,
                    monitoring_case_type, monitoring_case_reference_id,
                    source_policy_version, source_expires_at,
                    preparation_idempotency_key, preparation_request_fingerprint,
                    consumer_account_id, amount_minor, refunded_amount_minor, currency,
                    portone_payment_id, order_name, status, last_attempt_status,
                    created_at, updated_at, version
                ) VALUES (?, 'RESERVATION_DEPOSIT', ?, ?, 'RESERVATION_HOLD', ?,
                          1, ?, ?, REPEAT('a', 64),
                          10001, 10000, 0, 'KRW', ?, 'monitoring payment',
                          'READY', 'NOT_STARTED', ?, ?, 0)
                """;
    }

    private static Object[] paymentArguments(
            String paymentId,
            String sourceReference,
            long storeId,
            Instant createdAt
    ) {
        String idempotency = UUID.nameUUIDFromBytes(
                ("monitoring:" + paymentId).getBytes(StandardCharsets.UTF_8)).toString();
        return new Object[]{
                paymentId,
                sourceReference,
                storeId,
                sourceReference,
                dbTimestamp(createdAt.plusSeconds(3_600)),
                idempotency,
                "payment-monitoring-" + paymentId,
                dbTimestamp(createdAt),
                dbTimestamp(createdAt)
        };
    }

    private static Timestamp dbTimestamp(Instant value) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    private static String insertRefundSql() {
        return """
                INSERT INTO payment_refunds (
                    refund_id, payment_pk, idempotency_key, request_fingerprint,
                    source_event_id, amount_minor, currency, status, reason_code,
                    policy_version, provider_cancellation_id,
                    requested_at, processing_started_at, completed_at,
                    updated_at, attempt_count, version
                ) SELECT ?, payment_pk, ?, REPEAT('b', 64), ?, 1, 'KRW',
                         'REQUESTED', 'MONITORING', 1, NULL, ?, ?, NULL, ?, 1, 0
                    FROM payments WHERE payment_id = '900000000000000001'
                """;
    }

    private static Object[] refundArguments(int index) {
        String refundId = Long.toString(800_000_000_000_000_000L + index);
        String idempotency = UUID.nameUUIDFromBytes(
                ("monitoring-refund:" + index).getBytes(StandardCharsets.UTF_8)).toString();
        Instant requestedAt = HISTORY_PAID;
        return new Object[]{
                refundId, idempotency, "monitoring-refund-event-" + index,
                dbTimestamp(requestedAt), dbTimestamp(requestedAt), dbTimestamp(requestedAt)
        };
    }
}
