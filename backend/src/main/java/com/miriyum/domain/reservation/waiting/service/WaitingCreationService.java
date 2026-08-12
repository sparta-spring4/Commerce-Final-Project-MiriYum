package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamSnapshot;
import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import com.miriyum.domain.reservation.waiting.entity.WaitingActorType;
import com.miriyum.domain.reservation.waiting.entity.WaitingQueueSequence;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.entity.WaitingTransitionAudit;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingQueueSequenceRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTransitionAuditRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 검증된 입력으로 중앙 FIFO 팀과 활성 membership을 원자적으로 생성한다. */
@Service
public class WaitingCreationService {

    private static final int MAX_ATTEMPTS = 3;

    private final WaitingQueueSequenceRepository sequenceRepository;
    private final WaitingTeamRepository teamRepository;
    private final WaitingActiveMembershipRepository membershipRepository;
    private final WaitingTransitionAuditRepository auditRepository;
    private final WaitingStatusEventRepository eventRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final WaitingCreationTransactionExecutor transactionExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WaitingCreationService(
            WaitingQueueSequenceRepository sequenceRepository,
            WaitingTeamRepository teamRepository,
            WaitingActiveMembershipRepository membershipRepository,
            WaitingTransitionAuditRepository auditRepository,
            WaitingStatusEventRepository eventRepository,
            IdempotencyExecutor idempotencyExecutor,
            WaitingCreationTransactionExecutor transactionExecutor,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.sequenceRepository = Objects.requireNonNull(sequenceRepository);
        this.teamRepository = Objects.requireNonNull(teamRepository);
        this.membershipRepository = Objects.requireNonNull(membershipRepository);
        this.auditRepository = Objects.requireNonNull(auditRepository);
        this.eventRepository = Objects.requireNonNull(eventRepository);
        this.idempotencyExecutor = Objects.requireNonNull(idempotencyExecutor);
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    public WaitingCommandResult create(
            long storeId,
            long consumerAccountId,
            LocalDate businessDate,
            int partySize,
            WaitingSource source,
            IdempotencyKey key
    ) {
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(key, "key must not be null");
        IdempotencyCommand command = new IdempotencyCommand(
                "consumer",
                consumerAccountId,
                "WAITING_TEAM_CREATE",
                key.value(),
                RequestFingerprint.of("storeId=" + storeId
                        + "|consumerAccountId=" + consumerAccountId
                        + "|businessDate=" + businessDate
                        + "|partySize=" + partySize
                        + "|source=" + source)
        );
        Instant occurredAt = clock.instant();
        return executeWithRetry(() -> createInTransaction(
                storeId, consumerAccountId, businessDate, partySize, source, command, occurredAt));
    }

    private WaitingCommandResult createInTransaction(
            long storeId,
            long consumerAccountId,
            LocalDate businessDate,
            int partySize,
            WaitingSource source,
            IdempotencyCommand command,
            Instant occurredAt
    ) {
        return transactionExecutor.execute(() -> {
            IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
                    if (membershipRepository.findByStoreIdAndConsumerAccountId(
                            storeId, consumerAccountId).isPresent()) {
                        throw membershipConflict();
                    }
                    WaitingQueueSequence sequence = lockSequence(storeId, businessDate);
                    WaitingTeam team = teamRepository.saveAndFlush(WaitingTeam.create(
                            storeId, consumerAccountId, businessDate, partySize, source,
                            sequence.allocate(), occurredAt));
                    membershipRepository.saveAndFlush(WaitingActiveMembership.create(
                            storeId, consumerAccountId, team.getId(), occurredAt));
                    String commandId = "consumer:" + consumerAccountId
                            + ":WAITING_TEAM_CREATE:" + command.idempotencyKey();
                    auditRepository.save(WaitingTransitionAudit.record(
                            team.getId(), WaitingActorType.CONSUMER, consumerAccountId,
                            null, WaitingTeamStatus.WAITING, -1L, "WAITING_CREATED",
                            commandId, occurredAt, clock.instant()));
                    eventRepository.save(WaitingStatusEvent.pending(
                            team.getId(), 1L, WaitingTeamStatus.WAITING, occurredAt));
                    WaitingTeamSnapshot snapshot = WaitingTeamSnapshot.from(team);
                    return new BusinessResult<>(HttpStatus.OK.value(), "SUCCESS",
                            "WAITING_TEAM", Long.toString(team.getId()), snapshot);
            });
            return new WaitingCommandResult(outcome.httpStatus(),
                    objectMapper.treeToValue(outcome.data(), WaitingTeamSnapshot.class));
        });
    }

    private WaitingQueueSequence lockSequence(long storeId, LocalDate businessDate) {
        WaitingQueueSequence.Key key = new WaitingQueueSequence.Key(storeId, businessDate);
        if (!sequenceRepository.existsById(key)) {
            try {
                sequenceRepository.saveAndFlush(WaitingQueueSequence.create(storeId, businessDate));
            } catch (DataIntegrityViolationException ignoredConcurrentBootstrap) {
                // A concurrent creator committed the same sequence row; the outer retry reloads it.
                throw ignoredConcurrentBootstrap;
            }
        }
        return sequenceRepository.findByStoreIdAndBusinessDateForUpdate(storeId, businessDate)
                .orElseThrow(() -> new IllegalStateException("waiting sequence row not visible"));
    }

    private <T> T executeWithRetry(Supplier<T> work) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return work.get();
            } catch (DataIntegrityViolationException failure) {
                if (containsConstraint(failure,
                        "uk_waiting_active_memberships_store_consumer")) {
                    throw membershipConflict();
                }
                last = failure;
            } catch (RuntimeException failure) {
                if (!WaitingClosureFailureClassifier.isRetryable(failure)) {
                    throw failure;
                }
                last = failure;
            }
        }
        ServiceException conflict = new ServiceException(
                com.miriyum.global.exception.CommonErrorCode.CONCURRENT_MODIFICATION);
        conflict.initCause(last);
        throw conflict;
    }

    private static boolean containsConstraint(Throwable failure, String constraint) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    private static ServiceException membershipConflict() {
        return new ServiceException(ReservationErrorCode.WAITING_ACTIVE_MEMBERSHIP_CONFLICT);
    }
}
