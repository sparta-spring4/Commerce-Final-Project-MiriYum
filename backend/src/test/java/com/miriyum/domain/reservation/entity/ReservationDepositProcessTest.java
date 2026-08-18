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
import java.time.Duration;
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
    void paymentRecoveryResumesAwaitingPaymentWithoutDroppingStickyEvidence() {
        ReservationDepositProcess process = newProcess();
        process.requestAbandonment(CREATED_AT.plusSeconds(1));
        process.protectResources(CREATED_AT.plusSeconds(2));
        process.requireRecovery(CREATED_AT.plusSeconds(3));

        process.resumePaymentReconciliation(CREATED_AT.plusSeconds(4));

        assertThat(process.getStatus())
                .isEqualTo(ReservationDepositProcessStatus.AWAITING_PAYMENT);
        assertThat(process.isAbandonmentRequested()).isTrue();
        assertThat(process.isResourcesProtected()).isTrue();
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

    @Test
    void reconciliationLeaseReclaimsAtExactExpiryAndFencesTheOlderWorker() {
        ReservationDepositProcess process = newProcess();
        Instant firstLeaseUntil = CREATED_AT.plusSeconds(30);

        assertThat(process.getReconciliationNextAttemptAt()).isEqualTo(CREATED_AT);
        long firstToken = process.claimReconciliation(
                "worker-a",
                CREATED_AT,
                firstLeaseUntil);

        assertThat(firstToken).isEqualTo(1L);
        assertThat(process.isReconciliationClaimOwnedBy(
                "worker-a", firstToken, CREATED_AT.plusSeconds(29))).isTrue();
        assertThat(process.getReconciliationNextAttemptAt()).isNull();

        long secondToken = process.claimReconciliation(
                "worker-b",
                firstLeaseUntil,
                firstLeaseUntil.plusSeconds(30));

        assertThat(secondToken).isEqualTo(2L);
        assertThat(process.isReconciliationClaimOwnedBy(
                "worker-a", firstToken, firstLeaseUntil)).isFalse();
        assertThat(process.isReconciliationClaimOwnedBy(
                "worker-b", secondToken, firstLeaseUntil)).isTrue();

        process.requeueReconciliation(
                "worker-b",
                secondToken,
                firstLeaseUntil,
                Duration.ofSeconds(5));

        assertThat(process.getReconciliationNextAttemptAt())
                .isEqualTo(firstLeaseUntil.plusSeconds(5));
        assertThat(process.getReconciliationLeaseOwner()).isNull();
        assertThat(process.getReconciliationLeaseUntil()).isNull();
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
