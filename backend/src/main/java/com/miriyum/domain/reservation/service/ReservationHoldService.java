package com.miriyum.domain.reservation.service;

import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.consumer.dto.contract.ReservationContactResult;
import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.dto.ReservationHoldContracts;
import com.miriyum.domain.reservation.dto.request.ReservationTimeRequest;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationHold;
import com.miriyum.domain.reservation.entity.ReservationHoldCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.entity.ReservationHoldTransitionAudit;
import com.miriyum.domain.reservation.entity.ReservationHoldWarningTask;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.port.ReservationTemporaryMenuHoldPort;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldCommand;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldSelection;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldTransitionAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldWarningTaskRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.dto.contract.StoreReservationTransactionEligibility;
import com.miriyum.domain.store.service.StoreTransactionEligibilityService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 겹치는 전체 서비스 구간의 수용량만 10분 동안 원자적으로 선점한다. */
@Service
public class ReservationHoldService {

    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final String CREATION_AUDIT_COMMAND_PREFIX = "reservation-hold-create:";

    private final ReservationHoldRepository holdRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationHoldCapacityAllocationRepository allocationRepository;
    private final ReservationHoldTransitionAuditRepository auditRepository;
    private final ReservationHoldWarningTaskRepository warningTaskRepository;
    private final ReservationCapacityBucketRepository capacityBucketRepository;
    private final ReservationTemporaryMenuHoldPort temporaryMenuHoldPort;
    private final ConsumerAccountService consumerAccountService;
    private final StoreTransactionEligibilityService storeEligibilityService;
    private final ReservationTimeResolutionService timeResolutionService;
    private final ReservationCancellationPolicySelector cancellationPolicySelector;
    private final Clock clock;
    private final Supplier<UUID> auditCommandIdSupplier;
    private final ReservationCreationCapacityValidator capacityValidator =
            new ReservationCreationCapacityValidator();

    @Autowired
    public ReservationHoldService(
            ReservationHoldRepository holdRepository,
            ReservationRepository reservationRepository,
            ReservationHoldCapacityAllocationRepository allocationRepository,
            ReservationHoldTransitionAuditRepository auditRepository,
            ReservationHoldWarningTaskRepository warningTaskRepository,
            ReservationCapacityBucketRepository capacityBucketRepository,
            ReservationTemporaryMenuHoldPort temporaryMenuHoldPort,
            ConsumerAccountService consumerAccountService,
            StoreTransactionEligibilityService storeEligibilityService,
            ReservationTimeResolutionService timeResolutionService,
            ReservationCancellationPolicySelector cancellationPolicySelector,
            Clock clock
    ) {
        this(
                holdRepository,
                reservationRepository,
                allocationRepository,
                auditRepository,
                warningTaskRepository,
                capacityBucketRepository,
                temporaryMenuHoldPort,
                consumerAccountService,
                storeEligibilityService,
                timeResolutionService,
                cancellationPolicySelector,
                clock,
                UUID::randomUUID
        );
    }

    ReservationHoldService(
            ReservationHoldRepository holdRepository,
            ReservationRepository reservationRepository,
            ReservationHoldCapacityAllocationRepository allocationRepository,
            ReservationHoldTransitionAuditRepository auditRepository,
            ReservationHoldWarningTaskRepository warningTaskRepository,
            ReservationCapacityBucketRepository capacityBucketRepository,
            ReservationTemporaryMenuHoldPort temporaryMenuHoldPort,
            ConsumerAccountService consumerAccountService,
            StoreTransactionEligibilityService storeEligibilityService,
            ReservationTimeResolutionService timeResolutionService,
            ReservationCancellationPolicySelector cancellationPolicySelector,
            Clock clock,
            Supplier<UUID> auditCommandIdSupplier
    ) {
        this.holdRepository = holdRepository;
        this.reservationRepository = reservationRepository;
        this.allocationRepository = allocationRepository;
        this.auditRepository = auditRepository;
        this.warningTaskRepository = warningTaskRepository;
        this.capacityBucketRepository = capacityBucketRepository;
        this.temporaryMenuHoldPort = temporaryMenuHoldPort;
        this.consumerAccountService = consumerAccountService;
        this.storeEligibilityService = storeEligibilityService;
        this.timeResolutionService = timeResolutionService;
        this.cancellationPolicySelector = cancellationPolicySelector;
        this.clock = clock;
        this.auditCommandIdSupplier = auditCommandIdSupplier;
    }

