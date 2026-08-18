package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReservationDepositDispositionObligationTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final String OBLIGATION_KEY =
            "550e8400-e29b-41d4-a716-446655440239";
    private static final String CANCELLATION_KEY =
            "550e8400-e29b-41d4-a716-446655440240";

    @Test
    void createsImmutablePendingV2Obligation() {
        ReservationDepositDispositionObligation obligation = pending();

        assertThat(obligation.getReservationDepositProcessId()).isEqualTo(31L);
        assertThat(obligation.getReservationId()).isEqualTo(41L);
        assertThat(obligation.getPaymentId()).isEqualTo("51");
        assertThat(obligation.getSourceEventId())
                .isEqualTo("reservation-cancel:41:" + CANCELLATION_KEY);
        assertThat(obligation.getSourceEventType()).isEqualTo("RESERVATION_CANCELLED");
        assertThat(obligation.getPolicyVersion()).isEqualTo(2L);
        assertThat(obligation.getResponsibilityCode()).isEqualTo("CONSUMER");
        assertThat(obligation.getTargetRefundRateBasisPoints()).isEqualTo(5_000);
        assertThat(obligation.getObligationKey()).isEqualTo(OBLIGATION_KEY);
        assertThat(obligation.getCancellationIdempotencyKey()).isEqualTo(CANCELLATION_KEY);
        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositDispositionObligation.Status.PENDING);
        assertThat(obligation.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(obligation.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void claimsDueAndExpiredWorkWithMonotonicFencing() {
        ReservationDepositDispositionObligation obligation = pending();

        obligation.claim("worker-a", NOW, NOW.plusSeconds(30));

        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositDispositionObligation.Status.PROCESSING);
        assertThat(obligation.getAttemptCount()).isOne();
        assertThat(obligation.getClaimToken()).isOne();
        assertThat(obligation.isOwnedBy("worker-a", 1L, NOW.plusSeconds(29))).isTrue();

        obligation.claim("worker-b", NOW.plusSeconds(30), NOW.plusSeconds(60));

        assertThat(obligation.getAttemptCount()).isEqualTo(2);
        assertThat(obligation.getClaimToken()).isEqualTo(2L);
        assertThat(obligation.isOwnedBy("worker-a", 1L, NOW.plusSeconds(31))).isFalse();
        assertThat(obligation.isOwnedBy("worker-b", 2L, NOW.plusSeconds(31))).isTrue();
    }

    @Test
    void recordsCompletedPaymentSnapshotOnlyForCurrentFence() {
        ReservationDepositDispositionObligation obligation = pending();
        obligation.claim("worker-a", NOW, NOW.plusSeconds(30));
        ReservationDepositDispositionObligation.PaymentSnapshot snapshot = completedSnapshot();

        assertThatIllegalStateException().isThrownBy(() -> obligation.complete(
                "worker-b", 1L, NOW.plusSeconds(1), snapshot));

        obligation.complete("worker-a", 1L, NOW.plusSeconds(1), snapshot);

        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositDispositionObligation.Status.COMPLETED);
        assertThat(obligation.getDispositionId()).isEqualTo(OBLIGATION_KEY);
        assertThat(obligation.getRefundId()).isEqualTo("71");
        assertThat(obligation.getOriginalAmountMinor()).isEqualTo(10_001L);
        assertThat(obligation.getTargetRefundAmountMinor()).isEqualTo(5_000L);
        assertThat(obligation.getCompletedRefundAmountMinor()).isEqualTo(5_000L);
        assertThat(obligation.getWithheldAmountMinor()).isEqualTo(5_001L);
        assertThat(obligation.getCurrency()).isEqualTo("KRW");
        assertThat(obligation.getPaymentDispositionStatus()).isEqualTo("COMPLETED");
        assertThat(obligation.getCompletedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(obligation.getLeaseOwner()).isNull();
        assertThat(obligation.getLeaseUntil()).isNull();
    }

    @Test
    void separatesRetryReconciliationAndRecoveryTerminalState() {
        ReservationDepositDispositionObligation obligation = pending();
        obligation.claim("worker", NOW, NOW.plusSeconds(30));
        obligation.requeue(
                "worker", 1L, NOW.plusSeconds(1), Duration.ofSeconds(5));
        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositDispositionObligation.Status.PENDING);
        assertThat(obligation.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(6));

        obligation.claim("worker", NOW.plusSeconds(6), NOW.plusSeconds(36));
        obligation.requireReconciliation(
                "worker", 2L, NOW.plusSeconds(7), Duration.ofSeconds(5), unknownSnapshot());
        assertThat(obligation.getStatus()).isEqualTo(
                ReservationDepositDispositionObligation.Status.RECONCILIATION_REQUIRED);
        assertThat(obligation.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(12));
        assertThat(obligation.getFailureClassification()).isEqualTo("UNKNOWN");

        obligation.claim("worker", NOW.plusSeconds(12), NOW.plusSeconds(42));
        obligation.requireRecovery("worker", 3L, NOW.plusSeconds(13), permanentSnapshot());
        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositDispositionObligation.Status.RECOVERY_REQUIRED);
        assertThat(obligation.getNextAttemptAt()).isNull();
        assertThat(obligation.getFailureClassification()).isEqualTo("PERMANENT");
    }

    @Test
    void queryRequeueRequiresCurrentFenceAndPreservesQueryOnlyState() {
        ReservationDepositDispositionObligation obligation = pending();
        obligation.claim("worker", NOW, NOW.plusSeconds(30));
        obligation.requireReconciliation(
                "worker", 1L, NOW.plusSeconds(1), Duration.ZERO, unknownSnapshot());
        obligation.claim("worker", NOW.plusSeconds(1), NOW.plusSeconds(31));

        assertThatIllegalStateException().isThrownBy(() -> obligation.requeueQuery(
                "stale-worker",
                2L,
                NOW.plusSeconds(2),
                Duration.ofSeconds(5),
                null));

        obligation.requeueQuery(
                "worker",
                2L,
                NOW.plusSeconds(2),
                Duration.ofSeconds(5),
                null);

        assertThat(obligation.getStatus()).isEqualTo(
                ReservationDepositDispositionObligation.Status.RECONCILIATION_REQUIRED);
        assertThat(obligation.getNextOperation())
                .isEqualTo(ReservationDepositDispositionObligation.Operation.QUERY);
        assertThat(obligation.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(7));
        assertThat(obligation.getLeaseOwner()).isNull();
        assertThat(obligation.getLeaseUntil()).isNull();
    }

    @Test
    void rejectsInvalidIdentityAndPolicyScalars() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationDepositDispositionObligation.pending(
                        31L, 41L, "payment-51", "event", "RESERVATION_CANCELLED", null,
                        2L, "CONSUMER", 5_000, OBLIGATION_KEY, CANCELLATION_KEY, NOW));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationDepositDispositionObligation.pending(
                        31L, 41L, "51", "event", "RESERVATION_CANCELLED", null,
                        1L, "CONSUMER", 5_000, OBLIGATION_KEY, CANCELLATION_KEY, NOW));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationDepositDispositionObligation.pending(
                        31L, 41L, "51", "event", "RESERVATION_CANCELLED", null,
                        2L, "FREE_TEXT", 5_000, OBLIGATION_KEY, CANCELLATION_KEY, NOW));
    }

    private static ReservationDepositDispositionObligation pending() {
        return ReservationDepositDispositionObligation.pending(
                31L,
                41L,
                "51",
                "reservation-cancel:41:" + CANCELLATION_KEY,
                "RESERVATION_CANCELLED",
                null,
                2L,
                "CONSUMER",
                5_000,
                OBLIGATION_KEY,
                CANCELLATION_KEY,
                NOW
        );
    }

    private static ReservationDepositDispositionObligation.PaymentSnapshot completedSnapshot() {
        return new ReservationDepositDispositionObligation.PaymentSnapshot(
                OBLIGATION_KEY, "71", 10_001L, 5_000L, 5_000L, 5_000L, 5_001L,
                "KRW", "COMPLETED", null, NOW, NOW.plusSeconds(1), NOW.plusSeconds(1));
    }

    private static ReservationDepositDispositionObligation.PaymentSnapshot unknownSnapshot() {
        return new ReservationDepositDispositionObligation.PaymentSnapshot(
                OBLIGATION_KEY, "71", 10_001L, 5_000L, 5_000L, 0L, 10_001L,
                "KRW", "RECONCILIATION_REQUIRED", "UNKNOWN",
                NOW, NOW.plusSeconds(7), null);
    }

    private static ReservationDepositDispositionObligation.PaymentSnapshot permanentSnapshot() {
        return new ReservationDepositDispositionObligation.PaymentSnapshot(
                OBLIGATION_KEY, "71", 10_001L, 5_000L, 5_000L, 0L, 10_001L,
                "KRW", "FAILED", "PERMANENT", NOW, NOW.plusSeconds(13), null);
    }
}
