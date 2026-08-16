package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareWaitingReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.VerifiedWaitingReservationDeposit;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.entity.WaitingActorType;
import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.entity.WaitingTransitionAudit;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTransitionAuditRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WaitingReservationConversionService {
    private static final Pattern PUBLIC_ID = Pattern.compile("^[1-9][0-9]{0,18}$");
    private final WaitingTeamRepository teams;
    private final WaitingActiveMembershipRepository memberships;
    private final WaitingTransitionAuditRepository audits;
    private final WaitingStatusEventRepository events;
    private final PaymentService payments;
    private final WaitingConversionCompensationService compensations;
    private final Clock clock;
    private final TransactionTemplate waitingTransaction;
    private final TransactionTemplate paymentWithoutTransaction;

    public WaitingReservationConversionService(
            WaitingTeamRepository teams,
            WaitingActiveMembershipRepository memberships,
            WaitingTransitionAuditRepository audits,
            WaitingStatusEventRepository events,
            PaymentService payments,
            WaitingConversionCompensationService compensations,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.teams = teams;
        this.memberships = memberships;
        this.audits = audits;
        this.events = events;
        this.payments = payments;
        this.compensations = compensations;
        this.clock = clock;
        this.waitingTransaction = new TransactionTemplate(transactionManager);
        this.waitingTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.waitingTransaction.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.waitingTransaction.setTimeout(5);
        this.paymentWithoutTransaction = new TransactionTemplate(transactionManager);
        this.paymentWithoutTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public PaymentPreparation begin(BeginCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        BeginPreflight preflight = inWaitingTransaction(() -> preflight(command));
        PaymentPreparation preparation = paymentWithoutTransaction.execute(status ->
                payments.prepareWaitingReservationDeposit(
                        new PrepareWaitingReservationDepositCommand(
                                Long.toString(preflight.waitingTeamId()),
                                preflight.consumerAccountId(),
                                command.amountMinor(),
                                command.currency(),
                                command.sourceExpiresAt(),
                                command.policyVersion(),
                                command.idempotencyKey())));
        requireMatchingPreparation(command, preparation);
        return inWaitingTransaction(() -> startLocked(command, preflight, preparation));
    }

    public boolean fail(long waitingTeamId, long expectedVersion, String paymentId) {
        return inWaitingTransaction(() -> failLocked(
                waitingTeamId, expectedVersion, paymentId));
    }

    public boolean completeVerified(CompletionCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return inWaitingTransaction(() -> completeLocked(command));
    }

    private BeginPreflight preflight(BeginCommand command) {
        WaitingTeam team = teams.findById(command.waitingTeamId())
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.WAITING_TEAM_NOT_FOUND));
        requireWaitingVersionAndStatus(team, command.expectedVersion());
        return new BeginPreflight(team.getId(), team.getConsumerAccountId());
    }

    private PaymentPreparation startLocked(
            BeginCommand command,
            BeginPreflight preflight,
            PaymentPreparation preparation
    ) {
        WaitingTeam team = teams.findByIdForUpdate(command.waitingTeamId())
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.WAITING_TEAM_NOT_FOUND));
        if (!team.getConsumerAccountId().equals(preflight.consumerAccountId())) {
            throw new ServiceException(ReservationErrorCode.WAITING_INVALID_TRANSITION);
        }
        WaitingTeamStatus before = team.getStatus();
        long expectedVersion = command.expectedVersion();
        Instant occurredAt = clock.instant();
        team.beginReservationConversion(
                expectedVersion, preparation.paymentId(), occurredAt);
        audits.save(WaitingTransitionAudit.record(
                team.getId(),
                WaitingActorType.SYSTEM,
                null,
                before,
                team.getStatus(),
                expectedVersion,
                "RESERVATION_CONVERSION_STARTED",
                "waiting-conversion-begin:" + team.getId() + ':' + preparation.paymentId(),
                occurredAt,
                occurredAt));
        events.save(WaitingStatusEvent.pending(
                team.getId(),
                team.getVersion() + 1L,
                team.getStatus(),
                occurredAt));
        return preparation;
    }

    private boolean failLocked(
            long waitingTeamId,
            long expectedVersion,
            String paymentId
    ) {
        WaitingTeam team = teams.findByIdForUpdate(waitingTeamId)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.WAITING_TEAM_NOT_FOUND));
        WaitingTeamStatus before = team.getStatus();
        Instant occurredAt = clock.instant();
        team.failReservationConversion(expectedVersion, paymentId, occurredAt);
        audits.save(WaitingTransitionAudit.record(
                team.getId(),
                WaitingActorType.SYSTEM,
                null,
                before,
                team.getStatus(),
                expectedVersion,
                "RESERVATION_CONVERSION_FAILED",
                "waiting-conversion-fail:" + team.getId() + ':' + paymentId,
                occurredAt,
                occurredAt));
        events.save(WaitingStatusEvent.pending(
                team.getId(),
                team.getVersion() + 1L,
                team.getStatus(),
                occurredAt));
        return true;
    }

    private boolean completeLocked(CompletionCommand command) {
        WaitingTeam team = teams.findByIdForUpdate(command.waitingTeamId())
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.WAITING_TEAM_NOT_FOUND));
        if (team.getStatus() == WaitingTeamStatus.RESERVATION_CONVERTED) {
            requireExactConvertedReplay(team, command);
            return true;
        }
        requireMatchingCallbackState(team, command.paymentId());
        if (team.getStatus() == WaitingTeamStatus.RESERVATION_CONVERTING) {
            VerifiedWaitingReservationDeposit verified =
                    payments.getCompletableWaitingReservationDeposit(
                            command.paymentId(),
                            command.waitingTeamId(),
                            team.getConsumerAccountId());
            if (!verified.paymentId().equals(command.paymentId())) {
                throw invalidTransition();
            }
            if (verified.status() != PaymentStatus.PAID) {
                throw invalidTransition();
            }
            completeConversion(team, command);
            return true;
        }
        VerifiedWaitingReservationDeposit verified =
                payments.getVerifiedWaitingReservationDeposit(
                        command.paymentId(),
                        command.waitingTeamId(),
                        team.getConsumerAccountId());
        if (!verified.paymentId().equals(command.paymentId())) {
            throw invalidTransition();
        }
        recordCompensation(team, verified);
        return false;
    }

    private void completeConversion(WaitingTeam team, CompletionCommand command) {
        WaitingTeamStatus before = team.getStatus();
        long expectedVersion = team.getVersion();
        Instant occurredAt = clock.instant();
        team.completeReservationConversion(
                expectedVersion,
                command.paymentId(),
                command.finalReservationId(),
                occurredAt);
        if (memberships.deleteByWaitingTeamId(team.getId()) != 1L) {
            throw new ServiceException(
                    ReservationErrorCode.WAITING_ACTIVE_MEMBERSHIP_CONFLICT);
        }
        audits.save(WaitingTransitionAudit.record(
                team.getId(),
                WaitingActorType.SYSTEM,
                null,
                before,
                team.getStatus(),
                expectedVersion,
                "RESERVATION_CONVERSION_COMPLETED",
                "waiting-conversion-complete:" + team.getId()
                        + ':' + command.paymentId()
                        + ':' + command.finalReservationId(),
                occurredAt,
                occurredAt));
        events.save(WaitingStatusEvent.pending(
                team.getId(),
                team.getVersion() + 1L,
                team.getStatus(),
                occurredAt));
    }

    private void recordCompensation(
            WaitingTeam team,
            VerifiedWaitingReservationDeposit verified
    ) {
        String sourceEventId = "waiting-conversion-terminal:"
                + team.getId() + ':' + team.getStatus() + ':' + verified.paymentId();
        String reasonCode = team.getStatus() == WaitingTeamStatus.CANCELLED
                ? "WAITING_CANCELLED"
                : "WAITING_CLOSED_BY_STORE";
        compensations.recordRequired(
                team.getId(),
                verified.paymentId(),
                verified.amountMinor(),
                verified.currency(),
                verified.sourcePolicyVersion(),
                sourceEventId,
                UUID.nameUUIDFromBytes(sourceEventId.getBytes(StandardCharsets.UTF_8)).toString(),
                reasonCode);
    }

    private static void requireMatchingCallbackState(
            WaitingTeam team,
            String paymentId
    ) {
        WaitingTeamStatus status = team.getStatus();
        boolean callbackState = status == WaitingTeamStatus.RESERVATION_CONVERTING
                || status == WaitingTeamStatus.CANCELLED
                || status == WaitingTeamStatus.CLOSED_BY_STORE;
        if (!callbackState || !paymentId.equals(team.getWaitingPaymentId())) {
            throw invalidTransition();
        }
    }

    private static void requireExactConvertedReplay(
            WaitingTeam team,
            CompletionCommand command
    ) {
        if (!command.paymentId().equals(team.getWaitingPaymentId())
                || !Long.valueOf(command.finalReservationId())
                        .equals(team.getReservationReferenceId())) {
            throw invalidTransition();
        }
    }

    private static ServiceException invalidTransition() {
        return new ServiceException(ReservationErrorCode.WAITING_INVALID_TRANSITION);
    }

    private static void requireWaitingVersionAndStatus(
            WaitingTeam team,
            long expectedVersion
    ) {
        if (team.getVersion() != expectedVersion) {
            throw new ServiceException(ReservationErrorCode.WAITING_VERSION_CONFLICT);
        }
        if (team.getStatus() != WaitingTeamStatus.WAITING) {
            throw new ServiceException(ReservationErrorCode.WAITING_INVALID_TRANSITION);
        }
    }

    private static void requireMatchingPreparation(
            BeginCommand command,
            PaymentPreparation preparation
    ) {
        if (preparation == null
                || preparation.status() != PaymentStatus.READY
                || preparation.amountMinor() != command.amountMinor()
                || !command.currency().equals(preparation.currency())
                || preparation.sourceExpiresAt() == null
                || !command.sourceExpiresAt().truncatedTo(ChronoUnit.MICROS).equals(
                        preparation.sourceExpiresAt().truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalStateException("waiting payment preparation does not match command");
        }
    }

    private <T> T inWaitingTransaction(java.util.function.Supplier<T> action) {
        return waitingTransaction.execute(status -> action.get());
    }

    public record BeginCommand(
            long waitingTeamId,
            long expectedVersion,
            long amountMinor,
            String currency,
            Instant sourceExpiresAt,
            long policyVersion,
            String idempotencyKey
    ) { }

    public record CompletionCommand(
            long waitingTeamId,
            String paymentId,
            long finalReservationId
    ) {
        public CompletionCommand {
            if (waitingTeamId <= 0) {
                throw new IllegalArgumentException("waitingTeamId must be positive");
            }
            if (paymentId == null || !PUBLIC_ID.matcher(paymentId).matches()) {
                throw new IllegalArgumentException("paymentId must be a positive public ID");
            }
            if (finalReservationId <= 0) {
                throw new IllegalArgumentException("finalReservationId must be positive");
            }
        }
    }

    private record BeginPreflight(long waitingTeamId, Long consumerAccountId) { }
}