    /**
     * 사용자 입력 의미를 먼저 replay 판정하고, fresh 요청만 Store와 aggregate, 버킷 순으로 잠근다.
     *
     * @param command 인증된 소비자의 수용량 선점 생성 명령
     * @return Entity를 노출하지 않는 현재 선점 결과
     * @throws IllegalArgumentException 명령 구조가 유효하지 않은 경우
     * @throws ServiceException 멱등 키 재사용, 중복 거래 또는 수용량·정책 검증이 실패한 경우
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ReservationHoldContracts.Result create(
            ReservationHoldContracts.CreateCommand command
    ) {
        NormalizedCommand normalized = normalize(command);
        ReservationHold replay = holdRepository
                .findByConsumerAccountIdAndCreationCommandId(
                        normalized.consumerAccountId(),
                        normalized.creationCommandId())
                .orElse(null);
        if (replay != null) {
            if (!sameUserControlledMeaning(replay, normalized)) {
                throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            verifyMenuCreationReplay(replay, normalized.menuSelections());
            return resultOf(replay);
        }

        ReservationContactResult contact = consumerAccountService.getReservationContact(
                normalized.consumerAccountId());
        requireContactable(contact);
        StoreReservationTransactionEligibility store =
                storeEligibilityService.requireReservationTransactionEligibility(
                        normalized.storeId());
        ReservationHold concurrentReplay = holdRepository
                .findByConsumerAccountIdAndCreationCommandId(
                        normalized.consumerAccountId(),
                        normalized.creationCommandId())
                .orElse(null);
        if (concurrentReplay != null) {
            if (!sameUserControlledMeaning(concurrentReplay, normalized)) {
                throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            verifyMenuCreationReplay(concurrentReplay, normalized.menuSelections());
            return resultOf(concurrentReplay);
        }
        ReservationTimeSnapshot timeSnapshot = timeResolutionService.resolveCreationTime(
                normalized.storeId(),
                new ReservationTimeRequest(
                        normalized.serviceDate(),
                        normalized.startTime(),
                        normalized.startOffset()));

        List<Reservation> confirmedReservations =
                reservationRepository.findConfirmedOverlappingForUpdate(
                normalized.consumerAccountId(),
                normalized.storeId(),
                timeSnapshot.getStartAt(),
                timeSnapshot.getServiceEndAt());
        List<ReservationHold> protectedHolds = holdRepository.findProtectedOverlappingForUpdate(
                normalized.consumerAccountId(),
                normalized.storeId(),
                timeSnapshot.getStartAt(),
                timeSnapshot.getServiceEndAt());
        if (!confirmedReservations.isEmpty() || !protectedHolds.isEmpty()) {
            throw new ServiceException(ReservationErrorCode.DUPLICATE_RESERVATION);
        }

        ZoneId timeZone = ZoneId.of(timeSnapshot.getTimeZoneId());
        LocalTime occupancyEndTime = timeSnapshot.getOccupancyEndAt()
                .atZone(timeZone)
                .toLocalTime();
        List<ReservationCapacityBucket> buckets =
                capacityBucketRepository.findLatestPolicyBucketsOverlappingForUpdate(
                        normalized.storeId(),
                        normalized.serviceDate(),
                        normalized.startTime(),
                        occupancyEndTime);
        long capacityPolicyVersion = capacityValidator.validate(
                buckets,
                normalized.storeId(),
                normalized.serviceDate(),
                normalized.startTime(),
                occupancyEndTime,
                normalized.party().totalCount(),
                normalized.party().getInfantCount() > 0);
        for (ReservationCapacityBucket bucket : buckets) {
            bucket.occupy(normalized.party().totalCount());
        }

        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ReservationCancellationPolicyVersion cancellationPolicy =
                requireCancellationPolicy();
        ReservationHold hold = ReservationHold.active(
                normalized.consumerAccountId(),
                normalized.storeId(),
                store.storeName(),
                timeSnapshot,
                normalized.party(),
                ReservationContactSnapshot.contactable(
                        contact.notificationTargetReference()),
                capacityPolicyVersion,
                cancellationPolicy,
                normalized.creationCommandId(),
                createdAt);
        ReservationHold saved = holdRepository.saveAndFlush(hold);
        long holdId = requirePersistedId(saved);

        List<ReservationHoldCapacityAllocation> allocations = buckets.stream()
                .map(bucket -> ReservationHoldCapacityAllocation.allocate(
                        holdId,
                        requireBucketId(bucket),
                        normalized.party().totalCount(),
                        capacityPolicyVersion))
                .toList();
        allocationRepository.saveAll(allocations);

        createTemporaryMenuHold(saved, normalized.menuSelections());

        String auditCommandId = CREATION_AUDIT_COMMAND_PREFIX
                + requireAuditCommandId();
        auditRepository.save(ReservationHoldTransitionAudit.record(
                holdId,
                SYSTEM_ACTOR,
                null,
                createdAt,
                createdAt,
                null,
                ReservationHoldStatus.ACTIVE,
                timeSnapshot.getReservationTimePolicyVersion(),
                capacityPolicyVersion,
                auditCommandId));
        warningTaskRepository.save(ReservationHoldWarningTask.schedule(
                holdId,
                saved.getCreatedAt(),
                saved.getExpiresAt()));
        return resultOf(saved);
    }

    /**
     * 검증된 목표 상태를 적용하고 반환 상태의 수용량을 정확히 한 번 복구한다.
     *
     * @param command 상위 서버 조정자가 발급한 전역 operation 명령
     * @return 현재 선점 결과
     * @throws IllegalArgumentException 명령 구조가 유효하지 않은 경우
     * @throws ServiceException replay 의미, Hold 상태 또는 수용량 스냅샷이 충돌한 경우
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ReservationHoldContracts.Result transition(
            ReservationHoldContracts.TransitionCommand command
    ) {
        NormalizedTransitionCommand normalized = normalizeTransition(command);
        ReservationHoldTransitionAudit replay = auditRepository
                .findByCommandId(normalized.operationId())
                .orElse(null);
        if (replay != null) {
            requireSameTransitionMeaning(replay, normalized);
            ReservationHold current = holdRepository
                    .findById(normalized.reservationHoldId())
                    .orElseThrow(ReservationHoldService::holdNotFound);
            return resultOf(current);
        }

        ReservationHold hold = holdRepository
                .findByIdForUpdate(normalized.reservationHoldId())
                .orElseThrow(ReservationHoldService::holdNotFound);
        ReservationHoldTransitionAudit concurrentReplay = auditRepository
                .findByCommandId(normalized.operationId())
                .orElse(null);
        if (concurrentReplay != null) {
            requireSameTransitionMeaning(concurrentReplay, normalized);
            return resultOf(hold);
        }
        Instant occurredAt = clock.instant();
        ReservationHoldStatus beforeStatus = hold.getStatus();
        if (normalized.targetStatus().requiresCapacityRelease()) {
            validateCapacityReleasingTransition(
                    hold, normalized.targetStatus(), occurredAt);
            restoreCapacity(hold);
        }
        applyTransition(hold, normalized.targetStatus(), occurredAt);
        auditRepository.save(ReservationHoldTransitionAudit.record(
                normalized.reservationHoldId(),
                normalized.actorType(),
                normalized.actorId(),
                normalized.requestedAt(),
                occurredAt,
                beforeStatus,
                normalized.targetStatus(),
                hold.getReservationTimePolicyVersion(),
                hold.getCapacityPolicyVersion(),
                normalized.operationId()));
        holdRepository.flush();
        return resultOf(hold);
    }

    private static void requireSameTransitionMeaning(
            ReservationHoldTransitionAudit replay,
            NormalizedTransitionCommand command
    ) {
        if (!command.operationId().equals(replay.getCommandId())
                || replay.getBeforeStatus() == null
                || replay.getReservationHoldId() != command.reservationHoldId()
                || replay.getAfterStatus() != command.targetStatus()
                || !command.actorType().equals(replay.getActorType())
                || !Objects.equals(command.actorId(), replay.getActorId())
                || !command.requestedAt().equals(replay.getRequestedAt())) {
            throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
    }

    private void restoreCapacity(ReservationHold hold) {
        long holdId = requirePersistedId(hold);
        int partySize = hold.getParty().totalCount();
        List<ReservationHoldCapacityAllocation> allocations = allocationRepository
                .findAllByReservationHoldIdOrderByCapacityBucketIdAsc(holdId);
        if (allocations == null || allocations.isEmpty()) {
            throw capacityConfigurationConflict();
        }

        Map<Long, ReservationHoldCapacityAllocation> allocationByBucketId =
                new LinkedHashMap<>();
        long previousBucketId = 0L;
        for (ReservationHoldCapacityAllocation allocation : allocations) {
            if (allocation == null
                    || allocation.getReservationHoldId() == null
                    || allocation.getReservationHoldId() != holdId
                    || allocation.getCapacityBucketId() == null
                    || allocation.getCapacityBucketId() <= previousBucketId
                    || allocation.getOccupiedPeople() != partySize
                    || allocation.getOccupiedTeams() != 1
                    || allocation.getCapacityPolicyVersion()
                    != hold.getCapacityPolicyVersion()) {
                throw capacityConfigurationConflict();
            }
            previousBucketId = allocation.getCapacityBucketId();
            allocationByBucketId.put(allocation.getCapacityBucketId(), allocation);
        }

        ZoneId timeZone = ZoneId.of(hold.getTimeZoneId());
        LocalTime localStart = hold.getStartAt().atZone(timeZone).toLocalTime();
        LocalTime localOccupancyEnd = hold.getOccupancyEndAt()
                .atZone(timeZone)
                .toLocalTime();
        long observedLatestVersion = capacityBucketRepository
                .findLatestPolicyVersion(hold.getStoreId(), hold.getServiceDate())
                .filter(version -> version > 0)
                .orElseThrow(ReservationHoldService::capacityConfigurationConflict);
        List<Long> latestBucketIds = capacityBucketRepository
                .findLatestPolicyBucketIdsOverlapping(
                        List.of(hold.getStoreId()),
                        hold.getServiceDate(),
                        localStart,
                        localOccupancyEnd);
        Set<Long> latestIdSet = validateLatestBucketIds(latestBucketIds);
        if (observedLatestVersion < hold.getCapacityPolicyVersion()
                || (observedLatestVersion == hold.getCapacityPolicyVersion()
                && !latestIdSet.equals(allocationByBucketId.keySet()))) {
            throw capacityConfigurationConflict();
        }
        Set<Long> unionIdSet = new TreeSet<>();
        unionIdSet.addAll(allocationByBucketId.keySet());
        unionIdSet.addAll(latestIdSet);
        List<Long> unionIds = List.copyOf(unionIdSet);
        List<ReservationCapacityBucket> lockedBuckets =
                capacityBucketRepository.findAllByIdInForUpdate(unionIds);
        Map<Long, ReservationCapacityBucket> bucketById = validateLockedUnion(
                hold,
                unionIds,
                lockedBuckets);
        long recheckedLatestVersion = capacityBucketRepository
                .findLatestPolicyVersion(hold.getStoreId(), hold.getServiceDate())
                .filter(version -> version > 0)
                .orElseThrow(ReservationHoldService::capacityConfigurationConflict);
        List<Long> recheckedLatestBucketIds = capacityBucketRepository
                .findLatestPolicyBucketIdsOverlapping(
                        List.of(hold.getStoreId()),
                        hold.getServiceDate(),
                        localStart,
                        localOccupancyEnd);
        if (recheckedLatestVersion != observedLatestVersion
                || !latestIdSet.equals(validateLatestBucketIds(
                        recheckedLatestBucketIds))) {
            throw capacityConfigurationConflict();
        }
        validateOriginalAllocations(
                allocationByBucketId,
                bucketById,
                localStart,
                localOccupancyEnd);
        validateLatestOverlaps(
                latestIdSet,
                bucketById,
                localStart,
                localOccupancyEnd,
                observedLatestVersion);

        validateRestorableOccupancy(
                unionIds,
                allocationByBucketId,
                bucketById,
                partySize);

        for (Long bucketId : unionIds) {
            ReservationHoldCapacityAllocation allocation = allocationByBucketId.get(bucketId);
            if (allocation == null) {
                bucketById.get(bucketId).restore(partySize, 1);
            } else {
                bucketById.get(bucketId).restore(
                        allocation.getOccupiedPeople(),
                        allocation.getOccupiedTeams());
            }
        }
    }

    private static Set<Long> validateLatestBucketIds(List<Long> latestBucketIds) {
        if (latestBucketIds == null) {
            throw capacityConfigurationConflict();
        }
        Set<Long> validated = new LinkedHashSet<>();
        for (Long bucketId : latestBucketIds) {
            if (bucketId == null || bucketId <= 0 || !validated.add(bucketId)) {
                throw capacityConfigurationConflict();
            }
        }
        return validated;
    }

    private static Map<Long, ReservationCapacityBucket> validateLockedUnion(
            ReservationHold hold,
            List<Long> unionIds,
            List<ReservationCapacityBucket> lockedBuckets
    ) {
        if (lockedBuckets == null || lockedBuckets.size() != unionIds.size()) {
            throw capacityConfigurationConflict();
        }
        Map<Long, ReservationCapacityBucket> bucketById = new LinkedHashMap<>();
        for (ReservationCapacityBucket bucket : lockedBuckets) {
            if (bucket == null
                    || bucket.getId() == null
                    || !unionIds.contains(bucket.getId())
                    || bucketById.put(bucket.getId(), bucket) != null
                    || !bucket.getStoreId().equals(hold.getStoreId())
                    || !bucket.getServiceDate().equals(hold.getServiceDate())) {
                throw capacityConfigurationConflict();
            }
        }
        if (!bucketById.keySet().equals(new LinkedHashSet<>(unionIds))) {
            throw capacityConfigurationConflict();
        }
        return bucketById;
    }

    private static void validateOriginalAllocations(
            Map<Long, ReservationHoldCapacityAllocation> allocationByBucketId,
            Map<Long, ReservationCapacityBucket> bucketById,
            LocalTime requestedStart,
            LocalTime requestedEnd
    ) {
        List<ReservationCapacityBucket> originalBuckets = new java.util.ArrayList<>();
        for (Map.Entry<Long, ReservationHoldCapacityAllocation> entry
                : allocationByBucketId.entrySet()) {
            ReservationCapacityBucket bucket = bucketById.get(entry.getKey());
            if (bucket == null
                    || bucket.getPolicyVersion()
                    != entry.getValue().getCapacityPolicyVersion()) {
                throw capacityConfigurationConflict();
            }
            originalBuckets.add(bucket);
        }
        validateContinuousCoverage(originalBuckets, requestedStart, requestedEnd);
    }

    private static void validateLatestOverlaps(
            Set<Long> latestBucketIds,
            Map<Long, ReservationCapacityBucket> bucketById,
            LocalTime requestedStart,
            LocalTime requestedEnd,
            long expectedPolicyVersion
    ) {
        List<ReservationCapacityBucket> latestBuckets = latestBucketIds.stream()
                .map(bucketId -> {
                    ReservationCapacityBucket bucket = bucketById.get(bucketId);
                    if (bucket == null) {
                        throw capacityConfigurationConflict();
                    }
                    return bucket;
                })
                .sorted(Comparator.comparing(ReservationCapacityBucket::getStartTime)
                        .thenComparing(ReservationCapacityBucket::getEndTime))
                .toList();
        LocalTime previousEnd = null;
        for (ReservationCapacityBucket bucket : latestBuckets) {
            LocalTime overlapStart = laterOf(bucket.getStartTime(), requestedStart);
            LocalTime overlapEnd = earlierOf(bucket.getEndTime(), requestedEnd);
            if (bucket.getPolicyVersion() != expectedPolicyVersion
                    || !overlapEnd.isAfter(overlapStart)
                    || (previousEnd != null && bucket.getStartTime().isBefore(previousEnd))) {
                throw capacityConfigurationConflict();
            }
            previousEnd = bucket.getEndTime();
        }
    }

    private static void validateRestorableOccupancy(
            List<Long> unionIds,
            Map<Long, ReservationHoldCapacityAllocation> allocationByBucketId,
            Map<Long, ReservationCapacityBucket> bucketById,
            int partySize
    ) {
        for (Long bucketId : unionIds) {
            ReservationHoldCapacityAllocation allocation = allocationByBucketId.get(bucketId);
            int occupiedPeople = allocation == null
                    ? partySize
                    : allocation.getOccupiedPeople();
            int occupiedTeams = allocation == null
                    ? 1
                    : allocation.getOccupiedTeams();
            ReservationCapacityBucket bucket = bucketById.get(bucketId);
            if (bucket.getOccupiedPeople() < occupiedPeople
                    || bucket.getOccupiedTeams() < occupiedTeams) {
                throw capacityConfigurationConflict();
            }
        }
    }

    private static void validateContinuousCoverage(
            List<ReservationCapacityBucket> buckets,
            LocalTime requestedStart,
            LocalTime requestedEnd
    ) {
        List<ReservationCapacityBucket> ordered = buckets.stream()
                .sorted(Comparator.comparing(ReservationCapacityBucket::getStartTime)
                        .thenComparing(ReservationCapacityBucket::getEndTime))
                .toList();
        LocalTime coveredUntil = requestedStart;
        for (ReservationCapacityBucket bucket : ordered) {
            LocalTime coveredStart = laterOf(bucket.getStartTime(), requestedStart);
            LocalTime coveredEnd = earlierOf(bucket.getEndTime(), requestedEnd);
            if (!coveredStart.equals(coveredUntil) || !coveredEnd.isAfter(coveredStart)) {
                throw capacityConfigurationConflict();
            }
            coveredUntil = coveredEnd;
        }
        if (!coveredUntil.equals(requestedEnd)) {
            throw capacityConfigurationConflict();
        }
    }

    private static void applyTransition(
            ReservationHold hold,
            ReservationHoldStatus targetStatus,
            Instant occurredAt
    ) {
        switch (targetStatus) {
            case CONFIRMED -> hold.confirm();
            case RELEASED -> hold.release();
            case EXPIRED -> hold.expire(occurredAt);
            case RECONCILIATION_REQUIRED -> hold.requireReconciliation();
            case ACTIVE -> throw new ServiceException(
                    ReservationErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private static void validateCapacityReleasingTransition(
            ReservationHold hold,
            ReservationHoldStatus targetStatus,
            Instant occurredAt
    ) {
        switch (targetStatus) {
            case RELEASED -> hold.validateRelease();
            case EXPIRED -> hold.validateExpiry(occurredAt);
            default -> throw new IllegalArgumentException(
                    "targetStatus must release capacity");
        }
    }

    private static LocalTime laterOf(LocalTime left, LocalTime right) {
        return left.isAfter(right) ? left : right;
    }

    private static LocalTime earlierOf(LocalTime left, LocalTime right) {
        return left.isBefore(right) ? left : right;
    }

    private static ServiceException capacityConfigurationConflict() {
        return new ServiceException(
                ReservationErrorCode.CAPACITY_CONFIGURATION_CONFLICT);
    }

    private static ServiceException holdNotFound() {
        return new ServiceException(ReservationErrorCode.RESERVATION_NOT_FOUND);
    }

    private static NormalizedCommand normalize(
            ReservationHoldContracts.CreateCommand command
    ) {
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
        if (command.consumerAccountId() <= 0 || command.storeId() <= 0) {
            throw new IllegalArgumentException("consumerAccountId and storeId must be positive");
        }
        if (command.serviceDate() == null || command.startTime() == null) {
            throw new IllegalArgumentException("serviceDate and startTime are required");
        }
        if (command.startTime().getSecond() != 0 || command.startTime().getNano() != 0) {
            throw new IllegalArgumentException("startTime must use minute precision");
        }
        PartyComposition party = PartyComposition.of(
                command.adultCount(),
                command.childCount(),
                command.infantCount());
        String creationCommandId = normalizeCreationCommandId(command.creationCommandId());
        return new NormalizedCommand(
                command.consumerAccountId(),
                command.storeId(),
                command.serviceDate(),
                command.startTime(),
                command.startOffset(),
                party,
                creationCommandId,
                command.menuSelections());
    }

    private void verifyMenuCreationReplay(
            ReservationHold hold,
            List<ReservationTemporaryMenuHoldSelection> selections
    ) {
        temporaryMenuHoldPort.verifyCreationReplay(
                new ReservationTemporaryMenuHoldCommand.Replay(
                        requirePersistedId(hold), selections));
    }

    private void createTemporaryMenuHold(
            ReservationHold hold,
            List<ReservationTemporaryMenuHoldSelection> selections
    ) {
        if (selections.isEmpty()) {
            return;
        }
        ZoneId timeZone = ZoneId.of(hold.getTimeZoneId());
        var localStart = hold.getStartAt().atZone(timeZone);
        var localEnd = hold.getServiceEndAt().atZone(timeZone);
        temporaryMenuHoldPort.create(new ReservationTemporaryMenuHoldCommand.Create(
                requirePersistedId(hold),
                hold.getStoreId(),
                hold.getConsumerAccountId(),
                localStart.toLocalDate(),
                localStart.toLocalTime(),
                localEnd.toLocalDate(),
                localEnd.toLocalTime(),
                hold.getStartAt(),
                hold.getServiceEndAt(),
                hold.getExpiresAt(),
                selections));
    }

    private static NormalizedTransitionCommand normalizeTransition(
            ReservationHoldContracts.TransitionCommand command
    ) {
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
        if (command.reservationHoldId() <= 0) {
            throw new IllegalArgumentException("reservationHoldId must be positive");
        }
        if (command.targetStatus() == null) {
            throw new IllegalArgumentException("targetStatus is required");
        }
        String operationId = normalizeText(command.operationId(), 100, "operationId");
        String actorType = normalizeText(command.actorType(), 32, "actorType");
        if (SYSTEM_ACTOR.equals(actorType)) {
            if (command.actorId() != null && command.actorId() <= 0) {
                throw new IllegalArgumentException("actorId must be positive when present");
            }
        } else if (command.actorId() == null || command.actorId() <= 0) {
            throw new IllegalArgumentException("non-system actorId must be positive");
        }
        if (command.requestedAt() == null) {
            throw new IllegalArgumentException("requestedAt is required");
        }
        return new NormalizedTransitionCommand(
                command.reservationHoldId(),
                command.targetStatus(),
                operationId,
                actorType,
                command.actorId(),
                command.requestedAt().truncatedTo(ChronoUnit.MICROS));
    }

    private static String normalizeText(String value, int maxLength, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(
                    fieldName + " must be 1 to " + maxLength + " characters");
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    fieldName + " must be 1 to " + maxLength + " characters");
        }
        return normalized;
    }

    private static String normalizeCreationCommandId(String value) {
        if (value == null) {
            throw new IllegalArgumentException("creationCommandId must be 1 to 100 characters");
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new IllegalArgumentException("creationCommandId must be 1 to 100 characters");
        }
        return normalized;
    }

    private static void requireContactable(ReservationContactResult contact) {
        if (contact == null
                || !contact.contactAvailable()
                || contact.notificationTargetReference() == null
                || contact.notificationTargetReference().isBlank()) {
            throw new ServiceException(AccountErrorCode.RESERVATION_CONTACT_REQUIRED);
        }
    }

    private ReservationCancellationPolicyVersion requireCancellationPolicy() {
        ReservationCancellationPolicyVersion selected = cancellationPolicySelector.select();
        if (selected == null) {
            throw new IllegalStateException("cancellation policy selection is required");
        }
        return selected;
    }

    private UUID requireAuditCommandId() {
        UUID generated = auditCommandIdSupplier.get();
        if (generated == null) {
            throw new IllegalStateException("audit command id is required");
        }
        return generated;
    }

    private static long requirePersistedId(ReservationHold hold) {
        if (hold == null || hold.getId() == null || hold.getId() <= 0) {
            throw new IllegalStateException("saved reservation hold id is required");
        }
        return hold.getId();
    }

    private static long requireBucketId(ReservationCapacityBucket bucket) {
        if (bucket.getId() == null || bucket.getId() <= 0) {
            throw new IllegalStateException("capacity bucket id is required");
        }
        return bucket.getId();
    }

    private static boolean sameUserControlledMeaning(
            ReservationHold hold,
            NormalizedCommand command
    ) {
        if (!command.creationCommandId().equals(hold.getCreationCommandId())
                || hold.getConsumerAccountId() != command.consumerAccountId()
                || hold.getStoreId() != command.storeId()
                || !hold.getServiceDate().equals(command.serviceDate())) {
            return false;
        }
        ZoneId timeZone = ZoneId.of(hold.getTimeZoneId());
        LocalTime storedLocalStart = hold.getStartAt().atZone(timeZone).toLocalTime();
        if (!storedLocalStart.equals(command.startTime())) {
            return false;
        }
        if (command.startOffset() != null
                && command.startOffset().getTotalSeconds()
                != hold.getTimeSnapshot().getStartOffsetSeconds()) {
            return false;
        }
        PartyComposition storedParty = hold.getParty();
        return storedParty.getAdultCount() == command.party().getAdultCount()
                && storedParty.getChildCount() == command.party().getChildCount()
                && storedParty.getInfantCount() == command.party().getInfantCount();
    }

    private static ReservationHoldContracts.Result resultOf(ReservationHold hold) {
        return new ReservationHoldContracts.Result(
                requirePersistedId(hold),
                hold.getStatus(),
                hold.getStatusVersion(),
                hold.getConsumerAccountId(),
                hold.getStoreId(),
                hold.getServiceDate(),
                hold.getStartAt(),
                hold.getServiceEndAt(),
                hold.getOccupancyEndAt(),
                hold.getTimeZoneId(),
                hold.getParty().getAdultCount(),
                hold.getParty().getChildCount(),
                hold.getParty().getInfantCount(),
                hold.getReservationTimePolicyVersion(),
                hold.getCapacityPolicyVersion(),
                hold.getCancellationPolicyVersion(),
                hold.getCreatedAt(),
                hold.getExpiresAt());
    }

    private record NormalizedCommand(
            long consumerAccountId,
            long storeId,
            LocalDate serviceDate,
            LocalTime startTime,
            ZoneOffset startOffset,
            PartyComposition party,
            String creationCommandId,
            List<ReservationTemporaryMenuHoldSelection> menuSelections
    ) {
    }

    private record NormalizedTransitionCommand(
            long reservationHoldId,
            ReservationHoldStatus targetStatus,
            String operationId,
            String actorType,
            Long actorId,
            Instant requestedAt
    ) {
    }
}
