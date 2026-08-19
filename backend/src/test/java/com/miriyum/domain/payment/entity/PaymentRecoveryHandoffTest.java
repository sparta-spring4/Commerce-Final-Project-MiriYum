package com.miriyum.domain.payment.entity;

import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind.REFUND_RESULT_UNKNOWN;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType.RESERVATION_DEPOSIT_REFUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentRecoveryHandoffTest {

    private static final Instant NOW = Instant.parse("2026-08-19T01:00:00Z");

    @Test
    @DisplayName("handoff acknowledgement는 현재 claim owner와 token만 허용한다")
    void rejectsStaleAcknowledgement() {
        PaymentRecoveryHandoff handoff = handoff();
        handoff.claim("intake-a", NOW, NOW.plusSeconds(30));

        assertThatThrownBy(() -> handoff.acknowledge(
                "intake-b", handoff.getClaimToken(), "recovery-case-1", NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
    }

    @Test
    @DisplayName("만료된 handoff claim은 새 token으로 재선점되고 이전 token을 차단한다")
    void fencesExpiredClaim() {
        PaymentRecoveryHandoff handoff = handoff();
        handoff.claim("intake-a", NOW, NOW.plusSeconds(30));
        long expiredToken = handoff.getClaimToken();

        handoff.claim("intake-b", NOW.plusSeconds(30), NOW.plusSeconds(60));
        handoff.acknowledge(
                "intake-b", expiredToken + 1, "recovery-case-1", NOW.plusSeconds(31));

        assertThat(handoff.getStatus())
                .isEqualTo(PaymentRecoveryHandoff.Status.ACKNOWLEDGED);
        assertThatThrownBy(() -> handoff.acknowledge(
                "intake-a", expiredToken, "recovery-case-2", NOW.plusSeconds(32)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("동일 source replay는 immutable Payment identity가 모두 같아야 한다")
    void matchesImmutableRegistration() {
        PaymentRecoveryHandoff handoff = handoff();

        assertThat(handoff.matchesRegistration(
                11L,
                "900000000000000001",
                "reservation:1:cancelled",
                REFUND_RESULT_UNKNOWN,
                "550e8400-e29b-41d4-a716-446655440000"))
                .isTrue();
        assertThat(handoff.matchesRegistration(
                11L,
                "900000000000000001",
                "reservation:other",
                REFUND_RESULT_UNKNOWN,
                "550e8400-e29b-41d4-a716-446655440000"))
                .isFalse();
    }

    private static PaymentRecoveryHandoff handoff() {
        return PaymentRecoveryHandoff.register(
                RESERVATION_DEPOSIT_REFUND,
                "31",
                11L,
                "900000000000000001",
                "reservation:1:cancelled",
                REFUND_RESULT_UNKNOWN,
                "550e8400-e29b-41d4-a716-446655440000",
                NOW);
    }
}
