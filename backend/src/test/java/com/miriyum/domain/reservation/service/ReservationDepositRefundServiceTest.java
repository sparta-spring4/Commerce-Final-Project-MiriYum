package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType.RESERVATION_DEPOSIT_REFUND;

import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.reservation.entity.ReservationDepositProcess;
import com.miriyum.domain.reservation.entity.ReservationDepositProcessStatus;
import com.miriyum.domain.reservation.entity.ReservationDepositRefundObligation;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositRefundObligationRepository;
import com.miriyum.domain.reservation.service.ReservationDepositCalculator.Calculation;
import com.miriyum.domain.reservation.service.ReservationDepositCalculator.ItemSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

class ReservationDepositRefundServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    @Test
    void claimDueLeasesObligationAndMarksProcessCompensating() {
        ReservationDepositRefundObligationRepository refundRepository =
                mock(ReservationDepositRefundObligationRepository.class);
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        ReservationDepositRefundObligation obligation = obligation();
        ReservationDepositProcess process = compensationRequiredProcess();
        given(refundRepository.findClaimableForUpdate(
                NOW, PageRequest.of(0, 1))).willReturn(List.of(obligation));
        given(processRepository.findByIdForUpdate(99L)).willReturn(Optional.of(process));
        ReservationDepositRefundService service = new ReservationDepositRefundService(
                refundRepository,
                processRepository,
                mock(ReservationPaymentRecoveryOutboxService.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));

        List<ReservationDepositRefundService.Claim> claims =
                service.claimDue("worker-a", 1);

        assertThat(claims).containsExactly(new ReservationDepositRefundService.Claim(
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
                1L));
        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositRefundObligation.Status.PROCESSING);
        assertThat(obligation.getLeaseUntil()).isEqualTo(NOW.plusSeconds(30));
        assertThat(process.getStatus())
                .isEqualTo(ReservationDepositProcessStatus.COMPENSATING);
        var order = inOrder(refundRepository, processRepository);
        order.verify(refundRepository).findClaimableForUpdate(
                NOW, PageRequest.of(0, 1));
        order.verify(processRepository).findByIdForUpdate(99L);
        order.verify(refundRepository).save(obligation);
        order.verify(processRepository).saveAndFlush(process);
    }

    @Test
    void staleClaimResultCannotCompleteReclaimedObligationOrProcess() {
        ReservationDepositRefundObligationRepository refundRepository =
                mock(ReservationDepositRefundObligationRepository.class);
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        ReservationDepositRefundObligation obligation = obligation(NOW.minusSeconds(60));
        obligation.claim("worker-a", NOW.minusSeconds(60), NOW.minusSeconds(30));
        long staleToken = obligation.getClaimToken();
        obligation.claim("worker-b", NOW.minusSeconds(20), NOW.plusSeconds(10));
        given(refundRepository.findByIdForUpdate(501L))
                .willReturn(Optional.of(obligation));
        ReservationDepositRefundService service = new ReservationDepositRefundService(
                refundRepository,
                processRepository,
                mock(ReservationPaymentRecoveryOutboxService.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
        ReservationDepositRefundService.Claim stale =
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
                        staleToken);
        RefundResult completed = new RefundResult(
                "7001",
                "9001",
                4_000L,
                4_000L,
                4_000L,
                0L,
                "KRW",
                RefundStatus.COMPLETED,
                NOW.minusSeconds(1),
                NOW);

        assertThat(service.recordCompleted(stale, completed)).isFalse();

        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositRefundObligation.Status.PROCESSING);
        assertThat(obligation.getLeaseOwner()).isEqualTo("worker-b");
        verify(processRepository, never()).findByIdForUpdate(99L);
    }

    @Test
    void retryableFailureRequeuesCurrentFencedClaimWithoutCompletingProcess() {
        ReservationDepositRefundObligationRepository refundRepository =
                mock(ReservationDepositRefundObligationRepository.class);
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        ReservationDepositRefundObligation obligation = obligation(NOW.minusSeconds(60));
        obligation.claim("worker-a", NOW.minusSeconds(10), NOW.plusSeconds(20));
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
                        obligation.getClaimToken());
        given(refundRepository.findByIdForUpdate(501L))
                .willReturn(Optional.of(obligation));
        ReservationDepositRefundService service = new ReservationDepositRefundService(
                refundRepository,
                processRepository,
                mock(ReservationPaymentRecoveryOutboxService.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));

        assertThat(service.recordRetryableFailure(claim, Duration.ofSeconds(45))).isTrue();

        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositRefundObligation.Status.REQUIRED);
        assertThat(obligation.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(45));
        assertThat(obligation.getLeaseOwner()).isNull();
        assertThat(obligation.getLeaseUntil()).isNull();
        verify(refundRepository).save(obligation);
        verify(processRepository, never()).findByIdForUpdate(99L);
    }

    @Test
    void firstUnknownRefundSchedulesAutomaticReconciliationWithoutOutbox() {
        ReservationDepositRefundObligationRepository refundRepository =
                mock(ReservationDepositRefundObligationRepository.class);
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        ReservationDepositRefundObligation obligation = obligation(NOW.minusSeconds(60));
        obligation.claim("worker-a", NOW.minusSeconds(10), NOW.plusSeconds(20));
        ReservationDepositProcess process = compensationRequiredProcess();
        process.beginCompensation(NOW.minusSeconds(10));
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
                        obligation.getClaimToken());
        RefundResult unknown = new RefundResult(
                "7001",
                "9001",
                4_000L,
                0L,
                0L,
                4_000L,
                "KRW",
                RefundStatus.RECONCILIATION_REQUIRED,
                NOW.minusSeconds(1),
                null);
        given(refundRepository.findByIdForUpdate(501L))
                .willReturn(Optional.of(obligation));
        given(processRepository.findByIdForUpdate(99L)).willReturn(Optional.of(process));
        ReservationPaymentRecoveryOutboxService outbox =
                mock(ReservationPaymentRecoveryOutboxService.class);
        ReservationDepositRefundService service = new ReservationDepositRefundService(
                refundRepository,
                processRepository,
                outbox,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));

        assertThat(service.recordReconciliationRequired(
                claim, unknown, Duration.ofSeconds(30), 3)).isTrue();

        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositRefundObligation.Status.RECONCILIATION_REQUIRED);
        assertThat(process.getStatus())
                .isEqualTo(ReservationDepositProcessStatus.COMPENSATING);
        assertThat(obligation.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        verify(outbox, never()).enqueue(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(processRepository, never()).findByIdForUpdate(99L);
    }

    @Test
    void exhaustedUnknownRefundCreatesExactlyOneRecoveryHandoff() {
        ReservationDepositRefundObligationRepository refundRepository =
                mock(ReservationDepositRefundObligationRepository.class);
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        ReservationDepositRefundObligation obligation = obligation(NOW.minusSeconds(90));
        obligation.claim("worker-1", NOW.minusSeconds(90), NOW.minusSeconds(80));
        obligation.scheduleReconciliation("worker-1", obligation.getClaimToken(),
                NOW.minusSeconds(89), Duration.ZERO);
        obligation.claim("worker-2", NOW.minusSeconds(60), NOW.minusSeconds(50));
        obligation.scheduleReconciliation("worker-2", obligation.getClaimToken(),
                NOW.minusSeconds(59), Duration.ZERO);
        obligation.claim("worker-a", NOW.minusSeconds(10), NOW.plusSeconds(20));
        ReservationDepositProcess process = compensationRequiredProcess();
        process.beginCompensation(NOW.minusSeconds(10));
        ReservationDepositRefundService.Claim claim =
                new ReservationDepositRefundService.Claim(
                        501L, 99L, "9001", 4_000L, "KRW", 1L,
                        "reservation-deposit-compensation:99",
                        "123e4567-e89b-12d3-a456-426614174099",
                        "FULL_DEPOSIT_COMPENSATION", "worker-a",
                        obligation.getClaimToken(),
                        ReservationDepositRefundService.Operation.QUERY, 3);
        RefundResult unknown = new RefundResult(
                "7001", "9001", 4_000L, 0L, 0L, 4_000L, "KRW",
                RefundStatus.RECONCILIATION_REQUIRED, NOW.minusSeconds(1), null);
        given(refundRepository.findByIdForUpdate(501L))
                .willReturn(Optional.of(obligation));
        given(processRepository.findByIdForUpdate(99L)).willReturn(Optional.of(process));
        ReservationPaymentRecoveryOutboxService outbox =
                mock(ReservationPaymentRecoveryOutboxService.class);
        ReservationDepositRefundService service = new ReservationDepositRefundService(
                refundRepository, processRepository, outbox,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30));

        assertThat(service.recordReconciliationRequired(
                claim, unknown, Duration.ofSeconds(30), 3)).isTrue();

        assertThat(process.getStatus())
                .isEqualTo(ReservationDepositProcessStatus.RECOVERY_REQUIRED);
        verify(outbox).enqueue(
                RESERVATION_DEPOSIT_REFUND,
                "501",
                "9001",
                "reservation-deposit-compensation:99",
                "123e4567-e89b-12d3-a456-426614174099");
    }

    private static ReservationDepositRefundObligation obligation() {
        return obligation(NOW);
    }

    private static ReservationDepositRefundObligation obligation(Instant createdAt) {
        ReservationDepositRefundObligation obligation =
                ReservationDepositRefundObligation.required(
                        99L,
                        "9001",
                        4_000L,
                        "KRW",
                        1L,
                        "reservation-deposit-compensation:99",
                        "123e4567-e89b-12d3-a456-426614174099",
                        "FULL_DEPOSIT_COMPENSATION",
                        createdAt);
        ReflectionTestUtils.setField(obligation, "id", 501L);
        return obligation;
    }

    private static ReservationDepositProcess compensationRequiredProcess() {
        Instant expiresAt = NOW.plusSeconds(300);
        Calculation calculation = new Calculation(
                91L,
                20,
                1L,
                2,
                4_000L,
                "KRW",
                13L,
                40_000L,
                3,
                List.of(new ItemSnapshot("101", 4, 40_000)));
        ReservationDepositProcess process = ReservationDepositProcess.awaitingPayment(
                77L,
                11L,
                expiresAt,
                calculation,
                new PaymentPreparation(
                        "9001",
                        "portone-9001",
                        "미리윰 식당 예약금",
                        4_000L,
                        "KRW",
                        expiresAt,
                        PaymentStatus.READY),
                NOW.minusSeconds(60));
        ReflectionTestUtils.setField(process, "id", 99L);
        process.requestAbandonment(NOW.minusSeconds(1));
        process.requireCompensation(NOW.minusSeconds(1));
        return process;
    }
}
