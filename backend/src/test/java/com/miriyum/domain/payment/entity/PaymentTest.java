package com.miriyum.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-11T01:00:00Z");
    private static final Instant PAID_AT = Instant.parse("2026-08-11T01:05:00Z");

    @Test
    @DisplayName("예약금 결제를 내부 PK와 분리된 공개 참조로 준비한다")
    void preparesReservationDeposit() {
        Payment payment = preparedPayment();

        assertThat(payment.getPaymentId()).isEqualTo("900000000000000001");
        assertThat(payment.getSourceType()).isEqualTo("RESERVATION_DEPOSIT");
        assertThat(payment.getSourceReferenceId()).isEqualTo("123");
        assertThat(payment.getStoreId()).isEqualTo(12L);
        assertThat(payment.getSourcePolicyVersion()).isEqualTo(7L);
        assertThat(payment.getConsumerAccountId()).isEqualTo(11L);
        assertThat(payment.getAmountMinor()).isEqualTo(30_000L);
        assertThat(payment.getRefundedAmountMinor()).isZero();
        assertThat(payment.getCurrency()).isEqualTo("KRW");
        assertThat(payment.getPortOnePaymentId()).isEqualTo("payment-reservation-900000000000000001");
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.READY);
        assertThat(payment.getLastAttemptStatus()).isEqualTo(Payment.AttemptStatus.NOT_STARTED);
    }

    @Test
    @DisplayName("서버 검증을 시작한 결제만 PAID로 확정한다")
    void confirmsVerifiedPayment() {
        Payment payment = preparedPayment();

        payment.beginConfirmation(CREATED_AT.plusSeconds(30));
        payment.markPaid("transaction-1", PAID_AT);

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.PAID);
        assertThat(payment.getLastAttemptStatus()).isEqualTo(Payment.AttemptStatus.PAID);
        assertThat(payment.getProviderTransactionId()).isEqualTo("transaction-1");
        assertThat(payment.getPaidAt()).isEqualTo(PAID_AT);
    }

    @Test
    @DisplayName("동일 transaction 확정 재시도는 원장 상태를 바꾸지 않는다")
    void treatsSameProviderConfirmationAsIdempotent() {
        Payment payment = paidPayment();

        payment.markPaid("transaction-1", PAID_AT.plusSeconds(30));

        assertThat(payment.getPaidAt()).isEqualTo(PAID_AT);
        assertThat(payment.getProviderTransactionId()).isEqualTo("transaction-1");
    }

    @Test
    @DisplayName("이미 확정된 결제에 다른 transaction을 매핑하지 않는다")
    void rejectsDifferentProviderTransactionAfterConfirmation() {
        Payment payment = paidPayment();

        assertThatThrownBy(() -> payment.markPaid("transaction-2", PAID_AT.plusSeconds(30)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(PaymentErrorCode.PROVIDER_MAPPING_MISMATCH);
    }

    @Test
    @DisplayName("PortOne 결과를 확정할 수 없으면 자동 성공 대신 대사 필요로 격리한다")
    void isolatesUnknownProviderResult() {
        Payment payment = preparedPayment();
        payment.beginConfirmation(CREATED_AT.plusSeconds(30));

        payment.markReconciliationRequired(CREATED_AT.plusSeconds(60));

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.RECONCILIATION_REQUIRED);
        assertThat(payment.getLastAttemptStatus()).isEqualTo(Payment.AttemptStatus.UNKNOWN);
        assertThat(payment.getPaidAt()).isNull();
    }

    @Test
    @DisplayName("완료된 환불 합계에 따라 부분 환불과 전액 환불 상태를 계산한다")
    void appliesCompletedRefunds() {
        Payment payment = paidPayment();

        payment.applyCompletedRefund(10_000L, PAID_AT.plusSeconds(60));
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.PARTIALLY_REFUNDED);
        assertThat(payment.getRefundedAmountMinor()).isEqualTo(10_000L);
        assertThat(payment.getRefundableAmountMinor()).isEqualTo(20_000L);

        payment.applyCompletedRefund(20_000L, PAID_AT.plusSeconds(120));
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.REFUNDED);
        assertThat(payment.getRefundedAmountMinor()).isEqualTo(30_000L);
        assertThat(payment.getRefundableAmountMinor()).isZero();
    }

    @Test
    @DisplayName("잔여 결제액보다 큰 환불을 원장에 적용하지 않는다")
    void rejectsRefundBeyondPaidAmount() {
        Payment payment = paidPayment();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> payment.applyCompletedRefund(30_001L, PAID_AT.plusSeconds(60)));
        assertThat(payment.getRefundedAmountMinor()).isZero();
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.PAID);
    }

    @Test
    @DisplayName("금액과 소유자 식별자는 양수이고 통화는 ISO 대문자 3자리여야 한다")
    void rejectsInvalidPreparationSnapshot() {
        assertThatIllegalArgumentException().isThrownBy(() -> Payment.prepare(
                "900000000000000001", "RESERVATION_DEPOSIT", "123", 12L, 7L,
                CREATED_AT.plusSeconds(600), "550e8400-e29b-41d4-a716-446655440000",
                "a".repeat(64), 0L, 30_000L, "KRW",
                "payment-reservation-900000000000000001", "MiriYum 예약금 123", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> Payment.prepare(
                "900000000000000001", "RESERVATION_DEPOSIT", "123", 12L, 7L,
                CREATED_AT.plusSeconds(600), "550e8400-e29b-41d4-a716-446655440000",
                "a".repeat(64), 11L, 0L, "KRW",
                "payment-reservation-900000000000000001", "MiriYum 예약금 123", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> Payment.prepare(
                "900000000000000001", "RESERVATION_DEPOSIT", "123", 12L, 7L,
                CREATED_AT.plusSeconds(600), "550e8400-e29b-41d4-a716-446655440000",
                "a".repeat(64), 11L, 30_000L, "krw",
                "payment-reservation-900000000000000001", "MiriYum 예약금 123", CREATED_AT));
    }

    @Test
    @DisplayName("결제 source와 모니터링 사건 유형의 조합이 다르면 준비하지 않는다")
    void rejectsMonitoringCaseTypeThatContradictsPaymentSource() {
        assertThatIllegalArgumentException().isThrownBy(() -> Payment.prepare(
                "900000000000000001", "WAITING_RESERVATION_DEPOSIT", "123",
                "RESERVATION", "123", 12L, 7L,
                CREATED_AT.plusSeconds(600), "550e8400-e29b-41d4-a716-446655440000",
                "a".repeat(64), 11L, 30_000L, "KRW",
                "payment-reservation-900000000000000001", "MiriYum 예약금 123", CREATED_AT));
    }

    private static Payment preparedPayment() {
        return Payment.prepare(
                "900000000000000001",
                "RESERVATION_DEPOSIT",
                "123",
                12L,
                7L,
                CREATED_AT.plusSeconds(600),
                "550e8400-e29b-41d4-a716-446655440000",
                "a".repeat(64),
                11L,
                30_000L,
                "KRW",
                "payment-reservation-900000000000000001",
                "MiriYum 예약금 123",
                CREATED_AT
        );
    }

    private static Payment paidPayment() {
        Payment payment = preparedPayment();
        payment.beginConfirmation(CREATED_AT.plusSeconds(30));
        payment.markPaid("transaction-1", PAID_AT);
        return payment;
    }
}
