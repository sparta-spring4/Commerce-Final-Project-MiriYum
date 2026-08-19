package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.payment.dto.PaymentContracts.ApplyReservationDepositDispositionCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionResult;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.GetReservationDepositDispositionQuery;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.entity.ReservationDepositDispositionObligation;
import com.miriyum.domain.reservation.repository.ReservationDepositDispositionObligationRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-c")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.task.scheduling.enabled=false",
            "miriyum.reservation.deposit-worker.enabled=false",
            "miriyum.reservation.hold-expiration.enabled=false",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.payment.cursor-secret=test-history-cursor-secret-with-enough-entropy",
            "miriyum.payment.portone.api-secret=test-api-secret",
            "miriyum.payment.portone.webhook-secret=whsec_dGVzdC1zZWNyZXQ=",
            "miriyum.payment.portone.store-id=store-1"
        })
class ReservationDepositDispositionRuntimeIT {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-17T00:00:00Z");
    private static final String OBLIGATION_KEY =
            "550e8400-e29b-41d4-a716-446655440239";
    private static final String CANCELLATION_KEY =
            "550e8400-e29b-41d4-a716-446655440240";
    private static final String SOURCE_EVENT_ID =
            "reservation-cancel:41:" + CANCELLATION_KEY;

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @Autowired
    private ReservationDepositDispositionObligationRepository repository;

    @Autowired
    private ReservationDepositDispositionJob job;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private PaymentService paymentService;

    @BeforeEach
    void cleanObligations() {
        repository.deleteAll();
    }

    @Test
    void paymentApplyRunsAfterClaimCommitOutsideTransactionThenResultCommits() {
        ReservationDepositDispositionObligation obligation = repository.saveAndFlush(
                pending(ReservationDepositDispositionObligation.Operation.APPLY));
        ApplyReservationDepositDispositionCommand command =
                new ApplyReservationDepositDispositionCommand(
                        "51",
                        SOURCE_EVENT_ID,
                        "RESERVATION_CANCELLED",
                        null,
                        2L,
                        "CONSUMER",
                        0,
                        OBLIGATION_KEY);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM reservation_deposit_disposition_obligations "
                            + "WHERE reservation_deposit_disposition_obligation_id = ?",
                    String.class,
                    obligation.getId())).isEqualTo("PROCESSING");
            return completed();
        }).when(paymentService).applyReservationDepositDisposition(eq(command));

        assertThat(job.runOnce("worker-runtime", 10)).isOne();

        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, attempt_count, lease_owner, lease_until,
                       original_amount_minor, target_refund_amount_minor,
                       completed_refund_amount_minor, withheld_amount_minor,
                       payment_disposition_status
                  FROM reservation_deposit_disposition_obligations
                 WHERE reservation_deposit_disposition_obligation_id = ?
                """, obligation.getId()))
                .containsEntry("status", "COMPLETED")
                .containsEntry("attempt_count", 1)
                .containsEntry("lease_owner", null)
                .containsEntry("lease_until", null)
                .containsEntry("original_amount_minor", 10_001L)
                .containsEntry("target_refund_amount_minor", 0L)
                .containsEntry("completed_refund_amount_minor", 0L)
                .containsEntry("withheld_amount_minor", 10_001L)
                .containsEntry("payment_disposition_status", "COMPLETED");
    }

    @Test
    void reconciliationModeQueriesStoredPaymentResultWithoutApplyingAgain() {
        ReservationDepositDispositionObligation obligation = repository.saveAndFlush(
                pending(ReservationDepositDispositionObligation.Operation.APPLY));
        jdbcTemplate.update("""
                UPDATE reservation_deposit_disposition_obligations
                   SET status = 'RECONCILIATION_REQUIRED',
                       next_operation = 'QUERY',
                       next_attempt_at = UTC_TIMESTAMP(6),
                       disposition_id = ?,
                       original_amount_minor = 10001,
                       target_refund_amount_minor = 0,
                       incremental_refund_amount_minor = 0,
                       completed_refund_amount_minor = 0,
                       withheld_amount_minor = 10001,
                       currency = 'KRW',
                       payment_disposition_status = 'RECONCILIATION_REQUIRED',
                       failure_classification = 'UNKNOWN',
                       payment_requested_at = UTC_TIMESTAMP(6),
                       payment_updated_at = UTC_TIMESTAMP(6)
                 WHERE reservation_deposit_disposition_obligation_id = ?
                """, OBLIGATION_KEY, obligation.getId());
        GetReservationDepositDispositionQuery query =
                new GetReservationDepositDispositionQuery("51", SOURCE_EVENT_ID);
        given(paymentService.getReservationDepositDisposition(query))
                .willReturn(completed());

        assertThat(job.runOnce("worker-query", 10)).isOne();

        then(paymentService).should().getReservationDepositDisposition(query);
        then(paymentService).should(never())
                .applyReservationDepositDisposition(
                        org.mockito.ArgumentMatchers.any());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM reservation_deposit_disposition_obligations "
                        + "WHERE reservation_deposit_disposition_obligation_id = ?",
                String.class,
                obligation.getId())).isEqualTo("COMPLETED");
    }

    private static ReservationDepositDispositionObligation pending(
            ReservationDepositDispositionObligation.Operation ignored
    ) {
        return ReservationDepositDispositionObligation.pending(
                31L,
                41L,
                "51",
                SOURCE_EVENT_ID,
                "RESERVATION_CANCELLED",
                null,
                2L,
                "CONSUMER",
                0,
                OBLIGATION_KEY,
                CANCELLATION_KEY,
                REQUESTED_AT);
    }

    private static DispositionResult completed() {
        return new DispositionResult(
                OBLIGATION_KEY,
                "51",
                SOURCE_EVENT_ID,
                "RESERVATION_CANCELLED",
                null,
                2L,
                "CONSUMER",
                0,
                10_001L,
                0L,
                0L,
                0L,
                10_001L,
                "KRW",
                null,
                DispositionStatus.COMPLETED,
                null,
                REQUESTED_AT,
                REQUESTED_AT.plusSeconds(1),
                REQUESTED_AT.plusSeconds(1));
    }
}
