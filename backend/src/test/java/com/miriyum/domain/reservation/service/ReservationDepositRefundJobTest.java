package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.service.PaymentService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class ReservationDepositRefundJobTest {

    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    @Test
    void callsPaymentOutsideJobTransactionThenRecordsResultInNewTransaction()
            throws NoSuchMethodException {
        ReservationDepositRefundService refundService =
                mock(ReservationDepositRefundService.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositRefundService.Claim claim =
                new ReservationDepositRefundService.Claim(
                        501L,
                        99L,
                        "9001",
                        4_000L,
                        "KRW",
                        1L,
                        "reservation-deposit-compensation:99",
                        "123e4567-e89b-12d3-a456-426614174099",
                        "FULL_DEPOSIT_COMPENSATION",
                        "worker-a",
                        1L);
        RefundResult refund = new RefundResult(
                "7001",
                "9001",
                4_000L,
                4_000L,
                4_000L,
                0L,
                "KRW",
                RefundStatus.COMPLETED,
                Instant.parse("2026-08-16T12:00:00Z"),
                Instant.parse("2026-08-16T12:00:01Z"));
        given(refundService.claimDue("worker-a", 10)).willReturn(List.of(claim));
        given(paymentService.requestRefund(new RequestRefundCommand(
                "9001",
                "reservation-deposit-compensation:99",
                4_000L,
                "FULL_DEPOSIT_COMPENSATION",
                1L,
                "123e4567-e89b-12d3-a456-426614174099"))).willReturn(refund);
        given(refundService.recordCompleted(claim, refund)).willReturn(true);
        ReservationDepositRefundJob job = new ReservationDepositRefundJob(
                refundService, paymentService);

        int completed = job.runOnce("worker-a", 10);

        assertThat(completed).isEqualTo(1);
        var order = inOrder(refundService, paymentService);
        order.verify(refundService).claimDue("worker-a", 10);
        order.verify(paymentService).requestRefund(new RequestRefundCommand(
                "9001",
                "reservation-deposit-compensation:99",
                4_000L,
                "FULL_DEPOSIT_COMPENSATION",
                1L,
                "123e4567-e89b-12d3-a456-426614174099"));
        order.verify(refundService).recordCompleted(claim, refund);

        assertThat(ReservationDepositRefundJob.class
                .getMethod("runOnce", String.class, int.class)
                .getAnnotation(Transactional.class)).isNull();
        Transactional resultTransaction = ReservationDepositRefundService.class
                .getMethod(
                        "recordCompleted",
                        ReservationDepositRefundService.Claim.class,
                        RefundResult.class)
                .getAnnotation(Transactional.class);
        assertThat(resultTransaction).isNotNull();
        assertThat(resultTransaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void requeuesExternalFailureAndLeavesBatchRunnable() {
        ReservationDepositRefundService refundService =
                mock(ReservationDepositRefundService.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositRefundService.Claim claim =
                new ReservationDepositRefundService.Claim(
                        501L,
                        99L,
                        "9001",
                        4_000L,
                        "KRW",
                        1L,
                        "reservation-deposit-compensation:99",
                        "123e4567-e89b-12d3-a456-426614174099",
                        "FULL_DEPOSIT_COMPENSATION",
                        "worker-a",
                        1L);
        RequestRefundCommand command = new RequestRefundCommand(
                "9001",
                "reservation-deposit-compensation:99",
                4_000L,
                "FULL_DEPOSIT_COMPENSATION",
                1L,
                "123e4567-e89b-12d3-a456-426614174099");
        given(refundService.claimDue("worker-a", 10)).willReturn(List.of(claim));
        given(paymentService.requestRefund(command))
                .willThrow(new IllegalStateException("temporary payment failure"));
        given(refundService.recordRetryableFailure(claim, RETRY_DELAY)).willReturn(true);
        ReservationDepositRefundJob job = new ReservationDepositRefundJob(
                refundService, paymentService);

        assertThat(job.runOnce("worker-a", 10)).isZero();

        then(refundService).should().recordRetryableFailure(claim, RETRY_DELAY);
    }

    @Test
    void routesUnknownRefundResultToReconciliationInsteadOfCompletion() {
        ReservationDepositRefundService refundService =
                mock(ReservationDepositRefundService.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositRefundService.Claim claim =
                new ReservationDepositRefundService.Claim(
                        501L,
                        99L,
                        "9001",
                        4_000L,
                        "KRW",
                        1L,
                        "reservation-deposit-compensation:99",
                        "123e4567-e89b-12d3-a456-426614174099",
                        "FULL_DEPOSIT_COMPENSATION",
                        "worker-a",
                        1L);
        RequestRefundCommand command = new RequestRefundCommand(
                "9001",
                "reservation-deposit-compensation:99",
                4_000L,
                "FULL_DEPOSIT_COMPENSATION",
                1L,
                "123e4567-e89b-12d3-a456-426614174099");
        RefundResult unknown = new RefundResult(
                "7001",
                "9001",
                4_000L,
                0L,
                0L,
                4_000L,
                "KRW",
                RefundStatus.RECONCILIATION_REQUIRED,
                Instant.parse("2026-08-16T12:00:00Z"),
                null);
        given(refundService.claimDue("worker-a", 10)).willReturn(List.of(claim));
        given(paymentService.requestRefund(command)).willReturn(unknown);
        given(refundService.recordReconciliationRequired(claim, unknown)).willReturn(true);
        ReservationDepositRefundJob job = new ReservationDepositRefundJob(
                refundService, paymentService);

        assertThat(job.runOnce("worker-a", 10)).isZero();

        then(refundService).should().claimDue("worker-a", 10);
        then(refundService).should().recordReconciliationRequired(claim, unknown);
        then(refundService).shouldHaveNoMoreInteractions();
    }

    @Test
    void requeuesExplicitFailedRefundResult() {
        ReservationDepositRefundService refundService =
                mock(ReservationDepositRefundService.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositRefundService.Claim claim =
                new ReservationDepositRefundService.Claim(
                        501L,
                        99L,
                        "9001",
                        4_000L,
                        "KRW",
                        1L,
                        "reservation-deposit-compensation:99",
                        "123e4567-e89b-12d3-a456-426614174099",
                        "FULL_DEPOSIT_COMPENSATION",
                        "worker-a",
                        1L);
        RequestRefundCommand command = new RequestRefundCommand(
                "9001",
                "reservation-deposit-compensation:99",
                4_000L,
                "FULL_DEPOSIT_COMPENSATION",
                1L,
                "123e4567-e89b-12d3-a456-426614174099");
        RefundResult failed = new RefundResult(
                "7001",
                "9001",
                4_000L,
                0L,
                0L,
                4_000L,
                "KRW",
                RefundStatus.FAILED,
                Instant.parse("2026-08-16T12:00:00Z"),
                Instant.parse("2026-08-16T12:00:01Z"));
        given(refundService.claimDue("worker-a", 10)).willReturn(List.of(claim));
        given(paymentService.requestRefund(command)).willReturn(failed);
        given(refundService.recordRetryableFailure(claim, RETRY_DELAY)).willReturn(true);
        ReservationDepositRefundJob job = new ReservationDepositRefundJob(
                refundService, paymentService);

        assertThat(job.runOnce("worker-a", 10)).isZero();

        then(refundService).should().recordRetryableFailure(claim, RETRY_DELAY);
    }
}
