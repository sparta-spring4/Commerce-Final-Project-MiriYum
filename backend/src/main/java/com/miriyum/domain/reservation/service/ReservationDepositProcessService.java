package com.miriyum.domain.reservation.service;

import com.miriyum.domain.payment.dto.PaymentContracts.PaymentResult;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.dto.ReservationHoldContracts;
import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;
import com.miriyum.domain.reservation.dto.response.ReservationRequestResponse;
import com.miriyum.domain.reservation.dto.response.ReservationRequestResponse.PaymentPreparationSnapshot;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationDepositProcess;
import com.miriyum.domain.reservation.entity.ReservationDepositCauseAudit;
import com.miriyum.domain.reservation.entity.ReservationDepositRefundObligation;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.port.ReservationMenuHoldPort;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositCauseAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositRefundObligationRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Process-first coordinator for deposit finalization, abandonment, and recovery. */
@Service
public class ReservationDepositProcessService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final ReservationDepositProcessRepository processRepository;
    private final ReservationDepositCauseAuditRepository causeRepository;
    private final ReservationDepositRefundObligationRepository refundRepository;
    private final PaymentService paymentService;
    private final ReservationDepositFinalizationPrimitive finalizationPrimitive;
    private final ReservationHoldTransitionPrimitive holdTransitionPrimitive;
    private final ReservationMenuHoldPort menuHoldPort;
    private final Clock clock;
    private IdempotencyExecutor idempotencyExecutor;
    private ObjectMapper objectMapper;

    @Autowired
    public ReservationDepositProcessService(
            ReservationDepositProcessRepository processRepository,
            ReservationDepositCauseAuditRepository causeRepository,
            ReservationDepositRefundObligationRepository refundRepository,
            PaymentService paymentService,
            ReservationDepositFinalizationPrimitive finalizationPrimitive,
            ReservationHoldTransitionPrimitive holdTransitionPrimitive,
            ReservationMenuHoldPort menuHoldPort,
            Clock clock,
            IdempotencyExecutor idempotencyExecutor,
            ObjectMapper objectMapper
    ) {
        this(
                processRepository,
                causeRepository,
                refundRepository,
                paymentService,
                finalizationPrimitive,
                holdTransitionPrimitive,
                menuHoldPort,
                clock);
        this.idempotencyExecutor = idempotencyExecutor;
        this.objectMapper = objectMapper;
    }

    ReservationDepositProcessService(
            ReservationDepositProcessRepository processRepository,
            ReservationDepositCauseAuditRepository causeRepository,
            ReservationDepositRefundObligationRepository refundRepository,
            PaymentService paymentService,
            ReservationDepositFinalizationPrimitive finalizationPrimitive,
            ReservationHoldTransitionPrimitive holdTransitionPrimitive,
            ReservationMenuHoldPort menuHoldPort,
            Clock clock
    ) {
        this.processRepository = processRepository;
        this.causeRepository = causeRepository;
        this.refundRepository = refundRepository;
        this.paymentService = paymentService;
        this.finalizationPrimitive = finalizationPrimitive;
        this.holdTransitionPrimitive = holdTransitionPrimitive;
        this.menuHoldPort = menuHoldPort;
        this.clock = clock;
    }

    ReservationDepositProcessService(
            ReservationDepositProcessRepository processRepository,
            PaymentService paymentService,
            ReservationDepositFinalizationPrimitive finalizationPrimitive,
            ReservationHoldTransitionPrimitive holdTransitionPrimitive,
            ReservationMenuHoldPort menuHoldPort,
            Clock clock
    ) {
        this(
                processRepository,
                null,
                null,
                paymentService,
                finalizationPrimitive,
                holdTransitionPrimitive,
                menuHoldPort,
                clock);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ReservationDepositCommandResult finalizeOwnedIdempotent(
            long processId,
            long consumerAccountId,
            IdempotencyCommand command
    ) {
        requireCommand(command, consumerAccountId, "RESERVATION_DEPOSIT_FINALIZE");
        return executeIdempotent(
                processId,
                command,
                () -> finalizeOwned(processId, consumerAccountId, command.idempotencyKey()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ReservationDepositCommandResult abandonOwnedIdempotent(
            long processId,
            long consumerAccountId,
            IdempotencyCommand command
    ) {
        requireCommand(command, consumerAccountId, "RESERVATION_DEPOSIT_ABANDON");
        return executeIdempotent(
                processId,
                command,
                () -> abandonOwned(processId, consumerAccountId, command.idempotencyKey()));
    }

    private ReservationDepositCommandResult executeIdempotent(
            long processId,
            IdempotencyCommand command,
            Supplier<ReservationDepositCommandResult> work
    ) {
        if (idempotencyExecutor == null || objectMapper == null) {
            throw new IllegalStateException("deposit idempotency dependencies are required");
        }
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            ReservationDepositCommandResult result = work.get();
            Object payload = result.responseData();
            String resourceType = payload instanceof ReservationDetailResponse
                    ? "RESERVATION"
                    : "RESERVATION_DEPOSIT_PROCESS";
            String resourceId = payload instanceof ReservationDetailResponse reservation
                    ? reservation.reservationId()
                    : String.valueOf(processId);
            return new BusinessResult<>(
                    result.httpStatus(),
                    "SUCCESS",
                    resourceType,
                    resourceId,
                    payload);
        });
        if (outcome == null || outcome.data() == null) {
            throw new IllegalStateException("deposit idempotent outcome is required");
        }
        if ("RESERVATION".equals(outcome.resourceType())) {
            return ReservationDepositCommandResult.completed(objectMapper.treeToValue(
                    outcome.data(), ReservationDetailResponse.class));
        }
        if (!"RESERVATION_DEPOSIT_PROCESS".equals(outcome.resourceType())
                || !String.valueOf(processId).equals(outcome.resourceId())) {
            throw new IllegalStateException("deposit idempotent resource is inconsistent");
        }
        ReservationRequestResponse response = objectMapper.treeToValue(
                outcome.data(), ReservationRequestResponse.class);
        return outcome.httpStatus() == 200
                ? ReservationDepositCommandResult.terminated(response)
                : ReservationDepositCommandResult.pending(response);
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
        PaymentResult payment;
        try {
            payment = paymentService.getOwnedPayment(
                    process.getPaymentId(), String.valueOf(consumerAccountId));
        } catch (ServiceException exception) {
            if (exception.getErrorCode() != PaymentErrorCode.PAYMENT_NOT_FOUND) {
                throw exception;
            }
            return requirePaymentRecovery(process, clock.instant());
        }
        requireSamePayment(process, payment);
        Instant now = clock.instant();
        if (payment.status() == PaymentStatus.PAID
                && payment.paidAt() != null
                && payment.paidAt().isBefore(process.getExpiresAt())
                && (now.isBefore(process.getExpiresAt())
                || process.isResourcesProtected())
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
        if (payment.status() == PaymentStatus.PAID && payment.paidAt() != null) {
            boolean protectedResources = process.isResourcesProtected();
            holdTransitionPrimitive.transition(protectedResources
                    ? new ReservationHoldContracts.TransitionCommand(
                            process.getReservationHoldId(),
                            ReservationHoldStatus.RELEASED,
                            "reservation-deposit-compensate:" + processId + ":" + idempotencyKey,
                            "CONSUMER",
                            consumerAccountId,
                            now,
                            null)
                    : new ReservationHoldContracts.TransitionCommand(
                            process.getReservationHoldId(),
                            ReservationHoldStatus.EXPIRED,
                            "reservation-hold-expire:" + process.getReservationHoldId(),
                            "SYSTEM",
                            null,
                            process.getExpiresAt(),
                            null));
            requireCompensationRecords(
                    process,
                    payment,
                    now,
                    payment.paidAt().isBefore(process.getExpiresAt())
                            ? "UNPROTECTED_LATE_PAID"
                            : "PAYMENT_PAID_AT_OR_AFTER_EXPIRY");
            process.requireCompensation(now);
            processRepository.saveAndFlush(process);
            return ReservationDepositCommandResult.pending(toResponse(process));
        }
        if ((payment.status() == PaymentStatus.CONFIRMING
                || payment.status() == PaymentStatus.RECONCILIATION_REQUIRED)
                && now.isBefore(process.getExpiresAt())
                && !process.isAbandonmentRequested()) {
            protectResources(process, processId, consumerAccountId, idempotencyKey, now);
            processRepository.saveAndFlush(process);
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

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ReservationDepositCommandResult abandonOwned(
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
        Instant now = clock.instant();
        process.requestAbandonment(now);
        PaymentResult payment;
        try {
            payment = paymentService.getOwnedPayment(
                    process.getPaymentId(), String.valueOf(consumerAccountId));
        } catch (ServiceException exception) {
            if (exception.getErrorCode() != PaymentErrorCode.PAYMENT_NOT_FOUND) {
                throw exception;
            }
            return requirePaymentRecovery(process, now);
        }
        requireSamePayment(process, payment);
        if (payment.status() == PaymentStatus.READY) {
            holdTransitionPrimitive.transition(
                    new ReservationHoldContracts.TransitionCommand(
                            process.getReservationHoldId(),
                            ReservationHoldStatus.RELEASED,
                            "reservation-deposit-abandon:" + processId + ":" + idempotencyKey,
                            "CONSUMER",
                            consumerAccountId,
                            now,
                            null));
            process.abandon(now);
            processRepository.saveAndFlush(process);
            return ReservationDepositCommandResult.terminated(toResponse(process));
        }
        if (payment.status() == PaymentStatus.PAID) {
            holdTransitionPrimitive.transition(
                    new ReservationHoldContracts.TransitionCommand(
                            process.getReservationHoldId(),
                            ReservationHoldStatus.RELEASED,
                            "reservation-deposit-compensate:" + processId + ":" + idempotencyKey,
                            "CONSUMER",
                            consumerAccountId,
                            now,
                            null));
            requireCompensationRecords(process, payment, now, "ABANDONMENT_PAID");
            process.requireCompensation(now);
            processRepository.saveAndFlush(process);
            return ReservationDepositCommandResult.pending(toResponse(process));
        }
        if ((payment.status() == PaymentStatus.CONFIRMING
                || payment.status() == PaymentStatus.RECONCILIATION_REQUIRED)
                && now.isBefore(process.getExpiresAt())) {
            protectResources(process, processId, consumerAccountId, idempotencyKey, now);
        }
        processRepository.saveAndFlush(process);
        return ReservationDepositCommandResult.pending(toResponse(process));
    }

    private void requireCompensationRecords(
            ReservationDepositProcess process,
            PaymentResult payment,
            Instant observedAt,
            String causeCode
    ) {
        long processId = process.getId();
        ReservationDepositCauseAudit existingCause = causeRepository
                .findByReservationDepositProcessIdAndCauseCode(processId, causeCode)
                .orElse(null);
        if (existingCause == null) {
            causeRepository.save(ReservationDepositCauseAudit.record(
                    processId,
                    causeCode,
                    payment.paymentId(),
                    payment.status().name(),
                    payment.paidAt(),
                    observedAt));
        } else if (!existingCause.matches(
                payment.paymentId(), payment.status().name(), payment.paidAt())) {
            throw new IllegalStateException("deposit compensation cause meaning changed");
        }

        String reasonCode = "FULL_DEPOSIT_COMPENSATION";
        String sourceEventId = "reservation-deposit-compensation:" + processId;
        String stableKey = UUID.nameUUIDFromBytes(
                ("reservation-deposit-refund:" + processId + ":" + payment.paymentId())
                        .getBytes(StandardCharsets.UTF_8)).toString();
        ReservationDepositRefundObligation existingRefund = refundRepository
                .findByReservationDepositProcessIdAndPaymentIdAndReasonCode(
                        processId, payment.paymentId(), reasonCode)
                .orElse(null);
        if (existingRefund == null) {
            refundRepository.save(ReservationDepositRefundObligation.required(
                    processId,
                    payment.paymentId(),
                    process.getPaymentAmountMinor(),
                    process.getPaymentCurrency(),
                    1L,
                    sourceEventId,
                    stableKey,
                    reasonCode,
                    observedAt));
        } else if (!existingRefund.matchesRequired(
                processId,
                payment.paymentId(),
                process.getPaymentAmountMinor(),
                process.getPaymentCurrency(),
                1L,
                sourceEventId,
                stableKey,
                reasonCode)) {
            throw new IllegalStateException("deposit refund obligation meaning changed");
        }
    }

    private ReservationDepositCommandResult requirePaymentRecovery(
            ReservationDepositProcess process,
            Instant observedAt
    ) {
        String causeCode = "PAYMENT_NOT_FOUND";
        ReservationDepositCauseAudit existing = causeRepository
                .findByReservationDepositProcessIdAndCauseCode(
                        process.getId(), causeCode)
                .orElse(null);
        if (existing == null) {
            causeRepository.save(ReservationDepositCauseAudit.record(
                    process.getId(),
                    causeCode,
                    process.getPaymentId(),
                    "NOT_FOUND",
                    null,
                    observedAt));
        } else if (!existing.matches(process.getPaymentId(), "NOT_FOUND", null)) {
            throw new IllegalStateException("payment recovery cause meaning changed");
        }
        process.requireRecovery(observedAt);
        processRepository.saveAndFlush(process);
        return ReservationDepositCommandResult.pending(toResponse(process));
    }

    private void protectResources(
            ReservationDepositProcess process,
            long processId,
            long consumerAccountId,
            String idempotencyKey,
            Instant requestedAt
    ) {
        holdTransitionPrimitive.transition(
                new ReservationHoldContracts.TransitionCommand(
                        process.getReservationHoldId(),
                        ReservationHoldStatus.RECONCILIATION_REQUIRED,
                        "reservation-deposit-protect:" + processId + ":" + idempotencyKey,
                        "CONSUMER",
                        consumerAccountId,
                        requestedAt,
                        null));
        process.protectResources(requestedAt);
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

    private static void requireCommand(
            IdempotencyCommand command,
            long consumerAccountId,
            String commandType
    ) {
        if (command == null
                || !"consumer".equals(command.principalNamespace())
                || command.principalId() != consumerAccountId
                || !commandType.equals(command.commandType())) {
            throw new IllegalArgumentException("deposit idempotency command is inconsistent");
        }
    }
}
