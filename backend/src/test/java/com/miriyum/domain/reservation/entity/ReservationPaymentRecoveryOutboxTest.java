package com.miriyum.domain.reservation.entity;

import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType.RESERVATION_DEPOSIT_REFUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationPaymentRecoveryOutboxTest {

    private static final Instant NOW = Instant.parse("2026-08-19T01:00:00Z");

    @Test
    @DisplayName("복구 outbox는 claim token과 owner가 다른 완료를 거부한다")
    void rejectsStaleDelivery() {
        ReservationPaymentRecoveryOutbox outbox = pending();
        outbox.claim("worker-a", NOW, NOW.plusSeconds(30));

        assertThatThrownBy(() -> outbox.deliver(
                "worker-b", outbox.getClaimToken(), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
    }

    @Test
    @DisplayName("만료된 claim은 새 token으로만 재선점할 수 있다")
    void reclaimsExpiredLeaseWithNewToken() {
        ReservationPaymentRecoveryOutbox outbox = pending();
        outbox.claim("worker-a", NOW, NOW.plusSeconds(30));
        long expiredToken = outbox.getClaimToken();

        outbox.claim("worker-b", NOW.plusSeconds(30), NOW.plusSeconds(60));

        assertThat(outbox.getClaimToken()).isEqualTo(expiredToken + 1);
        assertThatThrownBy(() -> outbox.deliver(
                "worker-a", expiredToken, NOW.plusSeconds(31)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("재시도는 lease를 비우고 지정 지연 뒤에만 다시 claim 가능하게 한다")
    void requeuesAfterDelay() {
        ReservationPaymentRecoveryOutbox outbox = pending();
        outbox.claim("worker-a", NOW, NOW.plusSeconds(30));
        long token = outbox.getClaimToken();

        outbox.requeue("worker-a", token, NOW.plusSeconds(1), Duration.ofSeconds(20));

        assertThatThrownBy(() -> outbox.claim(
                "worker-b", NOW.plusSeconds(20), NOW.plusSeconds(50)))
                .isInstanceOf(IllegalStateException.class);
        outbox.claim("worker-b", NOW.plusSeconds(21), NOW.plusSeconds(51));
        assertThat(outbox.getClaimToken()).isEqualTo(token + 1);
    }

    private static ReservationPaymentRecoveryOutbox pending() {
        return ReservationPaymentRecoveryOutbox.pending(
                RESERVATION_DEPOSIT_REFUND,
                "31",
                "900000000000000001",
                "reservation:1:cancelled",
                "550e8400-e29b-41d4-a716-446655440000",
                NOW);
    }
}
