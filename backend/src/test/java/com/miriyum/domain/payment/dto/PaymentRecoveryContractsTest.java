package com.miriyum.domain.payment.dto;

import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryAction.REQUERY_PROVIDER_RESULT;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind.REFUND_RESULT_UNKNOWN;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryResultStatus.UNKNOWN;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType.RESERVATION_DEPOSIT_REFUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentRecoveryContractsTest {

    @Test
    @DisplayName("수동 복구 handoff 등록은 Reservation의 양수 source ID만 허용한다")
    void rejectsInvalidSourceId() {
        assertThatThrownBy(() -> new PaymentRecoveryContracts.RegisterManualRecoveryHandoffCommand(
                RESERVATION_DEPOSIT_REFUND,
                "0",
                "900000000000000001",
                "reservation:1:cancelled",
                "550e8400-e29b-41d4-a716-446655440000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceId");
    }

    @Test
    @DisplayName("수동 복구 handoff 등록은 정규화된 UUID 멱등 키를 요구한다")
    void rejectsInvalidRegistrationIdempotencyKey() {
        assertThatThrownBy(() -> new PaymentRecoveryContracts.RegisterManualRecoveryHandoffCommand(
                RESERVATION_DEPOSIT_REFUND,
                "31",
                "900000000000000001",
                "reservation:1:cancelled",
                "not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    @DisplayName("복구 조회 결과는 허용 작업 집합을 방어적으로 복사한다")
    void copiesAllowedActions() {
        Set<PaymentRecoveryContracts.ManualRecoveryAction> actions =
                new HashSet<>(Set.of(REQUERY_PROVIDER_RESULT));

        PaymentRecoveryContracts.ManualRecoveryInspection inspection =
                new PaymentRecoveryContracts.ManualRecoveryInspection(
                        "920000000000000001",
                        3L,
                        4L,
                        2L,
                        REFUND_RESULT_UNKNOWN,
                        300_000L,
                        100_000L,
                        200_000L,
                        "KRW",
                        UNKNOWN,
                        actions,
                        "port********1234");
        actions.clear();

        assertThat(inspection.allowedActions()).containsExactly(REQUERY_PROVIDER_RESULT);
    }

    @Test
    @DisplayName("복구 조회 결과에는 provider 원문 식별자를 넣을 수 없다")
    void rejectsUnmaskedProviderReference() {
        assertThatThrownBy(() -> new PaymentRecoveryContracts.ManualRecoveryInspection(
                "920000000000000001",
                3L,
                4L,
                2L,
                REFUND_RESULT_UNKNOWN,
                300_000L,
                100_000L,
                200_000L,
                "KRW",
                UNKNOWN,
                Set.of(REQUERY_PROVIDER_RESULT),
                "payment-secret-provider-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maskedProviderReference");
    }

    @Test
    @DisplayName("환불 복구 실행은 새 실행을 구분할 정규화된 operation ID를 요구한다")
    void requiresNormalizedOperationId() {
        assertThatThrownBy(() -> new PaymentRecoveryContracts.RequestManualRecoveryRefundCommand(
                "920000000000000001", 3L, 4L, 2L, "retry-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operationId");
    }
}
