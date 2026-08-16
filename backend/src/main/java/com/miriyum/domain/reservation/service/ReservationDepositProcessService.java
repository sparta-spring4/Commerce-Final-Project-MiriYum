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
import com.miriyum.domain.reservation.entity.ReservationDepositProcessStatus;
import com.miriyum.domain.reservation.entity.ReservationDepositCauseAudit;
import com.miriyum.domain.reservation.entity.ReservationDepositRefundObligation;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.port.ReservationMenuHoldPort;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositCauseAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositRefundObligationRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
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
    private final Duration reconciliationLeaseDuration;
    private final Duration reconciliationPollDelay;
    private IdempotencyExecutor idempotencyExecutor;
    private ObjectMapper objectMapper;
    private ReservationRepository reservationRepository;

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
            ReservationRepository reservationRepository,
            IdempotencyExecutor idempotencyExecutor,
            ObjectMapper objectMapper,
            @Qualifier("reservationDepositProcessLeaseDuration")
            Duration reconciliationLeaseDuration,
            @Qualifier("reservationDepositProcessPollDelay")
            Duration reconciliationPollDelay
    ) {
        this(
                processRepository,
                causeRepository,
                refundRepository,
                paymentService,
                finalizationPrimitive,
                holdTransitionPrimitive,
                menuHoldPort,
                clock,
                reconciliationLeaseDuration,
                reconciliationPollDelay);
        this.idempotencyExecutor = idempotencyExecutor;
        this.objectMapper = objectMapper;
        this.reservationRepository = reservationRepository;
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
        this(
                processRepository,
                causeRepository,
                refundRepository,
                paymentService,
                finalizationPrimitive,
                holdTransitionPrimitive,
                menuHoldPort,
                clock,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5));
    }

    ReservationDepositProcessService(
            ReservationDepositProcessRepository processRepository,
            ReservationDepositCauseAuditRepository causeRepository,
            ReservationDepositRefundObligationRepository refundRepository,
            PaymentService paymentService,
            ReservationDepositFinalizationPrimitive finalizationPrimitive,
            ReservationHoldTransitionPrimitive holdTransitionPrimitive,
            ReservationMenuHoldPort menuHoldPort,
            Clock clock,
            Duration reconciliationLeaseDuration,
            Duration reconciliationPollDelay
    ) {
        if (reconciliationLeaseDuration == null
                || reconciliationLeaseDuration.isZero()
                || reconciliationLeaseDuration.isNegative()
                || reconciliationPollDelay == null
                || reconciliationPollDelay.isZero()
                || reconciliationPollDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "reconciliation lease and poll delay must be positive");
        }
        this.processRepository = processRepository;
        this.causeRepository = causeRepository;
        this.refundRepository = refundRepository;
        this.paymentService = paymentService;
        this.finalizationPrimitive = finalizationPrimitive;
        this.holdTransitionPrimitive = holdTransitionPrimitive;
        this.menuHoldPort = menuHoldPort;
        this.clock = clock;
        this.reconciliationLeaseDuration = reconciliationLeaseDuration;
        this.reconciliationPollDelay = reconciliationPollDelay;
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

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public List<Claim> claimDue(String owner, int limit) {
        if (owner == null || owner.isBlank() || owner.length() > 64) {
            throw new IllegalArgumentException("owner must be 1 to 64 characters");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        Instant now = clock.instant();
        List<ReservationDepositProcess> processes = processRepository
                .findReconciliationClaimableForUpdate(
                        now,
                        PageRequest.of(0, limit));
        if (processes == null) {
            throw new IllegalStateException("claimable deposit processes are required");
        }
        List<Claim> claims = processes.stream()
                .map(process -> new Claim(
                        requireProcessId(process),
                        owner,
                        process.claimReconciliation(
                                owner,
                                now,
                                now.plus(reconciliationLeaseDuration))))
                .toList();
        processRepository.saveAllAndFlush(processes);
        return claims;
    }

    public record Claim(long processId, String owner, long token) {

        public Claim {
            if (processId <= 0
                    || owner == null
                    || owner.isBlank()
                    || owner.length() > 64
                    || token <= 0) {
                throw new IllegalArgumentException("deposit process claim is invalid");
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public boolean reconcileClaimed(Claim claim) {
        if (claim == null) {
            throw new IllegalArgumentException("deposit process claim is required");
        }
        ReservationDepositProcess process = processRepository
                .findByIdForUpdate(claim.processId())
                .orElse(null);
        Instant now = clock.instant();
        if (process == null
                || !process.isReconciliationClaimOwnedBy(
                        claim.owner(), claim.token(), now)) {
            return false;
        }
        if (process.getStatus() != ReservationDepositProcessStatus.AWAITING_PAYMENT
                && process.getStatus() != ReservationDepositProcessStatus.RECOVERY_REQUIRED) {
            process.completeReconciliationClaim(
                    claim.owner(), claim.token(), now);
            processRepository.saveAndFlush(process);
            return false;
        }
        PaymentResult payment;
        try {
            payment = paymentService.getOwnedPayment(
                    process.getPaymentId(),
                    String.valueOf(process.getConsumerAccountId()));
        } catch (ServiceException exception) {
            if (exception.getErrorCode() != PaymentErrorCode.PAYMENT_NOT_FOUND) {
                throw exception;
            }
            markPaymentRecovery(process, now);
            process.requeueReconciliation(
                    claim.owner(),
                    claim.token(),
                    now,
                    reconciliationPollDelay);
            processRepository.saveAndFlush(process);
            return true;
        }
        requireSamePayment(process, payment);
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
                            "reservation-deposit-worker-finalize:" + process.getId(),
                            "SYSTEM",
                            null,
                            now));
            process.complete(requireReservationId(reservation), now);
            process.completeReconciliationClaim(
                    claim.owner(), claim.token(), now);
            processRepository.saveAndFlush(process);
            return true;
        }
        if (payment.status() == PaymentStatus.PAID && payment.paidAt() != null) {
            boolean expireUnprotected = !process.isResourcesProtected()
                    && !now.isBefore(process.getExpiresAt());
            holdTransitionPrimitive.transition(expireUnprotected
                    ? new ReservationHoldContracts.TransitionCommand(
                            process.getReservationHoldId(),
                            ReservationHoldStatus.EXPIRED,
                            "reservation-hold-expire:" + process.getReservationHoldId(),
                            "SYSTEM",
                            null,
                            process.getExpiresAt(),
                            null)
                    : new ReservationHoldContracts.TransitionCommand(
                            process.getReservationHoldId(),
                            ReservationHoldStatus.RELEASED,
                            "reservation-deposit-worker-compensate:" + process.getId(),
                            "SYSTEM",
                            null,
                            now,
                            null));
            String causeCode = process.isAbandonmentRequested()
                    ? "ABANDONMENT_PAID"
                    : payment.paidAt().isBefore(process.getExpiresAt())
                            ? "UNPROTECTED_LATE_PAID"
                            : "PAYMENT_PAID_AT_OR_AFTER_EXPIRY";
            requireCompensationRecords(process, payment, now, causeCode);
            process.requireCompensation(now);
            process.completeReconciliationClaim(
                    claim.owner(), claim.token(), now);
            processRepository.saveAndFlush(process);
            return true;
        }
        if ((payment.status() == PaymentStatus.CONFIRMING
                || payment.status() == PaymentStatus.RECONCILIATION_REQUIRED)
                && ((now.isBefore(process.getExpiresAt())
                && !process.isAbandonmentRequested())
                || process.isResourcesProtected())) {
            if (!process.isResourcesProtected()) {
                holdTransitionPrimitive.transition(
                        new ReservationHoldContracts.TransitionCommand(
                                process.getReservationHoldId(),
                                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                                "reservation-deposit-protect:"
                                        + process.getId() + ":system",
                                "SYSTEM",
                                null,
                                now,
                                null));
                process.protectResources(now);
            }
            if (now.isBefore(process.getExpiresAt())) {
                requeueClaimBeforeExpiry(process, claim, now);
            } else {
                process.requeueReconciliation(
                        claim.owner(),
                        claim.token(),
                        now,
                        reconciliationPollDelay);
            }
            processRepository.saveAndFlush(process);
            return true;
        }
        if ((payment.status() == PaymentStatus.READY
                || payment.status() == PaymentStatus.CONFIRMING
                || payment.status() == PaymentStatus.RECONCILIATION_REQUIRED)
                && !now.isBefore(process.getExpiresAt())
                && !process.isResourcesProtected()
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
            process.completeReconciliationClaim(
                    claim.owner(), claim.token(), now);
            processRepository.saveAndFlush(process);
            return true;
        }
        if (payment.status() == PaymentStatus.READY
                && now.isBefore(process.getExpiresAt())
                && !process.isAbandonmentRequested()) {
            requeueClaimBeforeExpiry(process, claim, now);
            processRepository.saveAndFlush(process);
            return true;
        }
        throw new IllegalStateException(
                "claimed deposit process requires payment reconciliation");
    }

    private void requeueClaimBeforeExpiry(
            ReservationDepositProcess process,
            Claim claim,
            Instant now
    ) {
        Duration untilExpiry = Duration.between(now, process.getExpiresAt());
        Duration delay = untilExpiry.compareTo(reconciliationPollDelay) < 0
                ? untilExpiry
                : reconciliationPollDelay;
        process.requeueReconciliation(
                claim.owner(), claim.token(), now, delay);
    }

    @Transactional(readOnly = true)
    public ReservationRequestResponse getOwnedRequest(
            long processId,
            long consumerAccountId
    ) {
        ReservationDepositProcess process = processRepository
                .findByIdAndConsumerAccountId(processId, consumerAccountId)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.RESERVATION_NOT_FOUND));
        ReservationDetailResponse reservation = null;
        if (process.getFinalReservationId() != null) {
            if (reservationRepository == null) {
                throw new IllegalStateException("reservation query dependency is required");
            }
            Reservation finalReservation = reservationRepository
                    .findByIdAndConsumerAccountId(
                            process.getFinalReservationId(), consumerAccountId)
                    .orElseThrow(() -> new IllegalStateException(
                            "completed deposit reservation is missing"));
            reservation = ReservationDetailResponse.from(
                    finalReservation,
                    menuHoldPort.findSnapshots(finalReservation.getId()));
        }
        return toResponse(process, reservation);
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
    public ReservationDepositCommandResult reconcileLinkedExpiration(long processId) {
        requirePositive(processId, "processId");
        ReservationDepositProcess process = processRepository.findByIdForUpdate(processId)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.RESERVATION_NOT_FOUND));
        PaymentResult payment = paymentService.getOwnedPayment(
                process.getPaymentId(), String.valueOf(process.getConsumerAccountId()));
        requireSamePayment(process, payment);
        Instant now = clock.instant();
        if (payment.status() == PaymentStatus.PAID
                && payment.paidAt() != null
                && !now.isBefore(process.getExpiresAt())
                && !process.isResourcesProtected()) {
            holdTransitionPrimitive.transition(
                    new ReservationHoldContracts.TransitionCommand(
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
        if ((payment.status() != PaymentStatus.READY
                && payment.status() != PaymentStatus.CONFIRMING
                && payment.status() != PaymentStatus.RECONCILIATION_REQUIRED)
                || now.isBefore(process.getExpiresAt())
                || process.isAbandonmentRequested()) {
            throw new IllegalStateException(
                    "linked expiration requires payment reconciliation");
        }
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
        markPaymentRecovery(process, observedAt);
        processRepository.saveAndFlush(process);
        return ReservationDepositCommandResult.pending(toResponse(process));
    }

    private void markPaymentRecovery(
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
        return toResponse(process, null);
    }

    private static ReservationRequestResponse toResponse(
            ReservationDepositProcess process,
            ReservationDetailResponse reservation
    ) {
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
                reservation);
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

    private static long requireProcessId(ReservationDepositProcess process) {
        if (process == null || process.getId() == null || process.getId() <= 0) {
            throw new IllegalStateException("deposit process id is required");
        }
        return process.getId();
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
