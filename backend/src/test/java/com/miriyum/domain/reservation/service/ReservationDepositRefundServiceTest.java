package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

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

    private static ReservationDepositRefundObligation obligation() {
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
                        NOW);
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
