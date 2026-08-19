package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingActiveTeamImpact;
import com.miriyum.domain.reservation.waiting.dto.WaitingClosureCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingClosureJobSnapshot;
import com.miriyum.domain.reservation.waiting.entity.WaitingActorType;
import com.miriyum.domain.reservation.waiting.entity.WaitingClosureItemStatus;
import com.miriyum.domain.reservation.waiting.entity.WaitingClosureJob;
import com.miriyum.domain.reservation.waiting.entity.WaitingClosureJobItem;
import com.miriyum.domain.reservation.waiting.entity.WaitingSetting;
import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.entity.WaitingTransitionAudit;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingClosureJobItemRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingClosureJobRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingSettingRepository;
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
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 활성 웨이팅 팀 영향 조회와 version-bound 비동기 일괄 종결 작업을 소유한다.
 *
 * <p>worker는 작업 생성 당시 설정 version과 현재 비활성 설정이 일치할 때만 팀을 종결하며,
 * 재활성화되거나 더 최신 설정이 존재하면 오래된 작업 항목을 부수효과 없이 완료한다.
 */
@Service
public class WaitingClosureService {
    static final int MAX_ITEM_ATTEMPTS = 3;
    private static final List<WaitingTeamStatus> ACTIVE = List.of(
            WaitingTeamStatus.WAITING,
            WaitingTeamStatus.CALLED,
            WaitingTeamStatus.ARRIVED,
            WaitingTeamStatus.RESERVATION_CONVERTING);

    private final WaitingStoreAuthorityPort authorityPort;
    private final WaitingTeamRepository teamRepository;
    private final WaitingClosureJobRepository jobRepository;
    private final WaitingClosureJobItemRepository itemRepository;
    private final WaitingSettingRepository settingRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private WaitingActiveMembershipRepository membershipRepository;
    private WaitingTransitionAuditRepository auditRepository;
    private WaitingStatusEventRepository eventRepository;

    @Autowired
    public WaitingClosureService(WaitingStoreAuthorityPort authorityPort, WaitingTeamRepository teamRepository,
            WaitingClosureJobRepository jobRepository, WaitingClosureJobItemRepository itemRepository,
            WaitingSettingRepository settingRepository,
            IdempotencyExecutor idempotencyExecutor, ObjectMapper objectMapper, Clock clock,
            WaitingActiveMembershipRepository membershipRepository,
            WaitingTransitionAuditRepository auditRepository, WaitingStatusEventRepository eventRepository) {
        this.authorityPort = Objects.requireNonNull(authorityPort);
        this.teamRepository = Objects.requireNonNull(teamRepository);
        this.jobRepository = Objects.requireNonNull(jobRepository);
        this.itemRepository = Objects.requireNonNull(itemRepository);
        this.settingRepository = Objects.requireNonNull(settingRepository);
        this.idempotencyExecutor = Objects.requireNonNull(idempotencyExecutor);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.membershipRepository = Objects.requireNonNull(membershipRepository);
        this.auditRepository = Objects.requireNonNull(auditRepository);
        this.eventRepository = Objects.requireNonNull(eventRepository);
    }

    /**
     * 매장 비활성화 판단에 사용할 활성 팀 수를 조회한다.
     *
     * @param operatorId 인증된 매장 운영자 계정 ID
     * @param storeId 조회할 매장 ID
     * @return 종결 영향에 포함되는 활성 팀 수
     */
    @Transactional(readOnly = true)
    public WaitingActiveTeamImpact inspectActiveTeams(long operatorId, long storeId) {
        authorityPort.requireRead(operatorId, storeId);
        return new WaitingActiveTeamImpact(
                storeId, teamRepository.countByStoreIdAndStatusIn(storeId, ACTIVE));
    }

    /**
     * 현재 설정 version에 결박된 활성 팀 종결 작업을 멱등 생성한다.
     *
     * @param operatorId 인증된 매장 운영자 계정 ID
     * @param storeId 종결 대상 매장 ID
     * @param key 설정 변경과 공유하는 멱등 키
     * @param expectedSettingsVersion 비활성화로 확정된 설정 version
     * @return 202 closure job snapshot
     */
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

    /**
     * 현재 매장에 속한 종결 작업의 진행 상태를 조회한다.
     *
     * @param operatorId 인증된 매장 운영자 계정 ID
     * @param storeId 작업 소유 매장 ID
     * @param jobId 조회할 작업 ID
     * @return 종결 작업 snapshot
     */
    @Transactional(readOnly = true)
    public WaitingClosureJobSnapshot getClosureJob(long operatorId, long storeId, long jobId) {
        authorityPort.requireRead(operatorId, storeId);
        return jobRepository.findByIdAndStoreId(jobId, storeId)
                .map(WaitingClosureJobSnapshot::from)
                .orElseThrow(() -> new ServiceException(ReservationErrorCode.WAITING_CLOSE_JOB_NOT_FOUND));
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED,
            timeout = 5)
    List<WaitingClosureClaim> claimPendingItems(
            String owner, int limit, Duration leaseDuration, long afterItemId) {
        Instant now = clock.instant();
        List<WaitingClosureJobItem> items = itemRepository.findGloballyClaimableForUpdate(
                WaitingClosureItemStatus.PENDING.name(), WaitingClosureItemStatus.PROCESSING.name(),
                now, afterItemId, Math.min(limit, 100));
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
        if (!item.isOwnedBy(claim.owner(), claim.token(), now)) return false;
        WaitingClosureJob job = jobRepository.findByIdForUpdate(item.getWaitingClosureJobId())
                .orElseThrow(() -> new ServiceException(ReservationErrorCode.WAITING_CLOSE_JOB_NOT_FOUND));
        WaitingSetting setting = settingRepository.findByStoreIdForUpdate(job.getStoreId()).orElse(null);
        if (setting == null || setting.isEnabled()
                || setting.getVersion() != job.getSettingsVersion()) {
            item.complete(claim.owner(), claim.token(), now);
            reconcile(job, now);
            return true;
        }
        WaitingTeam team = teamRepository.findByIdForUpdate(item.getWaitingTeamId())
                .orElseThrow(() -> new ServiceException(ReservationErrorCode.WAITING_CLOSE_JOB_ITEM_FAILED));
        if (ACTIVE.contains(team.getStatus())) {
            WaitingTeamStatus before = team.getStatus();
            long expected = team.getVersion();
            team.closeByStore(expected, now);
            if (membershipRepository.deleteByWaitingTeamId(team.getId()) < 1L) {
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
        if (!item.isOwnedBy(claim.owner(), claim.token(), now)) return false;
        WaitingClosureJob job = jobRepository.findByIdForUpdate(item.getWaitingClosureJobId())
                .orElseThrow(() -> new ServiceException(ReservationErrorCode.WAITING_CLOSE_JOB_NOT_FOUND));
        if (retryable && item.getAttemptCount() < MAX_ITEM_ATTEMPTS) {
            item.requeue(claim.owner(), claim.token(), now);
        }
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
