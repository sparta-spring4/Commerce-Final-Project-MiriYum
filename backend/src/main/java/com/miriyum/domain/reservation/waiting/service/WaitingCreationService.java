package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamSnapshot;
import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import com.miriyum.domain.reservation.waiting.entity.WaitingActorType;
import com.miriyum.domain.reservation.waiting.entity.WaitingQueueSequence;
import com.miriyum.domain.reservation.waiting.entity.WaitingReceptionMode;
import com.miriyum.domain.reservation.waiting.entity.WaitingSetting;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.entity.WaitingTransitionAudit;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingQueueSequenceRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingSettingRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTransitionAuditRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.domain.store.service.StoreTransactionEligibilityService;
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
import java.util.function.IntToLongFunction;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final WaitingSettingRepository settingRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final WaitingCreationTransactionExecutor transactionExecutor;
    private final StoreTransactionEligibilityService storeEligibility;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final IntToLongFunction retryDelayMillis;
    private final RetrySleeper retrySleeper;

    @FunctionalInterface interface RetrySleeper { void sleep(long millis) throws InterruptedException; }

    @Autowired
    public WaitingCreationService(
            WaitingQueueSequenceRepository sequenceRepository,
            WaitingTeamRepository teamRepository,
            WaitingActiveMembershipRepository membershipRepository,
            WaitingTransitionAuditRepository auditRepository,
            WaitingStatusEventRepository eventRepository,
            WaitingSettingRepository settingRepository,
            IdempotencyExecutor idempotencyExecutor,
            WaitingCreationTransactionExecutor transactionExecutor,
            StoreTransactionEligibilityService storeEligibility,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this(sequenceRepository, teamRepository, membershipRepository, auditRepository,
                eventRepository, settingRepository, idempotencyExecutor, transactionExecutor,
                storeEligibility,
                objectMapper, clock,
                WaitingCreationService::defaultDelayMillis, Thread::sleep);
    }

    WaitingCreationService(WaitingQueueSequenceRepository sequenceRepository,
            WaitingTeamRepository teamRepository, WaitingActiveMembershipRepository membershipRepository,
            WaitingTransitionAuditRepository auditRepository, WaitingStatusEventRepository eventRepository,
            WaitingSettingRepository settingRepository,
            IdempotencyExecutor idempotencyExecutor, WaitingCreationTransactionExecutor transactionExecutor,
            StoreTransactionEligibilityService storeEligibility, ObjectMapper objectMapper,
            Clock clock, IntToLongFunction retryDelayMillis,
            RetrySleeper retrySleeper) {
        this.sequenceRepository = Objects.requireNonNull(sequenceRepository);
        this.teamRepository = Objects.requireNonNull(teamRepository);
        this.membershipRepository = Objects.requireNonNull(membershipRepository);
        this.auditRepository = Objects.requireNonNull(auditRepository);
        this.eventRepository = Objects.requireNonNull(eventRepository);
        this.settingRepository = Objects.requireNonNull(settingRepository);
        this.idempotencyExecutor = Objects.requireNonNull(idempotencyExecutor);
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor);
        this.storeEligibility = Objects.requireNonNull(storeEligibility);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.retryDelayMillis = Objects.requireNonNull(retryDelayMillis);
        this.retrySleeper = Objects.requireNonNull(retrySleeper);
    }

    /**
     * 현재 매장 설정이 활성이고 PAUSED가 아닐 때 중앙 FIFO 팀을 멱등 생성한다.
     *
     * <p>설정 행을 팀·membership·순번 생성과 같은 트랜잭션에서 잠그므로, 설정 비활성화와
     * 경합하면 먼저 확정된 명령만 효력을 갖는다.
     *
     * @param storeId 접수할 매장 ID
     * @param consumerAccountId 접수하는 소비자 계정 ID
     * @param businessDate 순번이 귀속되는 영업일
     * @param partySize 방문 인원
     * @param source 접수 출처
     * @param key 소비자 생성 명령 멱등 키
     * @return 생성되거나 replay된 웨이팅 팀
     */
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
                    storeEligibility.requireWaitingTransactionEligibility(storeId);
                    WaitingSetting setting = settingRepository.findByStoreIdForUpdate(storeId)
                            .orElseThrow(WaitingCreationService::receptionClosed);
                    if (!setting.isEnabled()
                            || setting.getReceptionMode() == WaitingReceptionMode.PAUSED) {
                        throw receptionClosed();
                    }
                    if (membershipRepository.findByConsumerAccountId(consumerAccountId).isPresent()) {
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
                if (WaitingCreationFailureClassifier.isMembershipConflict(failure)) {
                    throw membershipConflict();
                }
                if (!WaitingCreationFailureClassifier.isRetryable(failure)) throw failure;
                last = failure;
            } catch (RuntimeException failure) {
                if (!WaitingCreationFailureClassifier.isRetryable(failure)) {
                    throw failure;
                }
                last = failure;
            }
            if (attempt < MAX_ATTEMPTS) sleepBeforeRetry(attempt, last);
        }
        ServiceException conflict = new ServiceException(
                com.miriyum.global.exception.CommonErrorCode.CONCURRENT_MODIFICATION);
        conflict.initCause(last);
        throw conflict;
    }

    private void sleepBeforeRetry(int attempt, RuntimeException failure) {
        try { retrySleeper.sleep(retryDelayMillis.applyAsLong(attempt)); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); interrupted.addSuppressed(failure);
            ServiceException conflict = new ServiceException(
                    com.miriyum.global.exception.CommonErrorCode.CONCURRENT_MODIFICATION);
            conflict.initCause(interrupted); throw conflict;
        }
    }

    static long defaultDelayMillis(int attempt) {
        return switch (attempt) {
            case 1 -> ThreadLocalRandom.current().nextLong(100, 201);
            case 2 -> ThreadLocalRandom.current().nextLong(300, 501);
            default -> throw new IllegalArgumentException("unsupported retry attempt");
        };
    }

    private static ServiceException membershipConflict() {
        return new ServiceException(ReservationErrorCode.ACCOUNT_ACTIVE_WAITING_EXISTS);
    }

    private static ServiceException receptionClosed() {
        return new ServiceException(ReservationErrorCode.WAITING_RECEPTION_CLOSED);
    }
}
