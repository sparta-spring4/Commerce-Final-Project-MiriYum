package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.payment.dto.PaymentContracts.PaymentAttemptStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentResult;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.domain.reservation.dto.ReservationHoldContracts;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationDepositProcess;
import com.miriyum.domain.reservation.entity.ReservationDepositProcessStatus;
import com.miriyum.domain.reservation.entity.ReservationDepositCauseAudit;
import com.miriyum.domain.reservation.entity.ReservationDepositRefundObligation;
import com.miriyum.domain.reservation.entity.ReservationHold;
import com.miriyum.domain.reservation.entity.ReservationHoldCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.port.ReservationMenuHoldPort;
import com.miriyum.domain.reservation.repository.ReservationCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositCauseAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositRefundObligationRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.reservation.service.ReservationDepositCalculator.Calculation;
import com.miriyum.domain.reservation.service.ReservationDepositCalculator.ItemSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.miriyum.global.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class ReservationDepositProcessServiceTest {

    private static final long HOLD_ID = 77L;
    private static final long RESERVATION_ID = 88L;
    private static final long CONSUMER_ID = 11L;
    private static final long STORE_ID = 22L;
    private static final long CAPACITY_POLICY_VERSION = 7L;
    private static final long PROCESS_ID = 99L;
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 20);
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    @Test
    void paidBeforeExpiryFinalizesProcessAndReturnsReservation() {
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositFinalizationPrimitive finalizationPrimitive =
                mock(ReservationDepositFinalizationPrimitive.class);
        ReservationHoldTransitionPrimitive holdTransitionPrimitive =
                mock(ReservationHoldTransitionPrimitive.class);
        ReservationMenuHoldPort menuHoldPort = mock(ReservationMenuHoldPort.class);
        ReservationDepositProcess process = depositProcess();
        Reservation reservation = confirmedReservation();
        given(processRepository.findByIdAndConsumerAccountIdForUpdate(
                PROCESS_ID, CONSUMER_ID)).willReturn(Optional.of(process));
        given(paymentService.getOwnedPayment("9001", String.valueOf(CONSUMER_ID)))
                .willReturn(paidResult(NOW));
        given(finalizationPrimitive.finalizeResources(any()))
                .willAnswer(invocation -> {
                    assertThat(process.getStatus())
                            .isEqualTo(ReservationDepositProcessStatus.FINALIZING_RESOURCES);
                    return reservation;
                });
        given(menuHoldPort.findSnapshots(RESERVATION_ID)).willReturn(List.of());
        ReservationDepositProcessService service = new ReservationDepositProcessService(
                processRepository,
                paymentService,
                finalizationPrimitive,
                holdTransitionPrimitive,
                menuHoldPort,
                Clock.fixed(NOW, ZoneId.of("UTC")));

        ReservationDepositCommandResult result = service.finalizeOwned(
                PROCESS_ID,
                CONSUMER_ID,
                "123e4567-e89b-12d3-a456-426614174000");

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.reservation().reservationId())
                .isEqualTo(String.valueOf(RESERVATION_ID));
        assertThat(process.getStatus()).isEqualTo(ReservationDepositProcessStatus.COMPLETED);
        assertThat(process.getFinalReservationId()).isEqualTo(RESERVATION_ID);
        ArgumentCaptor<ReservationDepositFinalizationPrimitive.Command> command =
                ArgumentCaptor.forClass(ReservationDepositFinalizationPrimitive.Command.class);
        InOrder order = inOrder(processRepository, paymentService, finalizationPrimitive);
        order.verify(processRepository).findByIdAndConsumerAccountIdForUpdate(
                PROCESS_ID, CONSUMER_ID);
        order.verify(paymentService).getOwnedPayment("9001", String.valueOf(CONSUMER_ID));
        order.verify(finalizationPrimitive).finalizeResources(command.capture());
        order.verify(processRepository).saveAndFlush(process);
        assertThat(command.getValue()).isEqualTo(
                new ReservationDepositFinalizationPrimitive.Command(
                        HOLD_ID,
                        "reservation-deposit-finalize:99:123e4567-e89b-12d3-a456-426614174000",
                        "CONSUMER",
                        CONSUMER_ID,
                        NOW));
    }

    @Test
    void confirmingBeforeExpiryProtectsResourcesAndReturnsAccepted() {
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositFinalizationPrimitive finalizationPrimitive =
                mock(ReservationDepositFinalizationPrimitive.class);
        ReservationHoldTransitionPrimitive holdTransitionPrimitive =
                mock(ReservationHoldTransitionPrimitive.class);
        ReservationMenuHoldPort menuHoldPort = mock(ReservationMenuHoldPort.class);
        ReservationDepositProcess process = depositProcess();
        given(processRepository.findByIdAndConsumerAccountIdForUpdate(
                PROCESS_ID, CONSUMER_ID)).willReturn(Optional.of(process));
        given(paymentService.getOwnedPayment("9001", String.valueOf(CONSUMER_ID)))
                .willReturn(paymentResult(
                        PaymentStatus.CONFIRMING,
                        PaymentAttemptStatus.PENDING,
                        null));
        ReservationDepositProcessService service = new ReservationDepositProcessService(
                processRepository,
                paymentService,
                finalizationPrimitive,
                holdTransitionPrimitive,
                menuHoldPort,
                Clock.fixed(NOW, ZoneId.of("UTC")));

        ReservationDepositCommandResult result = service.finalizeOwned(
                PROCESS_ID,
                CONSUMER_ID,
                "123e4567-e89b-12d3-a456-426614174001");

        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(result.reservationRequest().status())
                .isEqualTo(ReservationDepositProcessStatus.AWAITING_PAYMENT);
        verify(holdTransitionPrimitive).transition(
                new ReservationHoldContracts.TransitionCommand(
                        HOLD_ID,
                        ReservationHoldStatus.RECONCILIATION_REQUIRED,
                        "reservation-deposit-protect:99:123e4567-e89b-12d3-a456-426614174001",
                        "CONSUMER",
                        CONSUMER_ID,
                        NOW,
                        null));
        verify(finalizationPrimitive, never()).finalizeResources(any());
    }

    @Test
    void readyAtExactExpiryReleasesResourcesAndMarksExpired() {
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositFinalizationPrimitive finalizationPrimitive =
                mock(ReservationDepositFinalizationPrimitive.class);
        ReservationHoldTransitionPrimitive holdTransitionPrimitive =
                mock(ReservationHoldTransitionPrimitive.class);
        ReservationMenuHoldPort menuHoldPort = mock(ReservationMenuHoldPort.class);
        ReservationDepositProcess process = depositProcess();
        Instant expiresAt = process.getExpiresAt();
        given(processRepository.findByIdAndConsumerAccountIdForUpdate(
                PROCESS_ID, CONSUMER_ID)).willReturn(Optional.of(process));
        given(paymentService.getOwnedPayment("9001", String.valueOf(CONSUMER_ID)))
                .willReturn(paymentResult(
                        PaymentStatus.READY,
                        PaymentAttemptStatus.NOT_STARTED,
                        null));
        ReservationDepositProcessService service = new ReservationDepositProcessService(
                processRepository,
                paymentService,
                finalizationPrimitive,
                holdTransitionPrimitive,
                menuHoldPort,
                Clock.fixed(expiresAt, ZoneId.of("UTC")));

        ReservationDepositCommandResult result = service.finalizeOwned(
                PROCESS_ID,
                CONSUMER_ID,
                "123e4567-e89b-12d3-a456-426614174002");

        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(result.reservationRequest().status())
                .isEqualTo(ReservationDepositProcessStatus.EXPIRED);
        verify(holdTransitionPrimitive).transition(
                new ReservationHoldContracts.TransitionCommand(
                        HOLD_ID,
                        ReservationHoldStatus.EXPIRED,
                        "reservation-hold-expire:77",
                        "SYSTEM",
                        null,
                        expiresAt,
                        null));
        verify(processRepository).saveAndFlush(process);
        verify(finalizationPrimitive, never()).finalizeResources(any());
    }

    @Test
    void linkedExpirationLocksProcessByInternalIdBeforeExpiringResources() {
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositFinalizationPrimitive finalizationPrimitive =
                mock(ReservationDepositFinalizationPrimitive.class);
        ReservationHoldTransitionPrimitive holdTransitionPrimitive =
                mock(ReservationHoldTransitionPrimitive.class);
        ReservationDepositProcess process = depositProcess();
        Instant expiresAt = process.getExpiresAt();
        given(processRepository.findByIdForUpdate(PROCESS_ID))
                .willReturn(Optional.of(process));
        given(paymentService.getOwnedPayment("9001", String.valueOf(CONSUMER_ID)))
                .willReturn(paymentResult(
                        PaymentStatus.READY,
                        PaymentAttemptStatus.NOT_STARTED,
                        null));
        ReservationDepositProcessService service = new ReservationDepositProcessService(
                processRepository,
                paymentService,
                finalizationPrimitive,
                holdTransitionPrimitive,
                mock(ReservationMenuHoldPort.class),
                Clock.fixed(expiresAt, ZoneId.of("UTC")));

        ReservationDepositCommandResult result =
                service.reconcileLinkedExpiration(PROCESS_ID);

        assertThat(result.reservationRequest().status())
                .isEqualTo(ReservationDepositProcessStatus.EXPIRED);
        verify(processRepository).findByIdForUpdate(PROCESS_ID);
        verify(processRepository, never())
                .findByIdAndConsumerAccountIdForUpdate(PROCESS_ID, CONSUMER_ID);
        verify(holdTransitionPrimitive).transition(
                new ReservationHoldContracts.TransitionCommand(
                        HOLD_ID,
                        ReservationHoldStatus.EXPIRED,
                        "reservation-hold-expire:77",
                        "SYSTEM",
                        null,
                        expiresAt,
                        null));
    }

    @Test
    void readyAbandonmentReleasesResourcesAndReturnsCompletedRequest() {
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositFinalizationPrimitive finalizationPrimitive =
                mock(ReservationDepositFinalizationPrimitive.class);
        ReservationHoldTransitionPrimitive holdTransitionPrimitive =
                mock(ReservationHoldTransitionPrimitive.class);
        ReservationMenuHoldPort menuHoldPort = mock(ReservationMenuHoldPort.class);
        ReservationDepositProcess process = depositProcess();
        given(processRepository.findByIdAndConsumerAccountIdForUpdate(
                PROCESS_ID, CONSUMER_ID)).willReturn(Optional.of(process));
        given(paymentService.getOwnedPayment("9001", String.valueOf(CONSUMER_ID)))
                .willReturn(paymentResult(
                        PaymentStatus.READY,
                        PaymentAttemptStatus.NOT_STARTED,
                        null));
        ReservationDepositProcessService service = new ReservationDepositProcessService(
                processRepository,
                paymentService,
                finalizationPrimitive,
                holdTransitionPrimitive,
                menuHoldPort,
                Clock.fixed(NOW, ZoneId.of("UTC")));

        ReservationDepositCommandResult result = service.abandonOwned(
                PROCESS_ID,
                CONSUMER_ID,
                "123e4567-e89b-12d3-a456-426614174003");

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.reservationRequest().status())
                .isEqualTo(ReservationDepositProcessStatus.ABANDONED);
        assertThat(result.reservationRequest().abandonmentRequested()).isTrue();
        verify(holdTransitionPrimitive).transition(
                new ReservationHoldContracts.TransitionCommand(
                        HOLD_ID,
                        ReservationHoldStatus.RELEASED,
                        "reservation-deposit-abandon:99:123e4567-e89b-12d3-a456-426614174003",
                        "CONSUMER",
                        CONSUMER_ID,
                        NOW,
                        null));
        verify(processRepository).saveAndFlush(process);
        verify(finalizationPrimitive, never()).finalizeResources(any());
    }

    @Test
    void confirmingAbandonmentPersistsIntentAndProtectsResources() {
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositFinalizationPrimitive finalizationPrimitive =
                mock(ReservationDepositFinalizationPrimitive.class);
        ReservationHoldTransitionPrimitive holdTransitionPrimitive =
                mock(ReservationHoldTransitionPrimitive.class);
        ReservationMenuHoldPort menuHoldPort = mock(ReservationMenuHoldPort.class);
        ReservationDepositProcess process = depositProcess();
        given(processRepository.findByIdAndConsumerAccountIdForUpdate(
                PROCESS_ID, CONSUMER_ID)).willReturn(Optional.of(process));
        given(paymentService.getOwnedPayment("9001", String.valueOf(CONSUMER_ID)))
                .willReturn(paymentResult(
                        PaymentStatus.CONFIRMING,
                        PaymentAttemptStatus.PENDING,
                        null));
        ReservationDepositProcessService service = new ReservationDepositProcessService(
                processRepository,
                paymentService,
                finalizationPrimitive,
                holdTransitionPrimitive,
                menuHoldPort,
                Clock.fixed(NOW, ZoneId.of("UTC")));

        ReservationDepositCommandResult result = service.abandonOwned(
                PROCESS_ID,
                CONSUMER_ID,
                "123e4567-e89b-12d3-a456-426614174004");

        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(result.reservationRequest().abandonmentRequested()).isTrue();
        assertThat(process.getStatus())
                .isEqualTo(ReservationDepositProcessStatus.AWAITING_PAYMENT);
        verify(holdTransitionPrimitive).transition(
                new ReservationHoldContracts.TransitionCommand(
                        HOLD_ID,
                        ReservationHoldStatus.RECONCILIATION_REQUIRED,
                        "reservation-deposit-protect:99:123e4567-e89b-12d3-a456-426614174004",
                        "CONSUMER",
                        CONSUMER_ID,
                        NOW,
                        null));
        verify(processRepository).saveAndFlush(process);
        verify(finalizationPrimitive, never()).finalizeResources(any());
    }

    @Test
    void paidAbandonmentReleasesResourcesAndPersistsRefundObligation() {
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        ReservationDepositCauseAuditRepository causeRepository =
                mock(ReservationDepositCauseAuditRepository.class);
        ReservationDepositRefundObligationRepository refundRepository =
                mock(ReservationDepositRefundObligationRepository.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositFinalizationPrimitive finalizationPrimitive =
                mock(ReservationDepositFinalizationPrimitive.class);
        ReservationHoldTransitionPrimitive holdTransitionPrimitive =
                mock(ReservationHoldTransitionPrimitive.class);
        ReservationMenuHoldPort menuHoldPort = mock(ReservationMenuHoldPort.class);
        ReservationDepositProcess process = depositProcess();
        given(processRepository.findByIdAndConsumerAccountIdForUpdate(
                PROCESS_ID, CONSUMER_ID)).willReturn(Optional.of(process));
        given(paymentService.getOwnedPayment("9001", String.valueOf(CONSUMER_ID)))
                .willReturn(paidResult(NOW.minusSeconds(1)));
        ReservationDepositProcessService service = new ReservationDepositProcessService(
                processRepository,
                causeRepository,
                refundRepository,
                paymentService,
                finalizationPrimitive,
                holdTransitionPrimitive,
                menuHoldPort,
                Clock.fixed(NOW, ZoneId.of("UTC")));

        ReservationDepositCommandResult result = service.abandonOwned(
                PROCESS_ID,
                CONSUMER_ID,
                "123e4567-e89b-12d3-a456-426614174005");

        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(result.reservationRequest().status())
                .isEqualTo(ReservationDepositProcessStatus.COMPENSATION_REQUIRED);
        verify(holdTransitionPrimitive).transition(
                new ReservationHoldContracts.TransitionCommand(
                        HOLD_ID,
                        ReservationHoldStatus.RELEASED,
                        "reservation-deposit-compensate:99:123e4567-e89b-12d3-a456-426614174005",
                        "CONSUMER",
                        CONSUMER_ID,
                        NOW,
                        null));
        ArgumentCaptor<ReservationDepositCauseAudit> cause =
                ArgumentCaptor.forClass(ReservationDepositCauseAudit.class);
        ArgumentCaptor<ReservationDepositRefundObligation> refund =
                ArgumentCaptor.forClass(ReservationDepositRefundObligation.class);
        InOrder order = inOrder(
                processRepository,
                paymentService,
                holdTransitionPrimitive,
                causeRepository,
                refundRepository);
        order.verify(processRepository).findByIdAndConsumerAccountIdForUpdate(
                PROCESS_ID, CONSUMER_ID);
        order.verify(paymentService).getOwnedPayment("9001", String.valueOf(CONSUMER_ID));
        order.verify(holdTransitionPrimitive).transition(any());
        order.verify(causeRepository).save(cause.capture());
        order.verify(refundRepository).save(refund.capture());
        order.verify(processRepository).saveAndFlush(process);
        assertThat(cause.getValue().getCauseCode()).isEqualTo("ABANDONMENT_PAID");
        String stableKey = UUID.nameUUIDFromBytes(
                "reservation-deposit-refund:99:9001"
                        .getBytes(StandardCharsets.UTF_8)).toString();
        assertThat(refund.getValue().matchesRequired(
                PROCESS_ID,
                "9001",
                4_000L,
                "KRW",
                1L,
                "reservation-deposit-compensation:99",
                stableKey,
                "FULL_DEPOSIT_COMPENSATION")).isTrue();
        verify(finalizationPrimitive, never()).finalizeResources(any());
    }

    @Test
    void missingStoredPaymentBecomesRecoveryRequiredInsteadOfConsumerNotFound() {
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        ReservationDepositCauseAuditRepository causeRepository =
                mock(ReservationDepositCauseAuditRepository.class);
        ReservationDepositRefundObligationRepository refundRepository =
                mock(ReservationDepositRefundObligationRepository.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositFinalizationPrimitive finalizationPrimitive =
                mock(ReservationDepositFinalizationPrimitive.class);
        ReservationHoldTransitionPrimitive holdTransitionPrimitive =
                mock(ReservationHoldTransitionPrimitive.class);
        ReservationMenuHoldPort menuHoldPort = mock(ReservationMenuHoldPort.class);
        ReservationDepositProcess process = depositProcess();
        given(processRepository.findByIdAndConsumerAccountIdForUpdate(
                PROCESS_ID, CONSUMER_ID)).willReturn(Optional.of(process));
        given(paymentService.getOwnedPayment("9001", String.valueOf(CONSUMER_ID)))
                .willThrow(new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        ReservationDepositProcessService service = new ReservationDepositProcessService(
                processRepository,
                causeRepository,
                refundRepository,
                paymentService,
                finalizationPrimitive,
                holdTransitionPrimitive,
                menuHoldPort,
                Clock.fixed(NOW, ZoneId.of("UTC")));

        ReservationDepositCommandResult result = service.finalizeOwned(
                PROCESS_ID,
                CONSUMER_ID,
                "123e4567-e89b-12d3-a456-426614174006");

        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(result.reservationRequest().status())
                .isEqualTo(ReservationDepositProcessStatus.RECOVERY_REQUIRED);
        ArgumentCaptor<ReservationDepositCauseAudit> cause =
                ArgumentCaptor.forClass(ReservationDepositCauseAudit.class);
        verify(causeRepository).save(cause.capture());
        assertThat(cause.getValue().getCauseCode()).isEqualTo("PAYMENT_NOT_FOUND");
        assertThat(cause.getValue().getPaymentId()).isEqualTo("9001");
        verify(processRepository).saveAndFlush(process);
        verify(finalizationPrimitive, never()).finalizeResources(any());
        verify(refundRepository, never()).save(any());
    }

    @Test
    void missingStoredPaymentDuringAbandonmentKeepsStickyIntentForRecovery() {
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        ReservationDepositCauseAuditRepository causeRepository =
                mock(ReservationDepositCauseAuditRepository.class);
        ReservationDepositRefundObligationRepository refundRepository =
                mock(ReservationDepositRefundObligationRepository.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositProcess process = depositProcess();
        given(processRepository.findByIdAndConsumerAccountIdForUpdate(
                PROCESS_ID, CONSUMER_ID)).willReturn(Optional.of(process));
        given(paymentService.getOwnedPayment("9001", String.valueOf(CONSUMER_ID)))
                .willThrow(new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        ReservationDepositProcessService service = new ReservationDepositProcessService(
                processRepository,
                causeRepository,
                refundRepository,
                paymentService,
                mock(ReservationDepositFinalizationPrimitive.class),
                mock(ReservationHoldTransitionPrimitive.class),
                mock(ReservationMenuHoldPort.class),
                Clock.fixed(NOW, ZoneId.of("UTC")));

        ReservationDepositCommandResult result = service.abandonOwned(
                PROCESS_ID,
                CONSUMER_ID,
                "123e4567-e89b-12d3-a456-426614174007");

        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(result.reservationRequest().status())
                .isEqualTo(ReservationDepositProcessStatus.RECOVERY_REQUIRED);
        assertThat(result.reservationRequest().abandonmentRequested()).isTrue();
        verify(processRepository).saveAndFlush(process);
    }

    @Test
    void paidAtExactExpiryNeverFinalizesAndRequiresFullRefund() {
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        ReservationDepositCauseAuditRepository causeRepository =
                mock(ReservationDepositCauseAuditRepository.class);
        ReservationDepositRefundObligationRepository refundRepository =
                mock(ReservationDepositRefundObligationRepository.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositFinalizationPrimitive finalizationPrimitive =
                mock(ReservationDepositFinalizationPrimitive.class);
        ReservationHoldTransitionPrimitive holdTransitionPrimitive =
                mock(ReservationHoldTransitionPrimitive.class);
        ReservationDepositProcess process = depositProcess();
        Instant expiresAt = process.getExpiresAt();
        given(processRepository.findByIdAndConsumerAccountIdForUpdate(
                PROCESS_ID, CONSUMER_ID)).willReturn(Optional.of(process));
        given(paymentService.getOwnedPayment("9001", String.valueOf(CONSUMER_ID)))
                .willReturn(paidResult(expiresAt));
        ReservationDepositProcessService service = new ReservationDepositProcessService(
                processRepository,
                causeRepository,
                refundRepository,
                paymentService,
                finalizationPrimitive,
                holdTransitionPrimitive,
                mock(ReservationMenuHoldPort.class),
                Clock.fixed(expiresAt.plusSeconds(1), ZoneId.of("UTC")));

        ReservationDepositCommandResult result = service.finalizeOwned(
                PROCESS_ID,
                CONSUMER_ID,
                "123e4567-e89b-12d3-a456-426614174008");

        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(result.reservationRequest().status())
                .isEqualTo(ReservationDepositProcessStatus.COMPENSATION_REQUIRED);
        verify(holdTransitionPrimitive).transition(
                new ReservationHoldContracts.TransitionCommand(
                        HOLD_ID,
                        ReservationHoldStatus.EXPIRED,
                        "reservation-hold-expire:77",
                        "SYSTEM",
                        null,
                        expiresAt,
                        null));
        ArgumentCaptor<ReservationDepositCauseAudit> cause =
                ArgumentCaptor.forClass(ReservationDepositCauseAudit.class);
        verify(causeRepository).save(cause.capture());
        assertThat(cause.getValue().getCauseCode())
                .isEqualTo("PAYMENT_PAID_AT_OR_AFTER_EXPIRY");
        verify(refundRepository).save(any(ReservationDepositRefundObligation.class));
        verify(processRepository).saveAndFlush(process);
        verify(finalizationPrimitive, never()).finalizeResources(any());
    }

    @Test
    void unprotectedLatePaidBeforeExpiryDoesNotResurrectExpiredResources() {
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        ReservationDepositCauseAuditRepository causeRepository =
                mock(ReservationDepositCauseAuditRepository.class);
        ReservationDepositRefundObligationRepository refundRepository =
                mock(ReservationDepositRefundObligationRepository.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositFinalizationPrimitive finalizationPrimitive =
                mock(ReservationDepositFinalizationPrimitive.class);
        ReservationHoldTransitionPrimitive holdTransitionPrimitive =
                mock(ReservationHoldTransitionPrimitive.class);
        ReservationDepositProcess process = depositProcess();
        Instant expiresAt = process.getExpiresAt();
        given(processRepository.findByIdAndConsumerAccountIdForUpdate(
                PROCESS_ID, CONSUMER_ID)).willReturn(Optional.of(process));
        given(paymentService.getOwnedPayment("9001", String.valueOf(CONSUMER_ID)))
                .willReturn(paidResult(expiresAt.minusNanos(1)));
        ReservationDepositProcessService service = new ReservationDepositProcessService(
                processRepository,
                causeRepository,
                refundRepository,
                paymentService,
                finalizationPrimitive,
                holdTransitionPrimitive,
                mock(ReservationMenuHoldPort.class),
                Clock.fixed(expiresAt.plusSeconds(1), ZoneId.of("UTC")));

        ReservationDepositCommandResult result = service.finalizeOwned(
                PROCESS_ID,
                CONSUMER_ID,
                "123e4567-e89b-12d3-a456-426614174009");

        assertThat(result.reservationRequest().status())
                .isEqualTo(ReservationDepositProcessStatus.COMPENSATION_REQUIRED);
        verify(holdTransitionPrimitive).transition(
                new ReservationHoldContracts.TransitionCommand(
                        HOLD_ID,
                        ReservationHoldStatus.EXPIRED,
                        "reservation-hold-expire:77",
                        "SYSTEM",
                        null,
                        expiresAt,
                        null));
        verify(finalizationPrimitive, never()).finalizeResources(any());
        verify(refundRepository).save(any(ReservationDepositRefundObligation.class));
    }

    @Test
    void protectedPaidBeforeExpiryCanFinalizeAfterWallClockExpiry() {
        ReservationDepositProcessRepository processRepository =
                mock(ReservationDepositProcessRepository.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositFinalizationPrimitive finalizationPrimitive =
                mock(ReservationDepositFinalizationPrimitive.class);
        ReservationDepositProcess process = depositProcess();
        Instant expiresAt = process.getExpiresAt();
        process.protectResources(NOW);
        given(processRepository.findByIdAndConsumerAccountIdForUpdate(
                PROCESS_ID, CONSUMER_ID)).willReturn(Optional.of(process));
        given(paymentService.getOwnedPayment("9001", String.valueOf(CONSUMER_ID)))
                .willReturn(paidResult(expiresAt.minusNanos(1)));
        given(finalizationPrimitive.finalizeResources(any()))
                .willReturn(confirmedReservation());
        ReservationDepositProcessService service = new ReservationDepositProcessService(
                processRepository,
                mock(ReservationDepositCauseAuditRepository.class),
                mock(ReservationDepositRefundObligationRepository.class),
                paymentService,
                finalizationPrimitive,
                mock(ReservationHoldTransitionPrimitive.class),
                mock(ReservationMenuHoldPort.class),
                Clock.fixed(expiresAt.plusSeconds(1), ZoneId.of("UTC")));

        ReservationDepositCommandResult result = service.finalizeOwned(
                PROCESS_ID,
                CONSUMER_ID,
                "123e4567-e89b-12d3-a456-426614174010");

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(process.getStatus()).isEqualTo(ReservationDepositProcessStatus.COMPLETED);
        verify(finalizationPrimitive).finalizeResources(any());
    }

    @Test
    void finalizationCopiesHeldCapacityWithoutMutatingOccupiedTotals() {
        ReservationHoldRepository holdRepository = mock(ReservationHoldRepository.class);
        ReservationHoldCapacityAllocationRepository holdAllocationRepository =
                mock(ReservationHoldCapacityAllocationRepository.class);
        ReservationCapacityBucketRepository bucketRepository =
                mock(ReservationCapacityBucketRepository.class);
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        ReservationCapacityAllocationRepository allocationRepository =
                mock(ReservationCapacityAllocationRepository.class);
        ReservationHoldTransitionPrimitive transitionPrimitive =
                mock(ReservationHoldTransitionPrimitive.class);
        ReservationDepositFinalizationPrimitive primitive =
                new ReservationDepositFinalizationPrimitive(
                        holdRepository,
                        holdAllocationRepository,
                        bucketRepository,
                        reservationRepository,
                        allocationRepository,
                        transitionPrimitive);
        ReservationHold hold = activeHold();
        List<ReservationHoldCapacityAllocation> heldAllocations = List.of(
                ReservationHoldCapacityAllocation.allocate(
                        HOLD_ID, 101L, 3, CAPACITY_POLICY_VERSION),
                ReservationHoldCapacityAllocation.allocate(
                        HOLD_ID, 102L, 3, CAPACITY_POLICY_VERSION));
        ReservationCapacityBucket first = bucket(101L, LocalTime.of(12, 0),
                LocalTime.of(12, 30), 9, 2);
        ReservationCapacityBucket second = bucket(102L, LocalTime.of(12, 30),
                LocalTime.of(13, 0), 7, 1);
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));
        given(holdAllocationRepository
                .findAllByReservationHoldIdOrderByCapacityBucketIdAsc(HOLD_ID))
                .willReturn(heldAllocations);
        given(reservationRepository.saveAndFlush(any(Reservation.class)))
                .willAnswer(invocation -> {
                    Reservation saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "id", RESERVATION_ID);
                    return saved;
                });
        given(bucketRepository.findAllByIdInForUpdate(List.of(101L, 102L)))
                .willReturn(List.of(first, second));

        Reservation result = primitive.finalizeResources(
                new ReservationDepositFinalizationPrimitive.Command(
                        HOLD_ID,
                        "reservation-deposit-finalize:request-key",
                        "CONSUMER",
                        CONSUMER_ID,
                        NOW));

        assertThat(result.getId()).isEqualTo(RESERVATION_ID);
        assertThat(first.getOccupiedPeople()).isEqualTo(9);
        assertThat(first.getOccupiedTeams()).isEqualTo(2);
        assertThat(second.getOccupiedPeople()).isEqualTo(7);
        assertThat(second.getOccupiedTeams()).isEqualTo(1);
        ArgumentCaptor<ReservationHoldContracts.TransitionCommand> transition =
                ArgumentCaptor.forClass(ReservationHoldContracts.TransitionCommand.class);
        InOrder order = inOrder(
                holdRepository,
                holdAllocationRepository,
                reservationRepository,
                transitionPrimitive,
                bucketRepository,
                allocationRepository);
        order.verify(holdRepository).findByIdForUpdate(HOLD_ID);
        order.verify(holdAllocationRepository)
                .findAllByReservationHoldIdOrderByCapacityBucketIdAsc(HOLD_ID);
        order.verify(reservationRepository).saveAndFlush(any(Reservation.class));
        order.verify(transitionPrimitive).transition(transition.capture());
        order.verify(bucketRepository).findAllByIdInForUpdate(List.of(101L, 102L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReservationCapacityAllocation>> allocations =
                ArgumentCaptor.forClass(List.class);
        order.verify(allocationRepository).saveAll(allocations.capture());
        assertThat(transition.getValue())
                .isEqualTo(new ReservationHoldContracts.TransitionCommand(
                        HOLD_ID,
                        ReservationHoldStatus.CONFIRMED,
                        "reservation-deposit-finalize:request-key",
                        "CONSUMER",
                        CONSUMER_ID,
                        NOW,
                        RESERVATION_ID));
        assertThat(allocations.getValue())
                .extracting(
                        ReservationCapacityAllocation::getCapacityBucketId,
                        ReservationCapacityAllocation::getOccupiedPeople,
                        ReservationCapacityAllocation::getOccupiedTeams,
                        ReservationCapacityAllocation::getCapacityPolicyVersion)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(101L, 3, 1, 7L),
                        org.assertj.core.groups.Tuple.tuple(102L, 3, 1, 7L));
    }

    private static ReservationDepositProcess depositProcess() {
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
                HOLD_ID,
                CONSUMER_ID,
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
        ReflectionTestUtils.setField(process, "id", PROCESS_ID);
        return process;
    }

    private static PaymentResult paidResult(Instant paidAt) {
        return paymentResult(PaymentStatus.PAID, PaymentAttemptStatus.PAID, paidAt);
    }

    private static PaymentResult paymentResult(
            PaymentStatus status,
            PaymentAttemptStatus attemptStatus,
            Instant paidAt
    ) {
        return new PaymentResult(
                "9001",
                String.valueOf(HOLD_ID),
                4_000L,
                0L,
                4_000L,
                "KRW",
                status,
                attemptStatus,
                NOW.minusSeconds(60),
                paidAt,
                NOW,
                List.of());
    }

    private static Reservation confirmedReservation() {
        ReservationHold hold = activeHold();
        Reservation reservation = Reservation.confirm(
                hold.getConsumerAccountId(),
                hold.getStoreId(),
                hold.getStoreNameSnapshot(),
                hold.getTimeSnapshot(),
                hold.getParty(),
                hold.getContactSnapshot(),
                hold.getCapacityPolicyVersion(),
                new ReservationCancellationPolicyVersion(
                        hold.getCancellationPolicyVersion()),
                NOW);
        ReflectionTestUtils.setField(reservation, "id", RESERVATION_ID);
        return reservation;
    }

    private static ReservationHold activeHold() {
        ReservationTimePolicyVersion timePolicy = ReservationTimePolicyVersion.createDraft(
                STORE_ID, 5L, 30, 60, 0);
        timePolicy.activate(NOW.minusSeconds(1), "test");
        ReservationTimeSnapshot time = ReservationTimeSnapshot.calculate(
                timePolicy,
                LocalDateTime.of(SERVICE_DATE, LocalTime.NOON),
                ZoneId.of("Asia/Seoul"),
                null);
        ReservationHold hold = ReservationHold.active(
                CONSUMER_ID,
                STORE_ID,
                "미리윰 매장",
                time,
                PartyComposition.of(2, 1, 0),
                ReservationContactSnapshot.contactable("contact-ref"),
                CAPACITY_POLICY_VERSION,
                new ReservationCancellationPolicyVersion(1L),
                "reservation-deposit-create:key",
                NOW.minusSeconds(60));
        ReflectionTestUtils.setField(hold, "id", HOLD_ID);
        return hold;
    }

    private static ReservationCapacityBucket bucket(
            long id,
            LocalTime start,
            LocalTime end,
            int occupiedPeople,
            int occupiedTeams
    ) {
        ReservationCapacityBucket bucket = ReservationCapacityBucket.create(
                STORE_ID,
                SERVICE_DATE,
                start,
                end,
                20,
                10,
                occupiedPeople,
                occupiedTeams,
                1,
                10,
                true,
                CAPACITY_POLICY_VERSION);
        ReflectionTestUtils.setField(bucket, "id", id);
        return bucket;
    }
}
