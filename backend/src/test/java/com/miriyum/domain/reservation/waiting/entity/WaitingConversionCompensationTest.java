package com.miriyum.domain.reservation.waiting.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WaitingConversionCompensationTest {
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    @Test
    void pendingWorkKeepsItsImmutableRefundIdentity() {
        WaitingConversionCompensation work = pending();

        assertThat(work.matchesRequired(
                11L,
                "101",
                12_000L,
                "KRW",
                3L,
                "waiting-conversion-cancelled:11",
                "550e8400-e29b-41d4-a716-446655440301",
                "WAITING_CANCELLED"))
                .isTrue();
        assertThat(work.matchesRequired(
                11L,
                "101",
                12_001L,
                "KRW",
                3L,
                "waiting-conversion-cancelled:11",
                "550e8400-e29b-41d4-a716-446655440301",
                "WAITING_CANCELLED"))
                .isFalse();
        assertThat(work.getStatus()).isEqualTo(WaitingConversionCompensationStatus.PENDING);
        assertThat(work.getNextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void claimUsesMonotonicFenceAndRejectsStaleOwnerAfterReclaim() {
        WaitingConversionCompensation work = pending();
        work.claim("worker-a", NOW, NOW.plusSeconds(30));
        long staleToken = work.getClaimToken();

        assertThat(work.isOwnedBy("worker-a", staleToken, NOW.plusSeconds(1))).isTrue();
        work.claim("worker-b", NOW.plusSeconds(31), NOW.plusSeconds(61));

        assertThat(work.getClaimToken()).isGreaterThan(staleToken);
        assertThatThrownBy(() -> work.complete("worker-a", staleToken, NOW.plusSeconds(32)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> work.requeue(
                "worker-a", staleToken, NOW.plusSeconds(32), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> work.requireReconciliation(
                "worker-a", staleToken, NOW.plusSeconds(32)))
                .isInstanceOf(IllegalStateException.class);

        work.complete("worker-b", work.getClaimToken(), NOW.plusSeconds(32));
        assertThat(work.getStatus()).isEqualTo(WaitingConversionCompensationStatus.COMPLETED);
        assertThat(work.getCompletedAt()).isEqualTo(NOW.plusSeconds(32));
        assertThat(work.getLeaseOwner()).isNull();
        assertThat(work.getLeaseUntil()).isNull();
    }

    @Test
    void invalidPaymentIdentityIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> WaitingConversionCompensation.pending(
                11L,
                "payment-101",
                12_000L,
                "KRW",
                3L,
                "waiting-conversion-cancelled:11",
                "550e8400-e29b-41d4-a716-446655440301",
                "WAITING_CANCELLED",
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paymentId");
    }

    private static WaitingConversionCompensation pending() {
        return WaitingConversionCompensation.pending(
                11L,
                "101",
                12_000L,
                "KRW",
                3L,
                "waiting-conversion-cancelled:11",
                "550e8400-e29b-41d4-a716-446655440301",
                "WAITING_CANCELLED",
                NOW);
    }
}
