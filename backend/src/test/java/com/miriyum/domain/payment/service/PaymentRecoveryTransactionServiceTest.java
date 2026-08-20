package com.miriyum.domain.payment.service;

import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind.REFUND_RESULT_UNKNOWN;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryAction.REQUERY_PROVIDER_RESULT;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryResultStatus.UNKNOWN;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistrationStatus.ALREADY_REGISTERED;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistrationStatus.NOT_REQUIRED;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistrationStatus.REGISTERED;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType.RESERVATION_DEPOSIT_REFUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.AcknowledgeManualRecoveryHandoffCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ClaimManualRecoveryHandoffsCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistration;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.InspectManualRecoveryQuery;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.PreviewManualRecoveryRefundQuery;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.RequestManualRecoveryRefundCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ReconcileManualRecoveryCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ReconcileRefundResultQuery;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.RegisterManualRecoveryHandoffCommand;
import com.miriyum.domain.payment.entity.Payment;
import com.miriyum.domain.payment.entity.PaymentRecoveryHandoff;
import com.miriyum.domain.payment.entity.PaymentRefund;
import com.miriyum.domain.payment.entity.ReservationDepositDisposition;
import com.miriyum.domain.payment.repository.PaymentRecoveryHandoffRepository;
import com.miriyum.domain.payment.repository.PaymentRefundRepository;
import com.miriyum.domain.payment.repository.PaymentRepository;
import com.miriyum.domain.payment.repository.ReservationDepositDispositionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentRecoveryTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T01:00:00Z");
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";

    private PaymentRecoveryHandoffRepository handoffs;
    private PaymentRepository payments;
    private PaymentRefundRepository refunds;
    private ReservationDepositDispositionRepository dispositions;
    private PaymentRecoveryTransactionService service;

    @BeforeEach
    void setUp() {
        handoffs = mock(PaymentRecoveryHandoffRepository.class);
        payments = mock(PaymentRepository.class);
        refunds = mock(PaymentRefundRepository.class);
        dispositions = mock(ReservationDepositDispositionRepository.class);
        service = new PaymentRecoveryTransactionService(
                handoffs, payments, refunds, dispositions,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("결과 불명 환불 source는 Payment가 kind를 판정해 handoff 하나로 등록한다")
    void registersUnknownRefund() {
        Payment payment = paidPayment();
        PaymentRefund refund = refund(payment);
        refund.requireReconciliation(NOW.minusSeconds(5));
        when(handoffs.findBySourceTypeAndSourceIdForUpdate(
                RESERVATION_DEPOSIT_REFUND, "31")).thenReturn(Optional.empty());
        when(payments.findByPaymentIdForUpdate("900000000000000001"))
                .thenReturn(Optional.of(payment));
        when(refunds.findByPayment_IdAndSourceEventIdForUpdate(
                11L, "reservation:1:cancelled")).thenReturn(Optional.of(refund));
        when(handoffs.saveAndFlush(any())).thenAnswer(invocation -> {
            PaymentRecoveryHandoff saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 21L);
            return saved;
        });

        ManualRecoveryRegistration result = service.register(command());

        assertThat(result.status()).isEqualTo(REGISTERED);
        assertThat(result.handoffId()).isEqualTo("21");
    }

    @Test
    @DisplayName("자동 환불 대사는 handoff 없이 canonical UNKNOWN 환불만 조회 대상으로 claim한다")
    void claimsAutomaticUnknownRefundWithoutHandoff() {
        Payment payment = paidPayment();
        PaymentRefund refund = refund(payment);
        refund.requireReconciliation(NOW.minusSeconds(5));
        ReconcileRefundResultQuery query = new ReconcileRefundResultQuery(
                "900000000000000001", "reservation:1:cancelled", 100_000L, "KRW");
        when(payments.findByPaymentIdForUpdate("900000000000000001"))
                .thenReturn(Optional.of(payment));
        when(refunds.findByPayment_IdAndSourceEventIdForUpdate(
                11L, "reservation:1:cancelled")).thenReturn(Optional.of(refund));

        var claim = service.claimAutomaticRefundReconciliation(query);

        assertThat(claim.requiresProviderLookup()).isTrue();
        assertThat(claim.refundClaim().refundId()).isEqualTo(refund.getRefundId());
        assertThat(claim.completedResult().status())
                .isEqualTo(com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus
                        .RECONCILIATION_REQUIRED);
    }

    @Test
    @DisplayName("동일 등록 replay는 새 handoff 없이 기존 ID로 수렴한다")
    void replaysMatchingRegistration() {
        PaymentRecoveryHandoff existing = PaymentRecoveryHandoff.register(
                RESERVATION_DEPOSIT_REFUND, "31", 11L,
                "900000000000000001", "reservation:1:cancelled",
                REFUND_RESULT_UNKNOWN, KEY, NOW.minusSeconds(1));
        ReflectionTestUtils.setField(existing, "id", 21L);
        when(handoffs.findBySourceTypeAndSourceIdForUpdate(
                RESERVATION_DEPOSIT_REFUND, "31")).thenReturn(Optional.of(existing));

        ManualRecoveryRegistration result = service.register(command());

        assertThat(result).isEqualTo(new ManualRecoveryRegistration(
                ALREADY_REGISTERED, "21"));
    }

    @Test
    @DisplayName("이미 완료된 환불은 수동 사건 handoff를 만들지 않는다")
    void skipsAlreadyCompletedRefund() {
        Payment payment = paidPayment();
        PaymentRefund refund = refund(payment);
        refund.complete("cancel-1", NOW.minusSeconds(5));
        when(handoffs.findBySourceTypeAndSourceIdForUpdate(
                RESERVATION_DEPOSIT_REFUND, "31")).thenReturn(Optional.empty());
        when(payments.findByPaymentIdForUpdate("900000000000000001"))
                .thenReturn(Optional.of(payment));
        when(refunds.findByPayment_IdAndSourceEventIdForUpdate(
                11L, "reservation:1:cancelled")).thenReturn(Optional.of(refund));

        assertThat(service.register(command()))
                .isEqualTo(new ManualRecoveryRegistration(NOT_REQUIRED, null));
    }

    @Test
    @DisplayName("canonical 환불이 없는 결정적 실패 handoff는 영구 재시도하지 않는다")
    void skipsUnsupportedFailureWithoutCanonicalRefund() {
        Payment payment = paidPayment();
        when(handoffs.findBySourceTypeAndSourceIdForUpdate(
                RESERVATION_DEPOSIT_REFUND, "31")).thenReturn(Optional.empty());
        when(payments.findByPaymentIdForUpdate("900000000000000001"))
                .thenReturn(Optional.of(payment));
        when(refunds.findByPayment_IdAndSourceEventIdForUpdate(
                11L, "reservation:1:cancelled")).thenReturn(Optional.empty());

        assertThat(service.register(command()))
                .isEqualTo(new ManualRecoveryRegistration(NOT_REQUIRED, null));
    }

    @Test
    @DisplayName("claim과 acknowledgement는 handoff lease token을 보존한다")
    void claimsAndAcknowledges() {
        PaymentRecoveryHandoff handoff = PaymentRecoveryHandoff.register(
                RESERVATION_DEPOSIT_REFUND, "31", 11L,
                "900000000000000001", "reservation:1:cancelled",
                REFUND_RESULT_UNKNOWN, KEY, NOW.minusSeconds(1));
        ReflectionTestUtils.setField(handoff, "id", 21L);
        when(handoffs.findClaimableForUpdate(any(), any(Pageable.class)))
                .thenReturn(List.of(handoff));
        when(handoffs.findByIdForUpdate(21L)).thenReturn(Optional.of(handoff));

        var claims = service.claim(new ClaimManualRecoveryHandoffsCommand("intake-a", 10));
        service.acknowledge(new AcknowledgeManualRecoveryHandoffCommand(
                "21", "intake-a", claims.getFirst().claimToken(), "recovery-case-1"));

        assertThat(handoff.getStatus()).isEqualTo(PaymentRecoveryHandoff.Status.ACKNOWLEDGED);
        assertThat(handoff.getAdminCaseId()).isEqualTo("recovery-case-1");
    }

    @Test
    @DisplayName("결과 불명 환불 조회는 버전과 금액을 제공하고 provider ID는 마스킹한다")
    void inspectsUnknownRefundWithoutExposingProviderId() {
        Payment payment = paidPayment();
        PaymentRefund refund = refund(payment);
        refund.requireReconciliation(NOW.minusSeconds(5));
        PaymentRecoveryHandoff handoff = PaymentRecoveryHandoff.register(
                RESERVATION_DEPOSIT_REFUND, "31", 11L,
                "900000000000000001", "reservation:1:cancelled",
                REFUND_RESULT_UNKNOWN, KEY, NOW.minusSeconds(1));
        ReflectionTestUtils.setField(handoff, "id", 21L);
        ReflectionTestUtils.setField(handoff, "rowVersion", 3L);
        ReflectionTestUtils.setField(payment, "version", 4L);
        ReflectionTestUtils.setField(refund, "version", 5L);
        when(handoffs.findByIdForUpdate(21L)).thenReturn(Optional.of(handoff));
        when(payments.findByPaymentIdForUpdate("900000000000000001"))
                .thenReturn(Optional.of(payment));
        when(refunds.findByPayment_IdAndSourceEventIdForUpdate(
                11L, "reservation:1:cancelled")).thenReturn(Optional.of(refund));

        var result = service.inspect(new InspectManualRecoveryQuery("21"));

        assertThat(result.handoffVersion()).isEqualTo(3L);
        assertThat(result.paymentVersion()).isEqualTo(4L);
        assertThat(result.recoveryVersion()).isEqualTo(5L);
        assertThat(result.originalAmountMinor()).isEqualTo(300_000L);
        assertThat(result.cumulativeRefundedAmountMinor()).isZero();
        assertThat(result.remainingRefundableAmountMinor()).isEqualTo(300_000L);
        assertThat(result.resultStatus()).isEqualTo(UNKNOWN);
        assertThat(result.allowedActions()).containsExactly(REQUERY_PROVIDER_RESULT);
        assertThat(result.maskedProviderReference())
                .matches("port\\*{8}[A-Za-z0-9_-]{1,4}")
                .doesNotContain("provider-payment-1");
    }

    @Test
    @DisplayName("실패 환불 preview는 기존 환불 금액과 identity만 반환한다")
    void previewsCanonicalFailedRefund() {
        Payment payment = paidPayment();
        PaymentRefund refund = refund(payment);
        refund.fail(NOW.minusSeconds(5));
        PaymentRecoveryHandoff handoff = failedRefundHandoff();
        ReflectionTestUtils.setField(handoff, "rowVersion", 3L);
        ReflectionTestUtils.setField(payment, "version", 4L);
        ReflectionTestUtils.setField(refund, "version", 5L);
        when(handoffs.findByIdForUpdate(21L)).thenReturn(Optional.of(handoff));
        when(payments.findByPaymentIdForUpdate("900000000000000001"))
                .thenReturn(Optional.of(payment));
        when(refunds.findByPayment_IdAndSourceEventIdForUpdate(
                11L, "reservation:1:cancelled")).thenReturn(Optional.of(refund));

        var preview = service.preview(new PreviewManualRecoveryRefundQuery("21"));

        assertThat(preview.requestedAmountMinor()).isEqualTo(100_000L);
        assertThat(preview.originalAmountMinor()).isEqualTo(300_000L);
        assertThat(preview.refundVersion()).isEqualTo(5L);
        assertThat(preview.retryable()).isTrue();
    }

    @Test
    @DisplayName("복구 환불 명령은 caller 값 없이 기존 환불 identity로만 구성한다")
    void claimsCanonicalFailedRefundForRetry() {
        Payment payment = paidPayment();
        PaymentRefund refund = refund(payment);
        refund.fail(NOW.minusSeconds(5));
        PaymentRecoveryHandoff handoff = failedRefundHandoff();
        ReflectionTestUtils.setField(handoff, "rowVersion", 3L);
        ReflectionTestUtils.setField(payment, "version", 4L);
        ReflectionTestUtils.setField(refund, "version", 5L);
        when(handoffs.findByIdForUpdate(21L)).thenReturn(Optional.of(handoff));
        when(payments.findByPaymentIdForUpdate("900000000000000001"))
                .thenReturn(Optional.of(payment));
        when(refunds.findByPayment_IdAndSourceEventIdForUpdate(
                11L, "reservation:1:cancelled")).thenReturn(Optional.of(refund));

        var claim = service.claimRefundExecution(new RequestManualRecoveryRefundCommand(
                "21", 3L, 4L, 5L,
                "550e8400-e29b-41d4-a716-446655440099"));

        assertThat(claim.command()).isEqualTo(new com.miriyum.domain.payment.dto
                .PaymentContracts.RequestRefundCommand(
                "900000000000000001", "reservation:1:cancelled", 100_000L,
                "RESERVATION_CANCELLED", 1L, KEY));
        assertThat(claim.replayResult()).isNull();
    }

    @Test
    @DisplayName("실패 환불 재실행이 결과 불명이 되면 같은 handoff에서 재조회한다")
    void reconcilesUnknownResultAfterFailedRefundRetry() {
        Payment payment = paidPayment();
        PaymentRefund refund = refund(payment);
        refund.requireReconciliation(NOW.minusSeconds(5));
        PaymentRecoveryHandoff handoff = failedRefundHandoff();
        handoff.beginOperation("550e8400-e29b-41d4-a716-446655440099",
                NOW.minusSeconds(3));
        handoff.finishOperation("550e8400-e29b-41d4-a716-446655440099",
                PaymentRecoveryHandoff.OperationStatus.UNKNOWN, NOW.minusSeconds(2));
        ReflectionTestUtils.setField(handoff, "rowVersion", 3L);
        ReflectionTestUtils.setField(payment, "version", 4L);
        ReflectionTestUtils.setField(refund, "version", 5L);
        when(handoffs.findByIdForUpdate(21L)).thenReturn(Optional.of(handoff));
        when(payments.findByPaymentIdForUpdate("900000000000000001"))
                .thenReturn(Optional.of(payment));
        when(refunds.findByPayment_IdAndSourceEventIdForUpdate(
                11L, "reservation:1:cancelled")).thenReturn(Optional.of(refund));

        var claim = service.claimReconciliation(new ReconcileManualRecoveryCommand(
                "21", 3L, 4L, 5L));

        assertThat(claim.target()).isEqualTo(
                PaymentRecoveryTransactionService.ReconciliationTarget.REFUND);
        assertThat(claim.refundClaim().refundId()).isEqualTo("910000000000000001");
    }

    @Test
    @DisplayName("외부 성공 후 handoff finalize 전 crash는 canonical 완료 상태로 operation을 회복한다")
    void healsProcessingOperationFromCompletedCanonicalRefund() {
        Payment payment = paidPayment();
        PaymentRefund refund = refund(payment);
        payment.applyCompletedRefund(100_000L, NOW.minusSeconds(4));
        refund.complete("cancel-1", NOW.minusSeconds(4));
        PaymentRecoveryHandoff handoff = failedRefundHandoff();
        String operationId = "550e8400-e29b-41d4-a716-446655440099";
        handoff.beginOperation(operationId, NOW.minusSeconds(5));
        ReflectionTestUtils.setField(handoff, "rowVersion", 3L);
        ReflectionTestUtils.setField(payment, "version", 4L);
        ReflectionTestUtils.setField(refund, "version", 5L);
        when(handoffs.findByIdForUpdate(21L)).thenReturn(Optional.of(handoff));
        when(payments.findByPaymentIdForUpdate("900000000000000001"))
                .thenReturn(Optional.of(payment));
        when(refunds.findByPayment_IdAndSourceEventIdForUpdate(
                11L, "reservation:1:cancelled")).thenReturn(Optional.of(refund));

        var replay = service.claimRefundExecution(
                new RequestManualRecoveryRefundCommand(
                        "21", 3L, 4L, 5L, operationId));

        assertThat(replay.replayResult().status()).isEqualTo(
                com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus.COMPLETED);
        assertThat(handoff.getOperationStatus()).isEqualTo(
                PaymentRecoveryHandoff.OperationStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("실패 처분에 연결된 canonical 실패 환불은 동일 identity로 preview한다")
    void previewsCanonicalRefundAttachedToFailedDisposition() {
        Payment payment = paidPayment();
        PaymentRefund refund = refund(payment);
        refund.fail(NOW.minusSeconds(5));
        ReservationDepositDisposition disposition = ReservationDepositDisposition.create(
                payment, "920000000000000001", "reservation:1:cancelled",
                "RESERVATION_CANCELLED", null, 1L, "CONSUMER", 5000,
                150_000L, 0L,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                NOW.minusSeconds(20));
        disposition.failRetryable(refund.getRefundId(), NOW.minusSeconds(5));
        PaymentRecoveryHandoff handoff = PaymentRecoveryHandoff.register(
                com.miriyum.domain.payment.dto.PaymentRecoveryContracts
                        .ManualRecoverySourceType.RESERVATION_DEPOSIT_DISPOSITION,
                "41", 11L, "900000000000000001", "reservation:1:cancelled",
                com.miriyum.domain.payment.dto.PaymentRecoveryContracts
                        .ManualRecoveryKind.DISPOSITION_FAILED,
                "550e8400-e29b-41d4-a716-446655440041", NOW.minusSeconds(1));
        ReflectionTestUtils.setField(handoff, "id", 31L);
        when(handoffs.findByIdForUpdate(31L)).thenReturn(Optional.of(handoff));
        when(payments.findByPaymentIdForUpdate("900000000000000001"))
                .thenReturn(Optional.of(payment));
        when(dispositions.findByPayment_IdAndSourceEventIdForUpdate(
                11L, "reservation:1:cancelled")).thenReturn(Optional.of(disposition));
        when(refunds.findByRefundIdForUpdate("910000000000000001"))
                .thenReturn(Optional.of(refund));

        var preview = service.preview(new PreviewManualRecoveryRefundQuery("31"));

        assertThat(preview.requestedAmountMinor()).isEqualTo(100_000L);
        assertThat(preview.retryable()).isTrue();
    }

    private static PaymentRecoveryHandoff failedRefundHandoff() {
        PaymentRecoveryHandoff handoff = PaymentRecoveryHandoff.register(
                RESERVATION_DEPOSIT_REFUND, "31", 11L,
                "900000000000000001", "reservation:1:cancelled",
                com.miriyum.domain.payment.dto.PaymentRecoveryContracts
                        .ManualRecoveryKind.REFUND_FAILED,
                KEY, NOW.minusSeconds(1));
        ReflectionTestUtils.setField(handoff, "id", 21L);
        return handoff;
    }

    private static RegisterManualRecoveryHandoffCommand command() {
        return new RegisterManualRecoveryHandoffCommand(
                RESERVATION_DEPOSIT_REFUND, "31", "900000000000000001",
                "reservation:1:cancelled", KEY);
    }

    private static Payment paidPayment() {
        Payment payment = Payment.prepare(
                "900000000000000001", "RESERVATION_DEPOSIT", "1", 7L, 1L,
                NOW.plusSeconds(3600),
                "550e8400-e29b-41d4-a716-446655440010",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                101L, 300_000L, "KRW", "provider-payment-1",
                "예약금", NOW.minusSeconds(100));
        ReflectionTestUtils.setField(payment, "id", 11L);
        payment.beginConfirmation(NOW.minusSeconds(90));
        payment.markPaid("provider-transaction-1", NOW.minusSeconds(80));
        return payment;
    }

    private static PaymentRefund refund(Payment payment) {
        PaymentRefund refund = PaymentRefund.request(
                "910000000000000001", payment, KEY,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "reservation:1:cancelled", 100_000L,
                "RESERVATION_CANCELLED", 1L, NOW.minusSeconds(20));
        ReflectionTestUtils.setField(refund, "id", 12L);
        return refund;
    }
}
