package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.request.ReservationAvailabilityCondition;
import com.miriyum.domain.reservation.dto.request.ReservationTimeRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyDraftRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyPublicationCancellationRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyPublicationRequest;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionStatus;
import com.miriyum.domain.reservation.dto.response.ReservationTimePolicyResponse;
import com.miriyum.domain.reservation.dto.response.ResolvedReservationTime;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyAudit;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.core.service.StoreScheduledActivationDecision;
import com.miriyum.domain.store.schedule.dto.StoreReservationWindowResult;
import com.miriyum.domain.store.schedule.dto.StoreReservationWindowStatus;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalRequest;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalResult;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalStatus;
import com.miriyum.domain.store.schedule.service.StoreScheduleService;
import com.miriyum.domain.store.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 일반 예약 유스케이스와 Reservation 소유 시간 정책 계산을 조정하는 주 Service다.
 */
@Service
public class ReservationService {

    private static final String PRINCIPAL_NAMESPACE = "store-operator";
    private static final String SUCCESS_RESPONSE_CODE = "SUCCESS";
    private static final String TIME_POLICY_RESOURCE_TYPE = "RESERVATION_TIME_POLICY";
    private static final Comparator<ReservationCapacityBucket> BUCKET_ORDER =
            Comparator.comparing(ReservationCapacityBucket::getStartTime)
                    .thenComparing(ReservationCapacityBucket::getEndTime)
                    .thenComparingLong(ReservationCapacityBucket::getPolicyVersion);

    private final StoreScheduleService storeScheduleService;
    private final StoreServiceIntervalValidationService storeServiceIntervalValidationService;
    private final ReservationTimePolicyVersionRepository timePolicyRepository;
    private final StoreService storeService;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ReservationTimePolicyAuditRepository timePolicyAuditRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ReservationCapacityBucketRepository capacityBucketRepository;

