package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReservationDepositRefundObligationTest {

    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    @Test
    void fullDepositObligationKeepsStableIdentityAndRejectsStaleFence() {
        ReservationDepositRefundObligation obligation =
                ReservationDepositRefundObligation.required(
                        99L,
                        "9001",
                        4_000L,
                        "KRW",
                        1L,
                        "reservation-deposit-compensation:99",
                        "123e4567-e89b-12d3-a456-426614174099",
                        "FULL_DEPOSIT_COMPENSATION",
                        NOW);

        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositRefundObligation.Status.REQUIRED);
        assertThat(obligation.getIdempotencyKey())
                .isEqualTo("123e4567-e89b-12d3-a456-426614174099");
        assertThat(obligation.matchesRequired(
                99L,
                "9001",
                4_000L,
                "KRW",
                1L,
                "reservation-deposit-compensation:99",
                "123e4567-e89b-12d3-a456-426614174099",
                "FULL_DEPOSIT_COMPENSATION")).isTrue();

        obligation.claim("worker-a", NOW, NOW.plusSeconds(30));
        long staleToken = obligation.getClaimToken();
        obligation.claim("worker-b", NOW.plusSeconds(30), NOW.plusSeconds(60));

        assertThat(obligation.getClaimToken()).isGreaterThan(staleToken);
        assertThatThrownBy(() -> obligation.complete(
                "worker-a", staleToken, NOW.plusSeconds(31)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> obligation.requeue(
                "worker-a", staleToken, NOW.plusSeconds(31), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class);

        obligation.complete(
                "worker-b", obligation.getClaimToken(), NOW.plusSeconds(31));
        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositRefundObligation.Status.COMPLETED);
        assertThat(obligation.getCompletedAt()).isEqualTo(NOW.plusSeconds(31));
        assertThat(obligation.getLeaseOwner()).isNull();
    }

    @Test
    void unknownResultSchedulesAQueryClaimInsteadOfCompletingTheObligation() {
        ReservationDepositRefundObligation obligation =
                ReservationDepositRefundObligation.required(
                        99L, "9001", 4_000L, "KRW", 1L,
                        "reservation-deposit-compensation:99",
                        "123e4567-e89b-12d3-a456-426614174099",
                        "FULL_DEPOSIT_COMPENSATION", NOW);
        obligation.claim("worker-a", NOW, NOW.plusSeconds(30));

        obligation.scheduleReconciliation(
                "worker-a", obligation.getClaimToken(), NOW.plusSeconds(1),
                Duration.ofSeconds(30));

        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositRefundObligation.Status.RECONCILIATION_REQUIRED);
        assertThat(obligation.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(31));
        assertThat(obligation.getCompletedAt()).isNull();

        obligation.claim("worker-b", NOW.plusSeconds(31), NOW.plusSeconds(61));

        assertThat(obligation.getAttemptCount()).isEqualTo(2);
        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositRefundObligation.Status.PROCESSING);
    }
}
