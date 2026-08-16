package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.service.ReservationDepositCalculator.Calculation;
import com.miriyum.domain.reservation.service.ReservationDepositCalculator.ItemSnapshot;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReservationDepositProcessTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-03T09:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-03T09:10:00Z");

    @Test
    void preservesTheInitialCalculationAndPaymentPreparation() {
        ReservationDepositProcess process = newProcess();

        assertThat(process.getStatus())
                .isEqualTo(ReservationDepositProcessStatus.AWAITING_PAYMENT);
        assertThat(process.getReservationHoldId()).isEqualTo(77L);
        assertThat(process.getConsumerAccountId()).isEqualTo(11L);
        assertThat(process.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(process.getPaymentId()).isEqualTo("pay_77");
        assertThat(process.getPortOnePaymentId()).isEqualTo("portone_77");
        assertThat(process.getPaymentOrderName()).isEqualTo("미리윰 식당 예약금");
        assertThat(process.getPaymentAmountMinor()).isEqualTo(4_000L);
        assertThat(process.getPaymentCurrency()).isEqualTo("KRW");
        assertThat(process.getPaymentPreparationStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(process.getCalculationSnapshot().getStorePolicyVersion()).isEqualTo(91L);
        assertThat(process.getCalculationSnapshot().getAlgorithmVersion()).isEqualTo(1L);
        assertThat(process.getCalculationSnapshot().getRepresentativeMenuVersion())
                .isEqualTo(13L);
        assertThat(process.getCalculationSnapshot().getItems())
                .extracting(ReservationDepositCalculationSnapshot.Item::getMenuId)
                .containsExactly("101", "102");
    }

    @Test
    void abandonmentIsStickyAndPreventsFinalCompletion() {
        ReservationDepositProcess process = newProcess();

        process.requestAbandonment(CREATED_AT.plusSeconds(1));
        process.requestAbandonment(CREATED_AT.plusSeconds(2));

        assertThat(process.isAbandonmentRequested()).isTrue();
        assertThat(process.getAbandonmentRequestedAt())
                .isEqualTo(CREATED_AT.plusSeconds(1));
        assertThatThrownBy(() -> process.beginFinalization(CREATED_AT.plusSeconds(3)))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION));
        assertThat(process.getStatus())
                .isEqualTo(ReservationDepositProcessStatus.AWAITING_PAYMENT);
    }

    @Test
    void completedProcessRejectsAbandonment() {
        ReservationDepositProcess process = newProcess();
        process.beginFinalization(CREATED_AT.plusSeconds(1));
        process.complete(88L, CREATED_AT.plusSeconds(2));

        assertThat(process.getStatus()).isEqualTo(ReservationDepositProcessStatus.COMPLETED);
        assertThat(process.getFinalReservationId()).isEqualTo(88L);
        assertThatThrownBy(() -> process.requestAbandonment(CREATED_AT.plusSeconds(3)))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION));
    }

    private static ReservationDepositProcess newProcess() {
        Calculation calculation = new Calculation(
                91L,
                20,
                1L,
                2,
                4_000L,
                "KRW",
                13L,
                40_000L,
                2,
                List.of(
                        new ItemSnapshot("101", 4, 18_000),
                        new ItemSnapshot("102", 8, 22_000)));
        PaymentPreparation payment = new PaymentPreparation(
                "pay_77",
                "portone_77",
                "미리윰 식당 예약금",
                4_000L,
                "KRW",
                EXPIRES_AT,
                PaymentStatus.READY);
        return ReservationDepositProcess.awaitingPayment(
                77L, 11L, EXPIRES_AT, calculation, payment, CREATED_AT);
    }
}