    public ReservationService(
            StoreScheduleService storeScheduleService,
            StoreServiceIntervalValidationService storeServiceIntervalValidationService,
            ReservationTimePolicyVersionRepository timePolicyRepository,
            StoreService storeService,
            IdempotencyExecutor idempotencyExecutor,
            ReservationTimePolicyAuditRepository timePolicyAuditRepository,
            ObjectMapper objectMapper,
            Clock clock,
            ReservationCapacityBucketRepository capacityBucketRepository
    ) {
        this.storeScheduleService = storeScheduleService;
        this.storeServiceIntervalValidationService = storeServiceIntervalValidationService;
        this.timePolicyRepository = timePolicyRepository;
        this.storeService = storeService;
        this.idempotencyExecutor = idempotencyExecutor;
        this.timePolicyAuditRepository = timePolicyAuditRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.capacityBucketRepository = capacityBucketRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ReservationTimePolicyCommandResult<ReservationTimePolicyResponse>
            createTimePolicyDraft(
                    long operatorId,
                    long storeId,
                    IdempotencyKey key,
                    ReservationTimePolicyDraftRequest request
    ) {
        requireCommandArguments(key, request);
        storeService.requireManagementOwnership(operatorId, storeId);
        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorId,
                "RESERVATION_TIME_POLICY_DRAFT_CREATE",
                key.value(),
                fingerprintForDraft(storeId, request)
        );
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            storeService.requireSchedulePublicationAuthority(operatorId, storeId);
            long nextVersion = Math.addExact(
                    timePolicyRepository.findMaxVersionNumberByStoreId(storeId),
                    1L
            );
            ReservationTimePolicyVersion policy =
                    ReservationTimePolicyVersion.createDraft(
                            storeId,
                            nextVersion,
                            request.slotInterval(),
                            request.serviceDuration(),
                            request.turnoverDuration()
                    );
            ReservationTimePolicyVersion saved = timePolicyRepository.saveAndFlush(policy);
            Instant now = clock.instant();
            timePolicyAuditRepository.save(
                    ReservationTimePolicyAudit.operatorCommand(
                            storeId,
                            operatorId,
                            saved.getVersionNumber(),
                            null,
                            null,
                            null,
                            ReservationTimePolicyStatus.DRAFT,
                            now,
                            null,
                            now,
                            null,
                            key.value()
                    )
            );
            ReservationTimePolicyResponse response =
                    ReservationTimePolicyResponse.from(saved);
            return success(resourceId(storeId, saved.getVersionNumber()), response);
        });
        return commandResult(outcome);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ReservationTimePolicyCommandResult<ReservationTimePolicyResponse>
            publishTimePolicy(
                    long operatorId,
                    long storeId,
                    long version,
                    IdempotencyKey key,
                    ReservationTimePolicyPublicationRequest request
    ) {
        requireCommandArguments(key, request);
        storeService.requireManagementOwnership(operatorId, storeId);
        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorId,
                "RESERVATION_TIME_POLICY_PUBLISH",
                key.value(),
                fingerprintForPublication(storeId, version, request)
        );
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            storeService.requireSchedulePublicationAuthority(operatorId, storeId);
            ReservationTimePolicyVersion target = loadTargetForUpdate(storeId, version);
            requireStatus(target, ReservationTimePolicyStatus.DRAFT);
            Optional<ReservationTimePolicyVersion> active =
                    timePolicyRepository.findByStoreIdAndStatusForUpdate(
                            storeId,
                            ReservationTimePolicyStatus.ACTIVE
                    );
            Instant now = clock.instant();
            ReservationTimePolicyStatus before = target.getStatus();
            Long previousActiveVersion = active
                    .map(ReservationTimePolicyVersion::getVersionNumber)
                    .orElse(null);
            Long newActiveVersion = previousActiveVersion;
            try {
                if (request.publicationMode()
                        == ReservationTimePolicyPublicationRequest.PublicationMode.IMMEDIATE) {
                    if (active.isPresent()) {
                        active.get().retire();
                        timePolicyRepository.flush();
                    }
                    target.activate(now, request.changeReason());
                    newActiveVersion = target.getVersionNumber();
                } else {
                    target.schedule(
                            request.effectiveAt().toInstant(),
                            now,
                            request.changeReason()
                    );
                }
            } catch (IllegalArgumentException | ServiceException exception) {
                throw timePolicyConflict(exception);
            }
            timePolicyAuditRepository.save(
                    ReservationTimePolicyAudit.operatorCommand(
                            storeId,
                            operatorId,
                            target.getVersionNumber(),
                            previousActiveVersion,
                            newActiveVersion,
                            before,
                            target.getStatus(),
                            now,
                            target.getEffectiveAt(),
                            now,
                            request.changeReason(),
                            key.value()
                    )
            );
            ReservationTimePolicyResponse response =
                    ReservationTimePolicyResponse.from(target);
            return success(resourceId(storeId, target.getVersionNumber()), response);
        });
        return commandResult(outcome);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ReservationTimePolicyCommandResult<ReservationTimePolicyResponse>
            cancelTimePolicyPublication(
                    long operatorId,
                    long storeId,
                    long version,
                    IdempotencyKey key,
                    ReservationTimePolicyPublicationCancellationRequest request
    ) {
        requireCommandArguments(key, request);
        storeService.requireManagementOwnership(operatorId, storeId);
        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorId,
                "RESERVATION_TIME_POLICY_PUBLICATION_CANCEL",
                key.value(),
                fingerprintForCancellation(storeId, version, request)
        );
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            storeService.requireSchedulePublicationAuthority(operatorId, storeId);
            ReservationTimePolicyVersion target = loadTargetForUpdate(storeId, version);
            requireStatus(target, ReservationTimePolicyStatus.SCHEDULED);
            Optional<ReservationTimePolicyVersion> active =
                    timePolicyRepository.findByStoreIdAndStatusForUpdate(
                            storeId,
                            ReservationTimePolicyStatus.ACTIVE
                    );
            Instant now = clock.instant();
            Instant scheduledAt = target.getEffectiveAt();
            Long activeVersion = active
                    .map(ReservationTimePolicyVersion::getVersionNumber)
                    .orElse(null);
            try {
                target.cancelPublication(now);
            } catch (IllegalArgumentException | ServiceException exception) {
                throw timePolicyConflict(exception);
            }
            timePolicyAuditRepository.save(
                    ReservationTimePolicyAudit.operatorCommand(
                            storeId,
                            operatorId,
                            target.getVersionNumber(),
                            activeVersion,
                            activeVersion,
                            ReservationTimePolicyStatus.SCHEDULED,
                            target.getStatus(),
                            now,
                            scheduledAt,
                            now,
                            request.changeReason(),
                            key.value()
                    )
            );
            ReservationTimePolicyResponse response =
                    ReservationTimePolicyResponse.from(target);
            return success(resourceId(storeId, target.getVersionNumber()), response);
        });
        return commandResult(outcome);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5
    )
    public boolean activateDueTimePolicy(long policyId) {
        if (policyId <= 0) {
            return false;
        }
        Long storeId = timePolicyRepository.findStoreIdById(policyId)
                .orElse(null);
        if (storeId == null) {
            return false;
        }

        StoreScheduledActivationDecision storeDecision =
                storeService.inspectScheduledActivation(storeId);
        ReservationTimePolicyVersion target =
                timePolicyRepository.findByIdForUpdate(policyId)
                        .orElse(null);
        Instant now = clock.instant();
        if (target == null
                || target.getStoreId().longValue() != storeId.longValue()
                || target.getStatus() != ReservationTimePolicyStatus.SCHEDULED
                || target.getEffectiveAt() == null
                || target.getEffectiveAt().isAfter(now)) {
            return false;
        }

        Optional<ReservationTimePolicyVersion> active =
                timePolicyRepository.findByStoreIdAndStatusForUpdate(
                        storeId,
                        ReservationTimePolicyStatus.ACTIVE
                );
        Long previousActiveVersion = active
                .map(ReservationTimePolicyVersion::getVersionNumber)
                .orElse(null);
        Instant effectiveAt = target.getEffectiveAt();
        Instant requestedAt = target.getPublicationRequestedAt() == null
                ? effectiveAt
                : target.getPublicationRequestedAt();
        String commandId = "activation:" + policyId + ":" + effectiveAt.toEpochMilli();

        if (!storeDecision.activationAllowed()) {
            target.failActivation();
            timePolicyAuditRepository.save(
                    ReservationTimePolicyAudit.systemActivation(
                            storeId,
                            target.getVersionNumber(),
                            previousActiveVersion,
                            previousActiveVersion,
                            ReservationTimePolicyStatus.SCHEDULED,
                            target.getStatus(),
                            requestedAt,
                            effectiveAt,
                            now,
                            target.getChangeReason(),
                            ReservationTimePolicyAudit.Outcome.ACTIVATION_FAILED,
                            commandId
                    )
            );
            return false;
        }

        if (active.isPresent()) {
            active.get().retire();
            timePolicyRepository.flush();
        }
        target.activate(now);
        timePolicyAuditRepository.save(
                ReservationTimePolicyAudit.systemActivation(
                        storeId,
                        target.getVersionNumber(),
                        previousActiveVersion,
                        target.getVersionNumber(),
                        ReservationTimePolicyStatus.SCHEDULED,
                        target.getStatus(),
                        requestedAt,
                        effectiveAt,
                        now,
                        target.getChangeReason(),
                        ReservationTimePolicyAudit.Outcome.SUCCEEDED,
                        commandId
                )
        );
        return true;
    }

    /**
     * 매장 한 곳의 현재 예약 가능 여부를 판정한다.
     *
     * @param storeId 대상 매장 ID
     * @param condition 서버가 확정한 날짜·점유 구간·일행 조건
     * @return 수용량 원장 기준 판정 결과
     */
    @Transactional(readOnly = true)
    public ReservationAvailabilityResult getAvailability(
            long storeId,
            ReservationAvailabilityCondition condition
    ) {
        requirePositiveStoreId(storeId);
        return getAvailabilities(List.of(storeId), condition).getFirst();
    }

    /**
     * 여러 매장의 현재 예약 가능 여부를 한 번의 버킷 조회로 판정한다.
     *
     * <p>결과는 입력 매장 순서를 그대로 보존한다. 이 조회 결과는 예약 생성 성공을 보장하지 않으며
     * 생성 트랜잭션에서 현재 정책과 점유량을 다시 검증해야 한다.</p>
     *
     * @param storeIds 판정 대상 매장 ID 목록
     * @param condition 모든 대상에 공통으로 적용할 날짜·점유 구간·일행 조건
     * @return 입력 매장과 같은 순서의 가용성 결과
     */
    @Transactional(readOnly = true)
    public List<ReservationAvailabilityResult> getAvailabilities(
            List<Long> storeIds,
            ReservationAvailabilityCondition condition
    ) {
        if (storeIds == null) {
            throw new IllegalArgumentException("storeIds must not be null");
        }
        if (condition == null) {
            throw new IllegalArgumentException("condition must not be null");
        }
        List<Long> candidateStoreIds = List.copyOf(storeIds);
        candidateStoreIds.forEach(ReservationService::requirePositiveStoreId);
        if (candidateStoreIds.isEmpty()) {
            return List.of();
        }

        List<ReservationCapacityBucket> buckets =
                capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                        candidateStoreIds,
                        condition.serviceDate(),
                        condition.startTime(),
                        condition.endTime()
                );
        Map<Long, List<ReservationCapacityBucket>> bucketsByStore =
                groupBucketsByStore(buckets);

        return candidateStoreIds.stream()
                .map(storeId -> new ReservationAvailabilityResult(
                        storeId,
                        availabilityOf(bucketsByStore.get(storeId), condition)
                ))
                .toList();
    }

    /**
     * Store의 시작 접수 window와 Reservation의 현재 시간 정책으로 매장별 실제 종료를 계산한다.
     *
     * <p>결과는 입력 순서·개수·중복을 보존한다. Store의 {@code windowEndAt}은 시작 접수
     * 상한일 뿐이므로 서비스 또는 점유 종료 계산에 사용하지 않는다. 계산된
     * {@code [startAt, serviceEndAt)}은 Store 일정 계약으로 검증하며 turnover 구간은 전달하지
     * 않는다. 이 결과만으로 최종 수용량 가용성이 확인되는 것은 아니다.</p>
     *
     * @param storeIds 계산 대상 매장 ID 목록
     * @param request 고객이 선택한 현지 시작 시각과 선택적 offset
     * @return 입력 매장과 같은 순서의 계산 또는 실패 폐쇄 결과
     */
    @Transactional(readOnly = true)
    public List<ReservationTimeResolutionResult> resolveReservationTimes(
            List<Long> storeIds,
            ReservationTimeRequest request
    ) {
        List<Long> candidates = validateRequest(storeIds, request);
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<StoreReservationWindowResult> windows =
                storeScheduleService.resolveReservationWindows(
                        candidates,
                        request.serviceDate(),
                        request.startTime()
                );
        if (!matchesInput(candidates, windows)) {
            return unavailableResults(candidates);
        }

        Set<Long> acceptingStoreIds = new LinkedHashSet<>();
        for (StoreReservationWindowResult window : windows) {
            if (window.status() == StoreReservationWindowStatus.ACCEPTING) {
                acceptingStoreIds.add(window.storeId());
            }
        }
        if (acceptingStoreIds.isEmpty()) {
            return unavailableResults(candidates);
        }

        Instant evaluatedAt = clock.instant();
        Map<Long, List<ReservationTimePolicyVersion>> policiesByStore = new HashMap<>();
        for (ReservationTimePolicyVersion policy :
                timePolicyRepository.findResolutionCandidatesByStoreIds(
                        acceptingStoreIds,
                        ReservationTimePolicyStatus.ACTIVE,
                        ReservationTimePolicyStatus.SCHEDULED,
                        evaluatedAt
                )) {
            policiesByStore.computeIfAbsent(policy.getStoreId(), ignored -> new ArrayList<>())
                    .add(policy);
        }

        LocalDateTime requestedAt = LocalDateTime.of(
                request.serviceDate(),
                request.startTime()
        );
        List<ReservationTimeResolutionResult> provisionalResults =
                new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            long storeId = candidates.get(index);
            StoreReservationWindowResult window = windows.get(index);
            ReservationTimePolicyVersion policy = singleEffectivePolicy(
                    policiesByStore.get(storeId),
                    storeId,
                    evaluatedAt
            );
            provisionalResults.add(resolveTime(
                    storeId,
                    window,
                    policy,
                    requestedAt,
                    request
            ));
        }

        List<StoreServiceIntervalRequest> intervalRequests = provisionalResults.stream()
                .filter(result -> result.status() == ReservationTimeResolutionStatus.RESOLVED)
                .map(result -> new StoreServiceIntervalRequest(
                        result.storeId(),
                        result.time().startAt(),
                        result.time().serviceEndAt()
                ))
                .toList();
        if (intervalRequests.isEmpty()) {
            return List.copyOf(provisionalResults);
        }

        List<StoreServiceIntervalResult> intervalResults =
                storeServiceIntervalValidationService.validateServiceIntervals(
                        intervalRequests
                );
        if (!matchesServiceIntervals(intervalRequests, intervalResults)) {
            return unavailableResults(candidates);
        }

        List<ReservationTimeResolutionResult> results =
                new ArrayList<>(candidates.size());
        int intervalIndex = 0;
        for (ReservationTimeResolutionResult provisionalResult : provisionalResults) {
            if (provisionalResult.status() != ReservationTimeResolutionStatus.RESOLVED) {
                results.add(provisionalResult);
                continue;
            }
            StoreServiceIntervalResult intervalResult = intervalResults.get(intervalIndex++);
            results.add(intervalResult.status() == StoreServiceIntervalStatus.ACCEPTING
                    ? provisionalResult
                    : ReservationTimeResolutionResult.unavailable(
                            provisionalResult.storeId()
                    ));
        }
        return List.copyOf(results);
    }

    private ReservationTimePolicyVersion loadTargetForUpdate(
            long storeId,
            long version
    ) {
        if (version <= 0) {
            throw new ServiceException(ReservationErrorCode.TIME_POLICY_CONFLICT);
        }
        return timePolicyRepository.findByStoreIdAndVersionNumberForUpdate(
                        storeId,
                        version
                )
                .orElseThrow(() ->
                        new ServiceException(ReservationErrorCode.TIME_POLICY_CONFLICT));
    }

    private static void requireStatus(
            ReservationTimePolicyVersion policy,
            ReservationTimePolicyStatus expected
    ) {
        if (policy.getStatus() != expected) {
            throw new ServiceException(ReservationErrorCode.TIME_POLICY_CONFLICT);
        }
    }

    private static void requireCommandArguments(IdempotencyKey key, Object request) {
        if (key == null || request == null) {
            throw new IllegalArgumentException("idempotency key and request are required");
        }
    }

    private static String fingerprintForDraft(
            long storeId,
            ReservationTimePolicyDraftRequest request
    ) {
        StringBuilder canonical = new StringBuilder(
                "PUT|/api/v1/store-operator/stores/{storeId}"
                        + "/reservation-time-policies|"
        );
        append(canonical, "storeId", Long.toString(storeId));
        append(canonical, "slotInterval", Integer.toString(request.slotInterval()));
        append(canonical, "serviceDuration", Integer.toString(request.serviceDuration()));
        append(canonical, "turnoverDuration", Integer.toString(request.turnoverDuration()));
        return RequestFingerprint.of(canonical.toString());
    }

    private static String fingerprintForPublication(
            long storeId,
            long version,
            ReservationTimePolicyPublicationRequest request
    ) {
        StringBuilder canonical = new StringBuilder(
                "POST|/api/v1/store-operator/stores/{storeId}"
                        + "/reservation-time-policies/{version}/publication|"
        );
        append(canonical, "storeId", Long.toString(storeId));
        append(canonical, "version", Long.toString(version));
        append(canonical, "publicationMode", request.publicationMode() == null
                ? ""
                : request.publicationMode().name());
        append(canonical, "effectiveAt", request.effectiveAt() == null
                ? ""
                : request.effectiveAt().toInstant().toString());
        append(canonical, "changeReason", request.changeReason() == null
                ? ""
                : request.changeReason());
        return RequestFingerprint.of(canonical.toString());
    }

    private static String fingerprintForCancellation(
            long storeId,
            long version,
            ReservationTimePolicyPublicationCancellationRequest request
    ) {
        StringBuilder canonical = new StringBuilder(
                "POST|/api/v1/store-operator/stores/{storeId}"
                        + "/reservation-time-policies/{version}"
                        + "/publication-cancellation|"
        );
        append(canonical, "storeId", Long.toString(storeId));
        append(canonical, "version", Long.toString(version));
        append(canonical, "changeReason", request.changeReason() == null
                ? ""
                : request.changeReason());
        return RequestFingerprint.of(canonical.toString());
    }

    private static void append(
            StringBuilder canonical,
            String fieldName,
            String value
    ) {
        canonical.append(fieldName)
                .append('=')
                .append(value.length())
                .append(':')
                .append(value)
                .append('|');
    }

    private static BusinessResult<ReservationTimePolicyResponse> success(
            String resourceId,
            ReservationTimePolicyResponse response
    ) {
        return new BusinessResult<>(
                HttpStatus.OK.value(),
                SUCCESS_RESPONSE_CODE,
                TIME_POLICY_RESOURCE_TYPE,
                resourceId,
                response
        );
    }

    private ReservationTimePolicyCommandResult<ReservationTimePolicyResponse>
            commandResult(IdempotentOutcome outcome) {
        ReservationTimePolicyResponse response = objectMapper.treeToValue(
                outcome.data(),
                ReservationTimePolicyResponse.class
        );
        return new ReservationTimePolicyCommandResult<>(
                outcome.httpStatus(),
                response
        );
    }

    private static String resourceId(long storeId, long version) {
        return storeId + ":" + version;
    }

    private static ServiceException timePolicyConflict(RuntimeException cause) {
        ServiceException conflict =
                new ServiceException(ReservationErrorCode.TIME_POLICY_CONFLICT);
        conflict.initCause(cause);
        return conflict;
    }

    private static Map<Long, List<ReservationCapacityBucket>> groupBucketsByStore(
            List<ReservationCapacityBucket> buckets
    ) {
        Map<Long, List<ReservationCapacityBucket>> bucketsByStore = new HashMap<>();
        for (ReservationCapacityBucket bucket : buckets) {
            bucketsByStore.computeIfAbsent(
                    bucket.getStoreId(),
                    ignored -> new ArrayList<>()
            ).add(bucket);
        }
        bucketsByStore.values().forEach(storeBuckets -> storeBuckets.sort(BUCKET_ORDER));
        return bucketsByStore;
    }

    private static ReservationAvailabilityStatus availabilityOf(
            List<ReservationCapacityBucket> buckets,
            ReservationAvailabilityCondition condition
    ) {
        if (buckets == null || buckets.isEmpty()) {
            return ReservationAvailabilityStatus.UNAVAILABLE;
        }

        LocalTime cursor = condition.startTime();
        long policyVersion = buckets.getFirst().getPolicyVersion();
        for (ReservationCapacityBucket bucket : buckets) {
            if (bucket.getPolicyVersion() != policyVersion) {
                return ReservationAvailabilityStatus.UNAVAILABLE;
            }
            LocalTime coveredStart = laterOf(bucket.getStartTime(), condition.startTime());
            LocalTime coveredEnd = earlierOf(bucket.getEndTime(), condition.endTime());
            if (!coveredStart.equals(cursor) || !coveredEnd.isAfter(coveredStart)) {
                return ReservationAvailabilityStatus.UNAVAILABLE;
            }
            if (!bucket.canAccept(condition.partySize(), condition.includesInfants())) {
                return ReservationAvailabilityStatus.UNAVAILABLE;
            }
            cursor = coveredEnd;
        }

        return cursor.equals(condition.endTime())
                ? ReservationAvailabilityStatus.AVAILABLE
                : ReservationAvailabilityStatus.UNAVAILABLE;
    }

    private static LocalTime laterOf(LocalTime first, LocalTime second) {
        return first.isAfter(second) ? first : second;
    }

    private static LocalTime earlierOf(LocalTime first, LocalTime second) {
        return first.isBefore(second) ? first : second;
    }

    private static void requirePositiveStoreId(long storeId) {
        if (storeId <= 0) {
            throw new IllegalArgumentException("storeId must be positive");
        }
    }

    private static List<Long> validateRequest(
            List<Long> storeIds,
            ReservationTimeRequest request
    ) {
        if (storeIds == null || request == null) {
            throw new IllegalArgumentException("storeIds and request are required");
        }
        if (storeIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("storeIds must be positive");
        }
        return List.copyOf(storeIds);
    }

    private static boolean matchesInput(
            List<Long> storeIds,
            List<StoreReservationWindowResult> windows
    ) {
        if (windows == null || windows.size() != storeIds.size()) {
            return false;
        }
        for (int index = 0; index < storeIds.size(); index++) {
            StoreReservationWindowResult window = windows.get(index);
            if (window == null || window.storeId() != storeIds.get(index)) {
                return false;
            }
        }
        return true;
    }

    private static ReservationTimePolicyVersion singleEffectivePolicy(
            List<ReservationTimePolicyVersion> policies,
            long storeId,
            Instant evaluatedAt
    ) {
        if (policies == null || policies.size() != 1) {
            return null;
        }
        ReservationTimePolicyVersion policy = policies.getFirst();
        if (policy.getStoreId() != storeId
                || policy.getStatus() != ReservationTimePolicyStatus.ACTIVE
                || policy.getEffectiveAt() == null
                || policy.getEffectiveAt().isAfter(evaluatedAt)) {
            return null;
        }
        return policy;
    }

    private static ReservationTimeResolutionResult resolveTime(
            long storeId,
            StoreReservationWindowResult window,
            ReservationTimePolicyVersion policy,
            LocalDateTime requestedAt,
            ReservationTimeRequest request
    ) {
        if (window.status() != StoreReservationWindowStatus.ACCEPTING
                || policy == null
                || !isSlotAligned(window.windowStartAt(), requestedAt, policy)) {
            return ReservationTimeResolutionResult.unavailable(storeId);
        }
        try {
            ReservationTimeSnapshot snapshot = ReservationTimeSnapshot.calculate(
                    policy,
                    requestedAt,
                    ZoneId.of(window.timeZoneId()),
                    request.startOffset()
            );
            return ReservationTimeResolutionResult.resolved(
                    storeId,
                    ResolvedReservationTime.from(snapshot)
            );
        } catch (DateTimeException | IllegalArgumentException exception) {
            return ReservationTimeResolutionResult.unavailable(storeId);
        }
    }

    private static boolean isSlotAligned(
            LocalDateTime windowStartAt,
            LocalDateTime requestedAt,
            ReservationTimePolicyVersion policy
    ) {
        if (windowStartAt == null || requestedAt.isBefore(windowStartAt)) {
            return false;
        }
        Duration elapsed = Duration.between(windowStartAt, requestedAt);
        long elapsedMinutes = elapsed.toMinutes();
        return elapsed.equals(Duration.ofMinutes(elapsedMinutes))
                && elapsedMinutes % policy.getSlotIntervalMinutes() == 0;
    }

    private static List<ReservationTimeResolutionResult> unavailableResults(
            List<Long> storeIds
    ) {
        return storeIds.stream()
                .map(ReservationTimeResolutionResult::unavailable)
                .toList();
    }

    private static boolean matchesServiceIntervals(
            List<StoreServiceIntervalRequest> requests,
            List<StoreServiceIntervalResult> results
    ) {
        if (results == null || requests.size() != results.size()) {
            return false;
        }
        for (int index = 0; index < requests.size(); index++) {
            StoreServiceIntervalRequest request = requests.get(index);
            StoreServiceIntervalResult result = results.get(index);
            if (result == null
                    || result.storeId() != request.storeId()
                    || !request.startAt().equals(result.startAt())
                    || !request.serviceEndAt().equals(result.serviceEndAt())
                    || result.status() == null) {
                return false;
            }
        }
        return true;
    }
}
