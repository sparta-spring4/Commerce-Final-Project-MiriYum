package com.miriyum.domain.reservation.service;

import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistrationStatus.NOT_REQUIRED;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistrationStatus.REGISTERED;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType.RESERVATION_DEPOSIT_REFUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistration;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.config.ReservationDepositProcessConfig;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationPaymentRecoveryHandoffJobTest {

    @Test
    @DisplayName("조건부 scheduled adapter는 복구 handoff job을 한 번 실행한다")
    void scheduledAdapterDelegatesToJob() {
        ReservationPaymentRecoveryHandoffJob job =
                mock(ReservationPaymentRecoveryHandoffJob.class);
        ReservationDepositProcessConfig.RecoveryHandoffScheduledWorker worker =
                new ReservationDepositProcessConfig.RecoveryHandoffScheduledWorker(job);

        worker.runScheduled();

        verify(job).runScheduled();
    }

    @Test
    @DisplayName("Payment가 handoff를 등록하면 Reservation outbox를 전달 완료한다")
    void deliversRegisteredHandoff() {
        ReservationPaymentRecoveryOutboxService outbox =
                mock(ReservationPaymentRecoveryOutboxService.class);
        PaymentService payment = mock(PaymentService.class);
        ReservationPaymentRecoveryOutboxService.Claim claim = claim();
        when(outbox.claimDue("worker-a", 10)).thenReturn(List.of(claim));
        when(payment.registerManualRecoveryHandoff(claim.toCommand()))
                .thenReturn(new ManualRecoveryRegistration(
                        REGISTERED, "920000000000000001"));
        when(outbox.recordDelivered(claim)).thenReturn(true);
        ReservationPaymentRecoveryHandoffJob job =
                new ReservationPaymentRecoveryHandoffJob(
                        outbox, payment, "worker-a", 10);

        assertThat(job.runOnce("worker-a", 10)).isEqualTo(1);

        verify(outbox).recordDelivered(claim);
        verify(outbox, never()).recordRetry(claim, Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("Payment 상태가 이미 수렴했으면 Admin 사건 없이 outbox를 전달 완료한다")
    void completesNotRequiredHandoff() {
        ReservationPaymentRecoveryOutboxService outbox =
                mock(ReservationPaymentRecoveryOutboxService.class);
        PaymentService payment = mock(PaymentService.class);
        ReservationPaymentRecoveryOutboxService.Claim claim = claim();
        when(outbox.claimDue("worker-a", 10)).thenReturn(List.of(claim));
        when(payment.registerManualRecoveryHandoff(claim.toCommand()))
                .thenReturn(new ManualRecoveryRegistration(NOT_REQUIRED, null));
        when(outbox.recordDelivered(claim)).thenReturn(true);
        ReservationPaymentRecoveryHandoffJob job =
                new ReservationPaymentRecoveryHandoffJob(
                        outbox, payment, "worker-a", 10);

        assertThat(job.runOnce("worker-a", 10)).isEqualTo(1);
        verify(outbox).recordDelivered(claim);
    }

    @Test
    @DisplayName("Payment 일시 장애는 outbox를 전달 완료하지 않고 지연 재시도한다")
    void retriesUnavailablePayment() {
        ReservationPaymentRecoveryOutboxService outbox =
                mock(ReservationPaymentRecoveryOutboxService.class);
        PaymentService payment = mock(PaymentService.class);
        ReservationPaymentRecoveryOutboxService.Claim claim = claim();
        when(outbox.claimDue("worker-a", 10)).thenReturn(List.of(claim));
        when(payment.registerManualRecoveryHandoff(claim.toCommand()))
                .thenThrow(new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE));
        ReservationPaymentRecoveryHandoffJob job =
                new ReservationPaymentRecoveryHandoffJob(
                        outbox, payment, "worker-a", 10);

        assertThat(job.runOnce("worker-a", 10)).isZero();

        verify(outbox).recordRetry(claim, Duration.ofSeconds(30));
        verify(outbox, never()).recordDelivered(claim);
    }

    private static ReservationPaymentRecoveryOutboxService.Claim claim() {
        return new ReservationPaymentRecoveryOutboxService.Claim(
                41L,
                RESERVATION_DEPOSIT_REFUND,
                "31",
                "900000000000000001",
                "reservation:1:cancelled",
                "550e8400-e29b-41d4-a716-446655440000",
                "worker-a",
                1L);
    }
}
