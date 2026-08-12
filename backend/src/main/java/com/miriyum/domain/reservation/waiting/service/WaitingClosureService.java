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
import java.time.Duration;
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
    private WaitingActiveMembershipRepository membershipRepository;
    private WaitingTransitionAuditRepository auditRepository;
    private WaitingStatusEventRepository eventRepository;

    @Autowired
    public WaitingClosureService(WaitingStoreAuthorityPort authorityPort, WaitingTeamRepository teamRepository,
            WaitingClosureJobRepository jobRepository, WaitingClosureJobItemRepository itemRepository,
            IdempotencyExecutor idempotencyExecutor, ObjectMapper objectMapper, Clock clock,
            WaitingActiveMembershipRepository membershipRepository,
            WaitingTransitionAuditRepository auditRepository, WaitingStatusEventRepository eventRepository) {
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
    }

    @Transactional(readOnly = true)
    public com.miriyum.domain.reservation.waiting.dto.WaitingActiveTeamImpact inspectActiveTeams(long operatorId, long storeId) {
        authorityPort.requireRead(operatorId, storeId);
        return new com.miriyum.domain.reservation.waiting.dto.WaitingActiveTeamImpact(
                storeId, teamRepository.countByStoreIdAndStatusIn(storeId, ACTIVE));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WaitingClosureCommandResult startClosure(long operatorId, long storeId, IdempotencyKey key,
            long expectedSettingsVersion) {
        authorityPort.requireMutation(operatorId, storeId);
        IdempotencyCommand command = new IdempotencyCommand("store-operator", operatorId,
                "WAITING_CLOSE_ACTIVE_TEAMS", key.value(),
                RequestFingerprint.of("storeId=" + storeId + "|expectedSettingsVersion=" + expectedSettingsVersion));
        Instant now = clock.instant();
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
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
        });
        return new WaitingClosureCommandResult(outcome.httpStatus(),
                objectMapper.treeToValue(outcome.data(), WaitingClosureJobSnapshot.class));
    }

    @Transactional(readOnly = true)
    public WaitingClosureJobSnapshot getClosureJob(long operatorId, long storeId, long jobId) {
        authorityPort.requireRead(operatorId, storeId);
        return jobRepository.findByIdAndStoreId(jobId, storeId)
                .map(WaitingClosureJobSnapshot::from)
                .orElseThrow(() -> new ServiceException(ReservationErrorCode.WAITING_CLOSE_JOB_NOT_FOUND));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    List<WaitingClosureClaim> claimPendingItems(String owner, int limit, Duration leaseDuration) {
        Instant now = clock.instant();
        List<WaitingClosureJobItem> items = itemRepository.findGloballyClaimableForUpdate(
                WaitingClosureItemStatus.PENDING, WaitingClosureItemStatus.PROCESSING,
                now, PageRequest.of(0, Math.min(limit, 100)));
        java.util.ArrayList<WaitingClosureClaim> claimed = new java.util.ArrayList<>();
        for (WaitingClosureJobItem item : items) {
            WaitingClosureJob job = jobRepository.findByIdForUpdate(item.getWaitingClosureJobId()).orElseThrow();
            if (item.getStatus() == WaitingClosureItemStatus.PROCESSING
                    && item.getAttemptCount() >= MAX_ITEM_ATTEMPTS) {
                item.reconcileExpired(now); reconcile(job, now); continue;
            }
            item.claim(owner, now, now.plus(leaseDuration));
            job.markProcessing();
            claimed.add(new WaitingClosureClaim(item.getId(), owner, item.getClaimToken()));
        }
        return List.copyOf(claimed);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean processClaimedItem(WaitingClosureClaim claim) {
        Instant now = clock.instant();
        WaitingClosureJobItem item = itemRepository.findByIdForUpdate(claim.itemId())
                .orElseThrow(() -> new ServiceException(ReservationErrorCode.WAITING_CLOSE_JOB_ITEM_FAILED));
        if (!item.isOwnedBy(claim.owner(), claim.token())) return false;
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
            eventRepository.save(WaitingStatusEvent.pending(
                    team.getId(), team.getVersion() + 1L, team.getStatus(), now));
        }
        item.complete(claim.owner(), claim.token(), now);
        reconcile(job, now);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean recordFailure(WaitingClosureClaim claim, boolean retryable) {
        Instant now = clock.instant();
        WaitingClosureJobItem item = itemRepository.findByIdForUpdate(claim.itemId())
                .orElseThrow(() -> new ServiceException(ReservationErrorCode.WAITING_CLOSE_JOB_ITEM_FAILED));
        if (!item.isOwnedBy(claim.owner(), claim.token())) return false;
        WaitingClosureJob job = jobRepository.findByIdForUpdate(item.getWaitingClosureJobId())
                .orElseThrow(() -> new ServiceException(ReservationErrorCode.WAITING_CLOSE_JOB_NOT_FOUND));
        if (retryable && item.getAttemptCount() < MAX_ITEM_ATTEMPTS) item.requeue(claim.owner(), claim.token());
        else item.requireReconciliation(claim.owner(), claim.token(), now);
        reconcile(job, now);
        return true;
    }

    private void reconcile(WaitingClosureJob job, Instant now) {
        long completed = itemRepository.countByWaitingClosureJobIdAndStatus(job.getId(), WaitingClosureItemStatus.COMPLETED);
        long failed = itemRepository.countByWaitingClosureJobIdAndStatus(job.getId(), WaitingClosureItemStatus.FAILED);
        long reconciliation = itemRepository.countByWaitingClosureJobIdAndStatus(
                job.getId(), WaitingClosureItemStatus.RECONCILIATION_REQUIRED);
        job.reconcile(completed, failed, reconciliation, now);
    }
}
