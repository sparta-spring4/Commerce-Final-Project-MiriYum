package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingClosureCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingClosureJobSnapshot;
import com.miriyum.domain.reservation.waiting.entity.*;
import com.miriyum.domain.reservation.waiting.repository.*;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class WaitingClosureService {
    static final int MAX_ITEM_ATTEMPTS = 3;
    private static final List<WaitingTeamStatus> ACTIVE = List.of(
            WaitingTeamStatus.WAITING, WaitingTeamStatus.CALLED, WaitingTeamStatus.ARRIVED);

    private final WaitingStoreAuthorityPort authorityPort;
    private final WaitingTeamRepository teamRepository;
    private final WaitingClosureJobRepository jobRepository;
    private final WaitingClosureJobItemRepository itemRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final WaitingClosureTransactionExecutor transactionExecutor;
    private WaitingActiveMembershipRepository membershipRepository;
    private WaitingTransitionAuditRepository auditRepository;
    private WaitingStatusEventRepository eventRepository;

    @Autowired
    public WaitingClosureService(WaitingStoreAuthorityPort authorityPort, WaitingTeamRepository teamRepository,
            WaitingClosureJobRepository jobRepository, WaitingClosureJobItemRepository itemRepository,
            IdempotencyExecutor idempotencyExecutor, ObjectMapper objectMapper, Clock clock,
            WaitingActiveMembershipRepository membershipRepository,
            WaitingTransitionAuditRepository auditRepository, WaitingStatusEventRepository eventRepository,
            WaitingClosureTransactionExecutor transactionExecutor) {
        this.authorityPort = Objects.requireNonNull(authorityPort);
        this.teamRepository = Objects.requireNonNull(teamRepository);
        this.jobRepository = Objects.requireNonNull(jobRepository);
        this.itemRepository = Objects.requireNonNull(itemRepository);
        this.idempotencyExecutor = Objects.requireNonNull(idempotencyExecutor);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.membershipRepository = Objects.requireNonNull(membershipRepository);
        this.auditRepository = Objects.requireNonNull(auditRepository);
        this.eventRepository = Objects.requireNonNull(eventRepository);
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor);
    }

    @Transactional(readOnly = true)
    public com.miriyum.domain.reservation.waiting.dto.WaitingActiveTeamImpact inspectActiveTeams(long operatorId, long storeId) {
        authorityPort.requireRead(operatorId, storeId);
        return new com.miriyum.domain.reservation.waiting.dto.WaitingActiveTeamImpact(
                storeId, teamRepository.countByStoreIdAndStatusIn(storeId, ACTIVE));
    }

    public WaitingClosureCommandResult startClosure(long operatorId, long storeId, IdempotencyKey key,
            long expectedSettingsVersion) {
        authorityPort.requireMutation(operatorId, storeId);
        IdempotencyCommand command = new IdempotencyCommand("store-operator", operatorId,
                "WAITING_CLOSE_ACTIVE_TEAMS", key.value(),
                RequestFingerprint.of("storeId=" + storeId + "|expectedSettingsVersion=" + expectedSettingsVersion));
        Instant now = clock.instant();
        IdempotentOutcome outcome = executeWithRetry(() -> idempotencyExecutor.execute(command, () -> {
            List<WaitingTeam> targets = teamRepository.findActiveClosureTargets(storeId);
            WaitingClosureJob job = jobRepository.saveAndFlush(
                    WaitingClosureJob.create(storeId, expectedSettingsVersion, targets.size(), now));
            List<WaitingClosureJobItem> items = targets.stream()
                    .map(team -> WaitingClosureJobItem.pending(job.getId(), team.getId(), team.getVersion(), now))
                    .toList();
            itemRepository.saveAll(items);
            WaitingClosureJobSnapshot snapshot = WaitingClosureJobSnapshot.from(job);
            return new BusinessResult<>(HttpStatus.ACCEPTED.value(), "SUCCESS",
                    "WAITING_CLOSURE_JOB", Long.toString(job.getId()), snapshot);
        }));
        return new WaitingClosureCommandResult(outcome.httpStatus(),
                objectMapper.treeToValue(outcome.data(), WaitingClosureJobSnapshot.class));
    }

    private <T> T executeWithRetry(java.util.function.Supplier<T> work) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return transactionExecutor.execute(work);
            } catch (RuntimeException failure) {
                if (!WaitingClosureFailureClassifier.isRetryable(failure)) throw failure;
                if (attempt == 3) {
                    ServiceException conflict = new ServiceException(
                            com.miriyum.global.exception.CommonErrorCode.CONCURRENT_MODIFICATION);
                    conflict.initCause(failure);
                    throw conflict;
                }
            }
        }
        throw new IllegalStateException("unreachable retry state");
    }

    @Transactional(readOnly = true)
    public WaitingClosureJobSnapshot getClosureJob(long operatorId, long storeId, long jobId) {
        authorityPort.requireRead(operatorId, storeId);
        return jobRepository.findByIdAndStoreId(jobId, storeId)
                .map(WaitingClosureJobSnapshot::from)
                .orElseThrow(() -> new ServiceException(ReservationErrorCode.WAITING_CLOSE_JOB_NOT_FOUND));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    List<Long> claimPendingItems(int limit) {
        Instant now = clock.instant();
        List<WaitingClosureJobItem> items = itemRepository.findClaimableBatchForUpdate(
                WaitingClosureItemStatus.PENDING, PageRequest.of(0, limit));
        items.forEach(item -> {
            item.claim(now);
            jobRepository.findByIdForUpdate(item.getWaitingClosureJobId()).ifPresent(WaitingClosureJob::markProcessing);
        });
        return items.stream().map(WaitingClosureJobItem::getId).toList();
    }

    /** Single-worker topology startup recovery; invoked once before the first scheduler claim. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recoverStrandedWork() {
        Instant now = clock.instant();
        itemRepository.findAllByStatusForUpdate(WaitingClosureItemStatus.PROCESSING)
                .forEach(item -> {
                    if (item.getAttemptCount() < MAX_ITEM_ATTEMPTS) item.requeue();
                    else item.requireReconciliation(now);
                });
        jobRepository.findAllByStatusForUpdate(WaitingClosureJobStatus.PROCESSING)
                .forEach(job -> {
                    reconcile(job, now);
                    if (job.getStatus() == WaitingClosureJobStatus.PROCESSING) job.resumePending();
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void processClaimedItem(long itemId) {
        Instant now = clock.instant();
        WaitingClosureJobItem item = itemRepository.findByIdForUpdate(itemId)
                .orElseThrow(() -> new ServiceException(ReservationErrorCode.WAITING_CLOSE_JOB_ITEM_FAILED));
        WaitingClosureJob job = jobRepository.findByIdForUpdate(item.getWaitingClosureJobId())
                .orElseThrow(() -> new ServiceException(ReservationErrorCode.WAITING_CLOSE_JOB_NOT_FOUND));
        WaitingTeam team = teamRepository.findByIdForUpdate(item.getWaitingTeamId())
                .orElseThrow(() -> new ServiceException(ReservationErrorCode.WAITING_CLOSE_JOB_ITEM_FAILED));
        if (ACTIVE.contains(team.getStatus())) {
            WaitingTeamStatus before = team.getStatus();
            long expected = team.getVersion();
            team.closeByStore(expected, now);
            if (membershipRepository.deleteByWaitingTeamId(team.getId()) != 1L) {
                throw new ServiceException(ReservationErrorCode.WAITING_ACTIVE_MEMBERSHIP_CONFLICT);
            }
            String commandId = "waiting-closure:" + job.getId() + ':' + item.getId();
            auditRepository.save(WaitingTransitionAudit.record(team.getId(), WaitingActorType.SYSTEM, null,
                    before, team.getStatus(), expected, "CLOSED_BY_STORE", commandId, now, now));
            eventRepository.save(WaitingStatusEvent.pending(team.getId(), team.getVersion(), team.getStatus(), now));
        }
        item.complete(now);
        reconcile(job, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordFailure(long itemId, boolean retryable) {
        Instant now = clock.instant();
        WaitingClosureJobItem item = itemRepository.findByIdForUpdate(itemId)
                .orElseThrow(() -> new ServiceException(ReservationErrorCode.WAITING_CLOSE_JOB_ITEM_FAILED));
        WaitingClosureJob job = jobRepository.findByIdForUpdate(item.getWaitingClosureJobId())
                .orElseThrow(() -> new ServiceException(ReservationErrorCode.WAITING_CLOSE_JOB_NOT_FOUND));
        if (retryable && item.getAttemptCount() < MAX_ITEM_ATTEMPTS) item.requeue();
        else item.requireReconciliation(now);
        reconcile(job, now);
    }

    private void reconcile(WaitingClosureJob job, Instant now) {
        long completed = itemRepository.countByWaitingClosureJobIdAndStatus(job.getId(), WaitingClosureItemStatus.COMPLETED);
        long failed = itemRepository.countByWaitingClosureJobIdAndStatus(job.getId(), WaitingClosureItemStatus.FAILED);
        long reconciliation = itemRepository.countByWaitingClosureJobIdAndStatus(
                job.getId(), WaitingClosureItemStatus.RECONCILIATION_REQUIRED);
        job.reconcile(completed, failed, reconciliation, now);
    }
}
