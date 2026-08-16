package com.miriyum.domain.reservation.service;

import com.miriyum.domain.payment.dto.PaymentContracts.PaymentResult;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.dto.ReservationHoldContracts;
import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;
import com.miriyum.domain.reservation.dto.response.ReservationRequestResponse;
import com.miriyum.domain.reservation.dto.response.ReservationRequestResponse.PaymentPreparationSnapshot;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationDepositProcess;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.port.ReservationMenuHoldPort;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Process-first coordinator for deposit finalization, abandonment, and recovery. */
@Service
public class ReservationDepositProcessService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final ReservationDepositProcessRepository processRepository;
    private final PaymentService paymentService;
    private final ReservationDepositFinalizationPrimitive finalizationPrimitive;
    private final ReservationHoldTransitionPrimitive holdTransitionPrimitive;
    private final ReservationMenuHoldPort menuHoldPort;
    private final Clock clock;

    public ReservationDepositProcessService(
            ReservationDepositProcessRepository processRepository,
            PaymentService paymentService,
            ReservationDepositFinalizationPrimitive finalizationPrimitive,
            ReservationHoldTransitionPrimitive holdTransitionPrimitive,
            ReservationMenuHoldPort menuHoldPort,
            Clock clock
    ) {
        this.processRepository = processRepository;
        this.paymentService = paymentService;
        this.finalizationPrimitive = finalizationPrimitive;
        this.holdTransitionPrimitive = holdTransitionPrimitive;
        this.menuHoldPort = menuHoldPort;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ReservationDepositCommandResult finalizeOwned(
            long processId,
            long consumerAccountId,
            String idempotencyKey
    ) {
        requirePositive(processId, "processId");
        requirePositive(consumerAccountId, "consumerAccountId");
        requireIdempotencyKey(idempotencyKey);
        ReservationDepositProcess process = processRepository
                .findByIdAndConsumerAccountIdForUpdate(processId, consumerAccountId)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.RESERVATION_NOT_FOUND));
        PaymentResult payment = paymentService.getOwnedPayment(
                process.getPaymentId(), String.valueOf(consumerAccountId));
        requireSamePayment(process, payment);
        Instant now = clock.instant();
        if (payment.status() == PaymentStatus.PAID
                && payment.paidAt() != null
                && payment.paidAt().isBefore(process.getExpiresAt())
                && !process.isAbandonmentRequested()) {
            process.beginFinalization(now);
            Reservation reservation = finalizationPrimitive.finalizeResources(
                    new ReservationDepositFinalizationPrimitive.Command(
                            process.getReservationHoldId(),
                            "reservation-deposit-finalize:" + processId + ":" + idempotencyKey,
                            "CONSUMER",
                            consumerAccountId,
                            now));
            process.complete(requireReservationId(reservation), now);
            processRepository.saveAndFlush(process);
            return ReservationDepositCommandResult.completed(
                    ReservationDetailResponse.from(
                            reservation,
                            menuHoldPort.findSnapshots(reservation.getId())));
        }
        if ((payment.status() == PaymentStatus.CONFIRMING
                || payment.status() == PaymentStatus.RECONCILIATION_REQUIRED)
                && now.isBefore(process.getExpiresAt())
                && !process.isAbandonmentRequested()) {
            holdTransitionPrimitive.transition(
                    new ReservationHoldContracts.TransitionCommand(
                            process.getReservationHoldId(),
                            ReservationHoldStatus.RECONCILIATION_REQUIRED,
                            "reservation-deposit-protect:" + processId + ":" + idempotencyKey,
                            "CONSUMER",
                            consumerAccountId,
                            now,
                            null));
        }
        if (payment.status() == PaymentStatus.READY
                && !now.isBefore(process.getExpiresAt())
                && !process.isAbandonmentRequested()) {
            holdTransitionPrimitive.transition(
                    new ReservationHoldContracts.TransitionCommand(
                            process.getReservationHoldId(),
                            ReservationHoldStatus.EXPIRED,
                            "reservation-hold-expire:" + process.getReservationHoldId(),
                            "SYSTEM",
                            null,
                            process.getExpiresAt(),
                            null));
            process.expire(now);
            processRepository.saveAndFlush(process);
        }
        return ReservationDepositCommandResult.pending(toResponse(process));
    }

    private static ReservationRequestResponse toResponse(ReservationDepositProcess process) {
        return new ReservationRequestResponse(
                String.valueOf(process.getId()),
                process.getStatus(),
                process.getExpiresAt().atOffset(ZoneOffset.UTC),
                new PaymentPreparationSnapshot(
                        process.getPaymentId(),
                        process.getPortOnePaymentId(),
                        process.getPaymentOrderName(),
                        process.getPaymentAmountMinor(),
                        process.getPaymentCurrency(),
                        process.getPaymentSourceExpiresAt().atOffset(ZoneOffset.UTC),
                        process.getPaymentPreparationStatus().name()),
                process.isAbandonmentRequested(),
                null);
    }

    private static void requireSamePayment(
            ReservationDepositProcess process,
            PaymentResult payment
    ) {
        if (payment == null
                || !process.getPaymentId().equals(payment.paymentId())
                || process.getPaymentAmountMinor() != payment.amountMinor()
                || !process.getPaymentCurrency().equals(payment.currency())) {
            throw new IllegalStateException("owned payment does not match deposit process");
        }
    }

    private static long requireReservationId(Reservation reservation) {
        if (reservation == null || reservation.getId() == null || reservation.getId() <= 0) {
            throw new IllegalStateException("final reservation id is required");
        }
        return reservation.getId();
    }

    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("idempotencyKey must be a normalized UUID");
        }
    }
}
