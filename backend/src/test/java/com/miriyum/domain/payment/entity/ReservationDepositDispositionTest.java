package com.miriyum.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.payment.dto.PaymentContracts.DispositionFailureClassification;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionStatus;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationDepositDispositionTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-17T01:00:00Z");

    @Test
    @DisplayName("0퍼센트 처분은 환불 없이 즉시 완료되고 원승인 전액을 유보한다")
    void completesZeroPercentWithoutRefund() {
        ReservationDepositDisposition disposition = ReservationDepositDisposition.create(
                payment(),
                "550e8400-e29b-41d4-a716-446655440001",
                "reservation:123:cancelled",
                "RESERVATION_CANCELLED",
                null,
                2L,
                "CONSUMER",
                0,
                0L,
                0L,
                "fingerprint",
                REQUESTED_AT
        );

        assertThat(disposition.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(disposition.getIncrementalRefundAmountMinor()).isZero();
        assertThat(disposition.getCompletedRefundAmountMinor()).isZero();
        assertThat(disposition.getWithheldAmountMinor()).isEqualTo(30_000L);
        assertThat(disposition.getRefundId()).isNull();
        assertThat(disposition.getFailureClassification()).isNull();
        assertThat(disposition.getCompletedAt()).isEqualTo(REQUESTED_AT);
    }

    @Test
    @DisplayName("명시적 환불 실패만 같은 처분과 환불 참조로 재시도한다")
    void retriesExplicitFailureWithoutChangingIdentity() {
        ReservationDepositDisposition disposition = fiftyPercentDisposition();
        disposition.failRetryable("910000000000000001", REQUESTED_AT.plusSeconds(1));

        disposition.retry(REQUESTED_AT.plusSeconds(2));

        assertThat(disposition.getDispositionId())
                .isEqualTo("550e8400-e29b-41d4-a716-446655440001");
        assertThat(disposition.getRefundId()).isEqualTo("910000000000000001");
        assertThat(disposition.getStatus()).isEqualTo(DispositionStatus.PROCESSING);
        assertThat(disposition.getFailureClassification()).isNull();
        assertThat(disposition.getAttemptCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("sibling 잔액 점유는 refund 없는 retryable 처분으로 보존한다")
    void defersRetryablyWithoutRefundReference() {
        ReservationDepositDisposition disposition =
                ReservationDepositDisposition.deferRetryable(
                        payment(),
                        "550e8400-e29b-41d4-a716-446655440004",
                        "reservation:124:cancelled",
                        "RESERVATION_CANCELLED",
                        null,
                        2L,
                        "CONSUMER",
                        5000,
                        15_000L,
                        0L,
                        "deferred-fingerprint",
                        REQUESTED_AT);

        assertThat(disposition.getStatus()).isEqualTo(DispositionStatus.FAILED);
        assertThat(disposition.getFailureClassification())
                .isEqualTo(DispositionFailureClassification.RETRYABLE);
        assertThat(disposition.getRefundId()).isNull();
        assertThat(disposition.getAttemptCount()).isZero();

        disposition.retry(REQUESTED_AT.plusSeconds(1));

        assertThat(disposition.getStatus()).isEqualTo(DispositionStatus.PROCESSING);
        assertThat(disposition.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("결과 불명 처분은 재시도하거나 완료로 가장하지 않는다")
    void keepsUnknownDispositionIsolated() {
        ReservationDepositDisposition disposition = fiftyPercentDisposition();
        disposition.requireReconciliation(
                "910000000000000001", REQUESTED_AT.plusSeconds(1));

        assertThat(disposition.getStatus())
                .isEqualTo(DispositionStatus.RECONCILIATION_REQUIRED);
        assertThat(disposition.getFailureClassification())
                .isEqualTo(DispositionFailureClassification.UNKNOWN);
        assertThatThrownBy(() -> disposition.retry(REQUESTED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("결과 불명 처분은 같은 환불의 provider 조회 성공으로만 완료한다")
    void completesUnknownDispositionFromReconciliation() {
        ReservationDepositDisposition disposition = fiftyPercentDisposition();
        disposition.requireReconciliation(
                "910000000000000001", REQUESTED_AT.plusSeconds(1));

        disposition.completeReconciledRefund(
                "910000000000000001", 15_000L, REQUESTED_AT.plusSeconds(2));

        assertThat(disposition.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(disposition.getCompletedRefundAmountMinor()).isEqualTo(15_000L);
        assertThat(disposition.getFailureClassification()).isNull();
    }

    @Test
    @DisplayName("provider 조회가 명시 실패를 반환하면 결과 불명 처분을 retryable 실패로 기록한다")
    void failsUnknownDispositionRetryablyFromReconciliation() {
        ReservationDepositDisposition disposition = fiftyPercentDisposition();
        disposition.requireReconciliation(
                "910000000000000001", REQUESTED_AT.plusSeconds(1));

        disposition.failReconciledRetryable(
                "910000000000000001", REQUESTED_AT.plusSeconds(2));

        assertThat(disposition.getStatus()).isEqualTo(DispositionStatus.FAILED);
        assertThat(disposition.getFailureClassification())
                .isEqualTo(DispositionFailureClassification.RETRYABLE);
        assertThat(disposition.getCompletedRefundAmountMinor()).isZero();
    }

    @Test
    @DisplayName("완료 처분은 이후 실패나 재시도로 되돌릴 수 없다")
    void keepsCompletedDispositionTerminal() {
        ReservationDepositDisposition disposition = fiftyPercentDisposition();
        disposition.completeRefund(
                "910000000000000001", 15_000L, REQUESTED_AT.plusSeconds(1));

        assertThatThrownBy(() -> disposition.failRetryable(
                "910000000000000001", REQUESTED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> disposition.retry(REQUESTED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("완료액보다 목표액을 낮춘 정정은 실제 완료액을 보존한 영구 실패가 된다")
    void rejectsTargetDecreaseWithoutRewritingCompletedAmount() {
        ReservationDepositDisposition disposition =
                ReservationDepositDisposition.rejectPermanent(
                        payment(),
                        "550e8400-e29b-41d4-a716-446655440002",
                        "reservation:123:correction:1",
                        "RESERVATION_CANCELLATION_CORRECTED",
                        "reservation:123:cancelled",
                        2L,
                        "CONSUMER",
                        5000,
                        15_000L,
                        30_000L,
                        "correction-fingerprint",
                        REQUESTED_AT.plusSeconds(2));

        assertThat(disposition.getStatus()).isEqualTo(DispositionStatus.FAILED);
        assertThat(disposition.getFailureClassification())
                .isEqualTo(DispositionFailureClassification.PERMANENT);
        assertThat(disposition.getCompletedRefundAmountMinor()).isEqualTo(30_000L);
        assertThat(disposition.getIncrementalRefundAmountMinor()).isZero();
        assertThat(disposition.getAttemptCount()).isZero();
        assertThat(disposition.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("50퍼센트 목표액은 long 최대 원승인에서도 overflow 없이 정수 절사한다")
    void calculatesHalfRateWithoutOverflow() {
        ReservationDepositDisposition disposition = ReservationDepositDisposition.create(
                payment(Long.MAX_VALUE),
                "550e8400-e29b-41d4-a716-446655440003",
                "reservation:maximum:cancelled",
                "RESERVATION_CANCELLED",
                null,
                2L,
                "CONSUMER",
                5000,
                Long.MAX_VALUE / 2,
                0L,
                "maximum-fingerprint",
                REQUESTED_AT);

        assertThat(disposition.getTargetRefundAmountMinor())
                .isEqualTo(Long.MAX_VALUE / 2);
    }

    private static ReservationDepositDisposition fiftyPercentDisposition() {
        return ReservationDepositDisposition.create(
                payment(),
                "550e8400-e29b-41d4-a716-446655440001",
                "reservation:123:cancelled",
                "RESERVATION_CANCELLED",
                null,
                2L,
                "CONSUMER",
                5000,
                15_000L,
                0L,
                "fingerprint",
                REQUESTED_AT
        );
    }

    private static Payment payment() {
        return payment(30_000L);
    }

    private static Payment payment(long amountMinor) {
        Payment payment = Payment.prepare(
                "900000000000000001",
                "RESERVATION_DEPOSIT",
                "123",
                2L,
                REQUESTED_AT.plusSeconds(600),
                "550e8400-e29b-41d4-a716-446655440000",
                "fingerprint",
                11L,
                amountMinor,
                "KRW",
                "payment-reservation-900000000000000001",
                "MiriYum 예약금 123",
                REQUESTED_AT.minusSeconds(60)
        );
        payment.beginConfirmation(REQUESTED_AT.minusSeconds(30));
        payment.markPaid("transaction-1", REQUESTED_AT);
        return payment;
    }
}
