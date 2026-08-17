package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.config.ReservationDepositProcessConfig;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class ReservationDepositRefundJobTest {

    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    @Test
    void conditionalScheduledWorkerUsesDedicatedSchedulerAndStableLeaseOwner()
            throws Exception {
        ReservationDepositRefundService refundService =
                mock(ReservationDepositRefundService.class);
        PaymentService paymentService = mock(PaymentService.class);
        given(refundService.claimDue(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(100))).willReturn(List.of());
        ReservationDepositRefundJob job = new ReservationDepositRefundJob(
                refundService,
                paymentService);
        ReservationDepositProcessConfig.RefundScheduledWorker worker =
                new ReservationDepositProcessConfig.RefundScheduledWorker(job);
        Method scheduledMethod = ReservationDepositProcessConfig.RefundScheduledWorker.class
                .getMethod("runScheduled");
        Scheduled scheduled = scheduledMethod.getAnnotation(Scheduled.class);

        scheduledMethod.invoke(worker);
        scheduledMethod.invoke(worker);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler()).isEqualTo("reservationDepositRefundScheduler");
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("#{@reservationDepositRefundPollDelayMs}");
        ArgumentCaptor<String> owners = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(refundService, times(2))
                .claimDue(owners.capture(), org.mockito.ArgumentMatchers.eq(100));
        String owner = owners.getAllValues().getFirst();
        assertThat(owners.getAllValues()).hasSize(2).allMatch(owner::equals);
        assertThat(owner).startsWith("reservation-deposit-refund-");
        assertThat(ReservationDepositRefundJob.class
                .getMethod("runScheduled")
                .getAnnotation(Scheduled.class)).isNull();
        assertSchedulerBean("reservationDepositRefundScheduler");
    }

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

    private static void assertSchedulerBean(String beanName) {
        assertThat(ReservationDepositProcessConfig.class.getDeclaredMethods())
                .anySatisfy(method -> {
                    Bean bean = method.getAnnotation(Bean.class);
                    assertThat(bean).isNotNull();
                    assertThat(List.of(bean.name())).contains(beanName);
                });
    }
}
