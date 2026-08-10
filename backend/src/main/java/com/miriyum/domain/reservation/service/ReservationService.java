package com.miriyum.domain.reservation.service;

import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.consumer.dto.response.ReservationContactResult;
import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.menuhold.dto.MenuHoldCommandResult;
import com.miriyum.domain.menuhold.dto.MenuHoldCreateCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldItemResult;
import com.miriyum.domain.menuhold.dto.MenuHoldReleaseCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldTerminationPresence;
import com.miriyum.domain.menuhold.dto.MenuSelection;
import com.miriyum.domain.menuhold.service.MenuHoldService;
import com.miriyum.domain.menuhold.service.MenuHoldSnapshotQueryService;
import com.miriyum.domain.reservation.dto.request.ReservationAvailabilityCondition;
import com.miriyum.domain.reservation.dto.request.ReservationCreateRequest;
import com.miriyum.domain.reservation.dto.request.ReservationHistorySearchRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyDraftRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyPublicationCancellationRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyPublicationRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimeRequest;
import com.miriyum.domain.reservation.dto.request.StoreReservationSearchRequest;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;
import com.miriyum.domain.reservation.dto.response.ReservationHistoryPageResponse;
import com.miriyum.domain.reservation.dto.response.ReservationTimePolicyResponse;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionStatus;
import com.miriyum.domain.reservation.dto.response.ResolvedReservationTime;
import com.miriyum.domain.reservation.dto.response.StoreReservationPageResponse;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationActorType;
import com.miriyum.domain.reservation.entity.ReservationCancellationAudit;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyAudit;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationCancellationAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import com.miriyum.domain.store.core.dto.StoreReservationTransactionEligibility;
import com.miriyum.domain.store.core.service.StoreScheduledActivationDecision;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.core.service.StoreTransactionEligibilityService;
import com.miriyum.domain.store.schedule.dto.StoreReservationWindowResult;
import com.miriyum.domain.store.schedule.dto.StoreReservationWindowStatus;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalRequest;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalResult;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalStatus;
import com.miriyum.domain.store.schedule.service.StoreScheduleService;
import com.miriyum.domain.store.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.global.exception.CommonErrorCode;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
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
    private final ReservationRepository reservationRepository;
    private final ConsumerAccountService consumerAccountService;
    private final MenuHoldSnapshotQueryService menuHoldSnapshotQueryService;
    private StoreTransactionEligibilityService storeTransactionEligibilityService;
    private ReservationCapacityAllocationRepository capacityAllocationRepository;
    private ReservationCancellationPolicySelector cancellationPolicySelector;
    private MenuHoldService menuHoldService;
    private ReservationCancellationAuditRepository cancellationAuditRepository;
    private ReservationCancellationPolicyEvaluator cancellationPolicyEvaluator;

    ReservationService(
            StoreScheduleService storeScheduleService,
            StoreServiceIntervalValidationService storeServiceIntervalValidationService,
            ReservationTimePolicyVersionRepository timePolicyRepository,
            StoreService storeService,
            IdempotencyExecutor idempotencyExecutor,
            ReservationTimePolicyAuditRepository timePolicyAuditRepository,
            ObjectMapper objectMapper,
            Clock clock,
            ReservationCapacityBucketRepository capacityBucketRepository,
            ReservationRepository reservationRepository,
            ConsumerAccountService consumerAccountService,
            MenuHoldSnapshotQueryService menuHoldSnapshotQueryService
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
        this.reservationRepository = reservationRepository;
        this.consumerAccountService = consumerAccountService;
        this.menuHoldSnapshotQueryService = menuHoldSnapshotQueryService;
    }

    /** Spring constructor including the public contracts used by creation and cancellation. */
    @Autowired
    public ReservationService(
            StoreScheduleService storeScheduleService,
            StoreServiceIntervalValidationService storeServiceIntervalValidationService,
            ReservationTimePolicyVersionRepository timePolicyRepository,
            StoreService storeService,
            IdempotencyExecutor idempotencyExecutor,
            ReservationTimePolicyAuditRepository timePolicyAuditRepository,
            ObjectMapper objectMapper,
            Clock clock,
            ReservationCapacityBucketRepository capacityBucketRepository,
            ReservationRepository reservationRepository,
            ConsumerAccountService consumerAccountService,
            MenuHoldSnapshotQueryService menuHoldSnapshotQueryService,
            StoreTransactionEligibilityService storeTransactionEligibilityService,
            ReservationCapacityAllocationRepository capacityAllocationRepository,
            ReservationCancellationPolicySelector cancellationPolicySelector,
            MenuHoldService menuHoldService,
            ReservationCancellationAuditRepository cancellationAuditRepository,
            ReservationCancellationPolicyEvaluator cancellationPolicyEvaluator
    ) {
        this(
                storeScheduleService,
                storeServiceIntervalValidationService,
                timePolicyRepository,
                storeService,
                idempotencyExecutor,
                timePolicyAuditRepository,
                objectMapper,
                clock,
                capacityBucketRepository,
                reservationRepository,
                consumerAccountService,
                menuHoldSnapshotQueryService
        );
        this.storeTransactionEligibilityService = storeTransactionEligibilityService;
        this.capacityAllocationRepository = capacityAllocationRepository;
        this.cancellationPolicySelector = cancellationPolicySelector;
        this.menuHoldService = menuHoldService;
        this.cancellationAuditRepository = cancellationAuditRepository;
        this.cancellationPolicyEvaluator = cancellationPolicyEvaluator;
    }

    /**
     * 인증된 일반 사용자의 일반 예약을 즉시 확정한다.
     * 현재 계정 상태는 replay에도 적용하고, 연락처는 최초 명령 실행에서만 확인한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ReservationCreationCommandResult createReservation(
            long consumerAccountId,
            IdempotencyKey key,
            ReservationCreateRequest request
    ) {
        NormalizedCreationRequest normalized = normalizeCreationRequest(
                consumerAccountId, key, request);
        consumerAccountService.requireActiveAccount(consumerAccountId);
        IdempotencyCommand command = new IdempotencyCommand(
                "consumer",
                consumerAccountId,
                "RESERVATION_CREATE",
                key.value(),
                fingerprintForCreation(normalized)
        );
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            ReservationContactResult contact =
                    consumerAccountService.getReservationContact(consumerAccountId);
            if (contact == null
                    || !contact.contactAvailable()
                    || contact.notificationTargetReference() == null
                    || contact.notificationTargetReference().isBlank()) {
                throw new ServiceException(AccountErrorCode.RESERVATION_CONTACT_REQUIRED);
            }
            return createReservationWork(
                    consumerAccountId,
                    key,
                    normalized,
                    contact
            );
        });
        ReservationDetailResponse response = reservationCreationResponse(outcome.data());
        return new ReservationCreationCommandResult(
                outcome.httpStatus(), response);
    }

    private ReservationDetailResponse reservationCreationResponse(JsonNode payload) {
        ReservationDetailResponse deserialized = objectMapper.treeToValue(
                payload, ReservationDetailResponse.class);
        return new ReservationDetailResponse(
                deserialized.reservationId(),
                deserialized.storeId(),
                deserialized.storeName(),
                deserialized.serviceDate(),
                deserialized.timeStatus(),
                storedOffsetDateTime(payload, "startAt"),
                storedOffsetDateTime(payload, "serviceEndAt"),
                deserialized.timeZoneId(),
                deserialized.party(),
                deserialized.status(),
                deserialized.menuSelections(),
                storedOffsetDateTime(payload, "createdAt")
        );
    }

    private static OffsetDateTime storedOffsetDateTime(JsonNode payload, String fieldName) {
        JsonNode value = payload.get(fieldName);
        return value == null || value.isNull()
                ? null
                : OffsetDateTime.parse(value.asString());
    }

    private BusinessResult<ReservationDetailResponse> createReservationWork(
            long consumerAccountId,
            IdempotencyKey key,
            NormalizedCreationRequest request,
            ReservationContactResult contact
    ) {
        StoreReservationTransactionEligibility store =
                requireCreationDependencies().requireReservationTransactionEligibility(
                        request.storeId());
        ReservationTimeSnapshot timeSnapshot = resolveCreationTime(request);

        if (!reservationRepository.findConfirmedOverlappingForUpdate(
                consumerAccountId,
                request.storeId(),
                timeSnapshot.getStartAt(),
                timeSnapshot.getServiceEndAt()).isEmpty()) {
            throw new ServiceException(ReservationErrorCode.DUPLICATE_RESERVATION);
        }

        ZoneId timeZone = ZoneId.of(timeSnapshot.getTimeZoneId());
        LocalTime occupancyEndTime = timeSnapshot.getOccupancyEndAt()
                .atZone(timeZone)
                .toLocalTime();
        List<ReservationCapacityBucket> buckets =
                capacityBucketRepository.findLatestPolicyBucketsOverlappingForUpdate(
                        request.storeId(),
                        request.serviceDate(),
                        request.startTime(),
                        occupancyEndTime
                );
        long capacityPolicyVersion = validateCreationCapacity(
                buckets,
                request,
                occupancyEndTime
        );
        for (ReservationCapacityBucket bucket : buckets) {
            bucket.occupy(request.partySize());
        }

        PartyComposition party = PartyComposition.of(
                request.adultCount(), request.childCount(), request.infantCount());
        Reservation reservation = Reservation.confirm(
                consumerAccountId,
                request.storeId(),
                store.storeName(),
                timeSnapshot,
                party,
                ReservationContactSnapshot.contactable(
                        contact.notificationTargetReference()),
                capacityPolicyVersion,
                requireCancellationPolicy(),
                clock.instant()
        );
        Reservation saved = reservationRepository.saveAndFlush(reservation);
        if (saved.getId() == null || saved.getId() <= 0) {
            throw new IllegalStateException("saved reservation id is required");
        }

        List<ReservationCapacityAllocation> allocations = buckets.stream()
                .map(bucket -> {
                    if (bucket.getId() == null || bucket.getId() <= 0) {
                        throw new IllegalStateException("capacity bucket id is required");
                    }
                    return ReservationCapacityAllocation.allocate(
                            saved.getId(),
                            bucket.getId(),
                            request.partySize(),
                            capacityPolicyVersion);
                })
                .toList();
        capacityAllocationRepository.saveAll(allocations);

        if (!request.menuSelections().isEmpty()) {
            ZonedDateTime localStart = timeSnapshot.getStartAt().atZone(timeZone);
            ZonedDateTime localEnd = timeSnapshot.getServiceEndAt().atZone(timeZone);
            MenuHoldCommandResult menuResult = menuHoldService.create(
                    new MenuHoldCreateCommand(
                            saved.getId(),
                            request.storeId(),
                            consumerAccountId,
                            localStart.toLocalDate(),
                            localStart.toLocalTime(),
                            localEnd.toLocalDate(),
                            localEnd.toLocalTime(),
                            timeSnapshot.getStartAt(),
                            timeSnapshot.getServiceEndAt(),
                            "reservation-create:" + saved.getId() + ":" + key.value(),
                            request.menuSelections()
                    ));
            if (menuResult == null
                    || menuResult.reservationId() != saved.getId()
                    || menuResult.outcome() != MenuHoldCommandResult.Outcome.CONFIRMED) {
                throw new IllegalStateException("menu hold creation result is inconsistent");
            }
        }

        ReservationDetailResponse response = ReservationDetailResponse.from(
                saved,
                request.menuSelections().isEmpty()
                        ? List.of()
                        : menuHoldSnapshotQueryService.findByReservationId(saved.getId())
        );
        return new BusinessResult<>(
                HttpStatus.CREATED.value(),
                SUCCESS_RESPONSE_CODE,
                "RESERVATION",
                String.valueOf(saved.getId()),
                response
        );
    }

    private StoreTransactionEligibilityService requireCreationDependencies() {
        if (storeTransactionEligibilityService == null
                || capacityAllocationRepository == null
                || cancellationPolicySelector == null
                || menuHoldService == null) {
            throw new IllegalStateException("reservation creation dependencies are required");
        }
        return storeTransactionEligibilityService;
    }

    private ReservationCancellationPolicyVersion requireCancellationPolicy() {
        ReservationCancellationPolicyVersion selected = cancellationPolicySelector.select();
        if (selected == null) {
            throw new IllegalStateException("cancellation policy selection is required");
        }
        return selected;
    }

    /**
     * 활성 소비자 본인의 예약과 연결 자원을 한 트랜잭션에서 취소·복구한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ReservationCancellationCommandResult cancelConsumerReservation(
            long consumerAccountId,
            long reservationId,
            IdempotencyCommand command,
            String reason,
            Instant requestedAt,
            String correlationId
    ) {
        requireCancellationArguments(
                "consumer", consumerAccountId, reservationId, command, reason, false,
                requestedAt, correlationId);
        consumerAccountService.requireActiveAccount(consumerAccountId);
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            Reservation reservation = reservationRepository
                    .findByIdAndConsumerAccountIdForUpdate(reservationId, consumerAccountId)
                    .orElseThrow(() -> new ServiceException(
                            ReservationErrorCode.RESERVATION_NOT_FOUND));
            return cancelReservationWork(
                    reservation,
                    ReservationCancellationActorType.CONSUMER,
                    consumerAccountId,
                    reason,
                    requestedAt,
                    correlationId
            );
        });
        return cancellationResult(outcome);
    }

    /**
     * 현재 관리 권한이 있는 운영자의 매장 예약과 연결 자원을 한 트랜잭션에서 취소·복구한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ReservationCancellationCommandResult cancelStoreReservation(
            long operatorAccountId,
            long storeId,
            long reservationId,
            IdempotencyCommand command,
            String reason,
            Instant requestedAt,
            String correlationId
    ) {
        requireCancellationArguments(
                "store-operator", operatorAccountId, reservationId, command, reason, true,
                requestedAt, correlationId);
        if (storeId <= 0) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        storeService.requireManagementOwnership(operatorAccountId, storeId);
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            Reservation reservation = reservationRepository
                    .findByIdAndStoreIdForUpdate(reservationId, storeId)
                    .orElseThrow(() -> new ServiceException(
                            ReservationErrorCode.RESERVATION_NOT_FOUND));
            return cancelReservationWork(
                    reservation,
                    ReservationCancellationActorType.STORE_OPERATOR,
                    operatorAccountId,
                    reason,
                    requestedAt,
                    correlationId
            );
        });
        return cancellationResult(outcome);
    }

    private BusinessResult<ReservationDetailResponse> cancelReservationWork(
            Reservation reservation,
            ReservationCancellationActorType actorType,
            long actorId,
            String reason,
            Instant requestedAt,
            String correlationId
    ) {
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION);
        }
        Long cancellationPolicyVersion = reservation.getCancellationPolicyVersion();
        Instant startAt = reservation.getStartAt();
        if (cancellationPolicyVersion == null || startAt == null) {
            throw new ServiceException(ReservationErrorCode.CANCELLATION_NOT_ALLOWED);
        }
        ReservationCancellationDecision decision = cancellationPolicyEvaluator.evaluate(
                cancellationPolicyVersion,
                actorType,
                reservation.getStatus(),
                startAt,
                requestedAt
        );
        if (decision == ReservationCancellationDecision.REJECTED_INVALID_STATE) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION);
        }
        if (decision == ReservationCancellationDecision.REJECTED_BY_POLICY) {
            throw new ServiceException(ReservationErrorCode.CANCELLATION_NOT_ALLOWED);
        }
        if (decision != ReservationCancellationDecision.ALLOWED) {
            throw new IllegalStateException("cancellation policy decision is required");
        }

        MenuHoldTerminationPresence menuHoldPresence =
                menuHoldService.lockForTermination(reservation.getId());
        if (menuHoldPresence == null) {
            throw new IllegalStateException("menu hold termination presence is required");
        }

        int partySize = reservation.getParty().totalCount();
        long capacityPolicyVersion = reservation.getCapacityPolicyVersion();
        List<ReservationCapacityAllocation> allocations = capacityAllocationRepository
                .findAllByReservationIdOrderByCapacityBucketIdAsc(reservation.getId());
        TreeSet<Long> originalBucketIds = validateOriginalAllocations(
                allocations,
                reservation.getId(),
                partySize,
                capacityPolicyVersion
        );

        CancellationCapacityWindow window = cancellationCapacityWindow(reservation);
        long latestPolicyVersion = capacityBucketRepository
                .findLatestPolicyVersion(reservation.getStoreId(), window.serviceDate())
                .filter(version -> version > 0)
                .orElseThrow(ReservationService::capacityPolicyChanged);
        if (latestPolicyVersion < capacityPolicyVersion) {
            throw capacityPolicyChanged();
        }
        List<Long> observedCurrentBucketIds =
                capacityBucketRepository.findLatestPolicyBucketIdsOverlapping(
                        List.of(reservation.getStoreId()),
                        window.serviceDate(),
                        window.startTime(),
                        window.occupancyEndTime()
                );
        TreeSet<Long> currentBucketIds =
                validateObservedCurrentBucketIds(observedCurrentBucketIds);
        if (latestPolicyVersion == capacityPolicyVersion
                && !currentBucketIds.equals(originalBucketIds)) {
            throw capacityPolicyChanged();
        }

        TreeSet<Long> unionBucketIds = new TreeSet<>(originalBucketIds);
        unionBucketIds.addAll(currentBucketIds);
        List<Long> orderedUnion = List.copyOf(unionBucketIds);
        List<ReservationCapacityBucket> lockedBuckets =
                capacityBucketRepository.findAllByIdInForUpdate(orderedUnion);
        Map<Long, ReservationCapacityBucket> lockedById = validateLockedBucketUnion(
                lockedBuckets,
                orderedUnion
        );
        long recheckedLatestVersion = capacityBucketRepository
                .findLatestPolicyVersion(reservation.getStoreId(), window.serviceDate())
                .orElseThrow(ReservationService::capacityPolicyChanged);
        if (recheckedLatestVersion != latestPolicyVersion) {
            throw capacityPolicyChanged();
        }
        validateLockedBucketSet(
                lockedById,
                originalBucketIds,
                reservation.getStoreId(),
                window,
                capacityPolicyVersion
        );
        if (latestPolicyVersion > capacityPolicyVersion) {
            validateLockedBucketSet(
                    lockedById,
                    currentBucketIds,
                    reservation.getStoreId(),
                    window,
                    latestPolicyVersion
            );
        }
        for (Long bucketId : orderedUnion) {
            lockedById.get(bucketId).restore(partySize, 1);
        }

        if (menuHoldPresence == MenuHoldTerminationPresence.HOLD_PRESENT) {
            MenuHoldCommandResult releaseResult = menuHoldService.release(
                    new MenuHoldReleaseCommand(reservation.getId(), correlationId));
            if (releaseResult == null
                    || releaseResult.reservationId() != reservation.getId()
                    || releaseResult.outcome() != MenuHoldCommandResult.Outcome.RELEASED) {
                throw new IllegalStateException("menu hold release result is inconsistent");
            }
        }

        Reservation managedReservation = reservationRepository.findById(reservation.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "managed reservation is required after resource release"));
        Instant occurredAt = clock.instant();
        managedReservation.cancel(occurredAt);
        ReservationCancellationAudit audit = ReservationCancellationAudit.recordSuccess(
                managedReservation.getId(), actorType, actorId, reason, requestedAt, occurredAt,
                ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED,
                cancellationPolicyVersion, capacityPolicyVersion, correlationId
        );
        cancellationAuditRepository.saveAndFlush(audit);
        List<MenuHoldItemResult> menuSnapshots =
                menuHoldPresence == MenuHoldTerminationPresence.HOLD_PRESENT
                        ? menuHoldSnapshotQueryService.findByReservationId(
                                managedReservation.getId())
                        : List.of();
        if (menuSnapshots == null) {
            throw new IllegalStateException("menu hold snapshots are required");
        }
        ReservationDetailResponse response = ReservationDetailResponse.from(
                managedReservation,
                menuSnapshots,
                audit.getActorType(),
                audit.getCancellationReason()
        );
        return new BusinessResult<>(
                HttpStatus.OK.value(),
                SUCCESS_RESPONSE_CODE,
                "RESERVATION",
                String.valueOf(managedReservation.getId()),
                response
        );
    }

    private static TreeSet<Long> validateOriginalAllocations(
            List<ReservationCapacityAllocation> allocations,
            long reservationId,
            int partySize,
            long capacityPolicyVersion
    ) {
        if (allocations == null || allocations.isEmpty()) {
            throw capacityPolicyChanged();
        }
        TreeSet<Long> bucketIds = new TreeSet<>();
        long previousBucketId = 0;
        for (ReservationCapacityAllocation allocation : allocations) {
            if (allocation == null
                    || allocation.getReservationId() == null
                    || allocation.getReservationId() != reservationId
                    || allocation.getCapacityBucketId() == null
                    || allocation.getCapacityBucketId() <= 0
                    || allocation.getCapacityBucketId() <= previousBucketId
                    || allocation.getOccupiedPeople() != partySize
                    || allocation.getOccupiedTeams() != 1
                    || allocation.getCapacityPolicyVersion() != capacityPolicyVersion
                    || !bucketIds.add(allocation.getCapacityBucketId())) {
                throw capacityPolicyChanged();
            }
            previousBucketId = allocation.getCapacityBucketId();
        }
        return bucketIds;
    }

    private static CancellationCapacityWindow cancellationCapacityWindow(
            Reservation reservation
    ) {
        if (reservation.getServiceDate() == null
                || reservation.getOccupancyEndAt() == null
                || reservation.getTimeZoneId() == null
                || reservation.getTimeZoneId().isBlank()) {
            throw capacityPolicyChanged();
        }
        try {
            ZoneId zone = ZoneId.of(reservation.getTimeZoneId());
            ZonedDateTime localStart = reservation.getStartAt().atZone(zone);
            ZonedDateTime localOccupancyEnd = reservation.getOccupancyEndAt().atZone(zone);
            if (!localStart.toLocalDate().equals(reservation.getServiceDate())
                    || !localOccupancyEnd.toLocalDate().equals(reservation.getServiceDate())
                    || !localStart.toLocalTime().isBefore(localOccupancyEnd.toLocalTime())) {
                throw capacityPolicyChanged();
            }
            return new CancellationCapacityWindow(
                    reservation.getServiceDate(),
                    localStart.toLocalTime(),
                    localOccupancyEnd.toLocalTime()
            );
        } catch (DateTimeException exception) {
            throw capacityPolicyChanged();
        }
    }

    private static TreeSet<Long> validateObservedCurrentBucketIds(List<Long> bucketIds) {
        if (bucketIds == null || bucketIds.isEmpty()) {
            throw capacityPolicyChanged();
        }
        TreeSet<Long> uniqueBucketIds = new TreeSet<>();
        for (Long bucketId : bucketIds) {
            if (bucketId == null || bucketId <= 0 || !uniqueBucketIds.add(bucketId)) {
                throw capacityPolicyChanged();
            }
        }
        return uniqueBucketIds;
    }

    private static Map<Long, ReservationCapacityBucket> validateLockedBucketUnion(
            List<ReservationCapacityBucket> lockedBuckets,
            List<Long> orderedUnion
    ) {
        if (lockedBuckets == null || lockedBuckets.size() != orderedUnion.size()) {
            throw capacityPolicyChanged();
        }
        Map<Long, ReservationCapacityBucket> lockedById = new HashMap<>();
        List<Long> returnedIds = new ArrayList<>(lockedBuckets.size());
        for (ReservationCapacityBucket bucket : lockedBuckets) {
            if (bucket == null || bucket.getId() == null || bucket.getId() <= 0
                    || lockedById.put(bucket.getId(), bucket) != null) {
                throw capacityPolicyChanged();
            }
            returnedIds.add(bucket.getId());
        }
        if (!returnedIds.equals(orderedUnion)) {
            throw capacityPolicyChanged();
        }
        return lockedById;
    }

    private static void validateLockedBucketSet(
            Map<Long, ReservationCapacityBucket> lockedById,
            Set<Long> expectedIds,
            long storeId,
            CancellationCapacityWindow window,
            long expectedVersion
    ) {
        List<ReservationCapacityBucket> buckets = expectedIds.stream()
                .map(lockedById::get)
                .toList();
        for (ReservationCapacityBucket bucket : buckets) {
            if (!matchesCancellationBucket(
                    bucket,
                    storeId,
                    window.serviceDate(),
                    expectedVersion
            )) {
                throw capacityPolicyChanged();
            }
        }
        validateCancellationCoverage(buckets, window);
    }

    private static boolean matchesCancellationBucket(
            ReservationCapacityBucket bucket,
            long storeId,
            LocalDate serviceDate,
            long expectedVersion
    ) {
        return bucket != null
                && bucket.getId() != null
                && bucket.getId() > 0
                && bucket.getStoreId() == storeId
                && serviceDate.equals(bucket.getServiceDate())
                && bucket.getPolicyVersion() == expectedVersion;
    }

    private static void validateCancellationCoverage(
            List<ReservationCapacityBucket> buckets,
            CancellationCapacityWindow window
    ) {
        List<ReservationCapacityBucket> byInterval = buckets.stream()
                .sorted(BUCKET_ORDER)
                .toList();
        LocalTime coveredUntil = window.startTime();
        for (ReservationCapacityBucket bucket : byInterval) {
            LocalTime coveredStart = laterOf(bucket.getStartTime(), window.startTime());
            LocalTime coveredEnd = earlierOf(
                    bucket.getEndTime(), window.occupancyEndTime());
            if (!coveredStart.equals(coveredUntil) || !coveredEnd.isAfter(coveredStart)) {
                throw capacityPolicyChanged();
            }
            coveredUntil = coveredEnd;
        }
        if (!coveredUntil.equals(window.occupancyEndTime())) {
            throw capacityPolicyChanged();
        }
    }

    private static void requireCancellationArguments(
            String namespace,
            long actorId,
            long reservationId,
            IdempotencyCommand command,
            String reason,
            boolean reasonRequired,
            Instant requestedAt,
            String correlationId
    ) {
        String expectedCorrelation = command == null
                ? null
                : "reservation-cancel:" + namespace + ":" + actorId + ":"
                        + command.idempotencyKey();
        if (actorId <= 0
                || command == null
                || !namespace.equals(command.principalNamespace())
                || actorId != command.principalId()
                || !"RESERVATION_CANCEL".equals(command.commandType())
                || requestedAt == null
                || correlationId == null
                || correlationId.length() > 90
                || !correlationId.equals(expectedCorrelation)
                || (reasonRequired && reason == null)
                || (reason != null && !hasValidCancellationReasonLength(reason))) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private static boolean hasValidCancellationReasonLength(String reason) {
        int length = reason.codePointCount(0, reason.length());
        return length >= 1 && length <= 500;
    }

    private ReservationCancellationCommandResult cancellationResult(
            IdempotentOutcome outcome
    ) {
        if (outcome == null || outcome.data() == null) {
            throw new IllegalStateException("cancellation idempotent outcome data is required");
        }
        ReservationDetailResponse deserialized = objectMapper.treeToValue(
                outcome.data(), ReservationDetailResponse.class);
        ReservationDetailResponse response = new ReservationDetailResponse(
                deserialized.reservationId(),
                deserialized.storeId(),
                deserialized.storeName(),
                deserialized.serviceDate(),
                deserialized.timeStatus(),
                storedOffsetDateTime(outcome.data(), "startAt"),
                storedOffsetDateTime(outcome.data(), "serviceEndAt"),
                deserialized.timeZoneId(),
                deserialized.party(),
                deserialized.status(),
                deserialized.menuSelections(),
                storedOffsetDateTime(outcome.data(), "createdAt"),
                deserialized.cancelledBy(),
                deserialized.cancellationReason()
        );
        return new ReservationCancellationCommandResult(outcome.httpStatus(), response);
    }

    private static ServiceException capacityPolicyChanged() {
        return new ServiceException(ReservationErrorCode.CAPACITY_POLICY_CHANGED);
    }

    private record CancellationCapacityWindow(
            LocalDate serviceDate,
            LocalTime startTime,
            LocalTime occupancyEndTime
    ) {
    }

    private ReservationTimeSnapshot resolveCreationTime(NormalizedCreationRequest request) {
        List<StoreReservationWindowResult> windows =
                storeScheduleService.resolveReservationWindows(
                        List.of(request.storeId()),
                        request.serviceDate(),
                        request.startTime());
        if (windows == null || windows.size() != 1) {
            throw outsideReservationWindow();
        }
        StoreReservationWindowResult window = windows.getFirst();
        if (window == null
                || window.storeId() != request.storeId()
                || window.status() != StoreReservationWindowStatus.ACCEPTING) {
            throw outsideReservationWindow();
        }

        Instant evaluatedAt = clock.instant();
        List<ReservationTimePolicyVersion> policies =
                timePolicyRepository.findResolutionCandidatesByStoreIds(
                        Set.of(request.storeId()),
                        ReservationTimePolicyStatus.ACTIVE,
                        ReservationTimePolicyStatus.SCHEDULED,
                        evaluatedAt);
        ReservationTimePolicyVersion policy = singleEffectivePolicy(
                policies,
                request.storeId(),
                evaluatedAt);
        LocalDateTime requestedAt = LocalDateTime.of(
                request.serviceDate(), request.startTime());
        if (policy == null || !isSlotAligned(window.windowStartAt(), requestedAt, policy)) {
            throw outsideReservationWindow();
        }

        ReservationTimeSnapshot snapshot;
        try {
            snapshot = ReservationTimeSnapshot.calculate(
                    policy,
                    requestedAt,
                    ZoneId.of(window.timeZoneId()),
                    request.startOffset());
        } catch (DateTimeException | IllegalArgumentException exception) {
            throw outsideReservationWindow();
        }
        if (!staysWithinCreationLocalBoundary(snapshot)) {
            throw outsideReservationWindow();
        }
        if (snapshot.getStartAt().isBefore(evaluatedAt)) {
            throw outsideReservationWindow();
        }

        StoreServiceIntervalRequest intervalRequest = new StoreServiceIntervalRequest(
                request.storeId(), snapshot.getStartAt(), snapshot.getServiceEndAt());
        List<StoreServiceIntervalResult> intervalResults =
                storeServiceIntervalValidationService.validateServiceIntervals(
                        List.of(intervalRequest));
        if (intervalResults == null || intervalResults.size() != 1) {
            throw outsideReservationWindow();
        }
        StoreServiceIntervalResult interval = intervalResults.getFirst();
        if (interval == null
                || interval.storeId() != intervalRequest.storeId()
                || !interval.startAt().equals(intervalRequest.startAt())
                || !interval.serviceEndAt().equals(intervalRequest.serviceEndAt())
                || interval.status() != StoreServiceIntervalStatus.ACCEPTING) {
            throw outsideReservationWindow();
        }
        return snapshot;
    }

    private static boolean staysWithinCreationLocalBoundary(ReservationTimeSnapshot snapshot) {
        ZoneId zone = ZoneId.of(snapshot.getTimeZoneId());
        ZonedDateTime start = snapshot.getStartAt().atZone(zone);
        ZonedDateTime serviceEnd = snapshot.getServiceEndAt().atZone(zone);
        ZonedDateTime occupancyEnd = snapshot.getOccupancyEndAt().atZone(zone);
        ZoneOffset requiredOffset = start.getOffset();
        return start.toLocalDate().equals(snapshot.getServiceDate())
                && serviceEnd.toLocalDate().equals(snapshot.getServiceDate())
                && occupancyEnd.toLocalDate().equals(snapshot.getServiceDate())
                && serviceEnd.getOffset().equals(requiredOffset)
                && occupancyEnd.getOffset().equals(requiredOffset);
    }

    private static long validateCreationCapacity(
            List<ReservationCapacityBucket> buckets,
            NormalizedCreationRequest request,
            LocalTime occupancyEndTime
    ) {
        if (buckets == null || buckets.isEmpty()) {
            throw new ServiceException(ReservationErrorCode.INSUFFICIENT_CAPACITY);
        }
        long version = buckets.getFirst().getPolicyVersion();
        for (ReservationCapacityBucket bucket : buckets) {
            if (bucket.getStoreId() != request.storeId()
                    || !bucket.getServiceDate().equals(request.serviceDate())
                    || bucket.getPolicyVersion() != version) {
                throw new ServiceException(ReservationErrorCode.CAPACITY_POLICY_CHANGED);
            }
            if (request.partySize() < bucket.getMinPartySize()
                    || request.partySize() > bucket.getMaxPartySize()
                    || (request.infantCount() > 0 && !bucket.isInfantsAllowed())) {
                throw new ServiceException(ReservationErrorCode.PARTY_SIZE_OUT_OF_RANGE);
            }
        }
        List<ReservationCapacityBucket> byInterval = buckets.stream()
                .sorted(BUCKET_ORDER)
                .toList();
        LocalTime coveredUntil = request.startTime();
        for (ReservationCapacityBucket bucket : byInterval) {
            LocalTime coveredStart = laterOf(bucket.getStartTime(), request.startTime());
            LocalTime coveredEnd = earlierOf(bucket.getEndTime(), occupancyEndTime);
            if (!coveredStart.equals(coveredUntil) || !coveredEnd.isAfter(coveredStart)) {
                throw new ServiceException(ReservationErrorCode.INSUFFICIENT_CAPACITY);
            }
            coveredUntil = coveredEnd;
        }
        if (coveredUntil.equals(occupancyEndTime)) {
            return version;
        }
        throw new ServiceException(ReservationErrorCode.INSUFFICIENT_CAPACITY);
    }

    private static NormalizedCreationRequest normalizeCreationRequest(
            long consumerAccountId,
            IdempotencyKey key,
            ReservationCreateRequest request
    ) {
        try {
            if (consumerAccountId <= 0 || key == null || request == null
                    || request.serviceDate() == null
                    || request.startTime() == null
                    || request.party() == null
                    || request.party().adultCount() == null
                    || request.party().childCount() == null
                    || request.party().infantCount() == null
                    || request.menuSelections() == null
                    || request.menuSelections().size() > 20) {
                throw new IllegalArgumentException("reservation creation fields are required");
            }
            if (request.startOffset() != null
                    && !request.startOffset().matches(
                            "^[+-](?:(?:0[0-9]|1[0-7]):[0-5][0-9]|18:00)$")) {
                throw new IllegalArgumentException("startOffset must use ±HH:MM");
            }
            long storeId = request.storeIdAsLong();
            int adultCount = request.party().adultCount();
            int childCount = request.party().childCount();
            int infantCount = request.party().infantCount();
            PartyComposition party = PartyComposition.of(
                    adultCount, childCount, infantCount);
            TreeMap<Long, Integer> quantitiesByMenuId = new TreeMap<>();
            for (var selection : request.menuSelections()) {
                if (selection == null || selection.quantity() == null
                        || selection.quantity() <= 0
                        || selection.quantity() > 100) {
                    throw new IllegalArgumentException("valid menu selection is required");
                }
                quantitiesByMenuId.merge(
                        selection.menuIdAsLong(),
                        selection.quantity(),
                        Math::addExact);
            }
            List<MenuSelection> menuSelections = quantitiesByMenuId.entrySet().stream()
                    .map(entry -> new MenuSelection(entry.getKey(), entry.getValue()))
                    .toList();
            return new NormalizedCreationRequest(
                    storeId,
                    request.serviceDate(),
                    request.startTime(),
                    request.startOffsetAsZoneOffset(),
                    adultCount,
                    childCount,
                    infantCount,
                    party.totalCount(),
                    menuSelections);
        } catch (ArithmeticException | DateTimeException | IllegalArgumentException exception) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private static String fingerprintForCreation(NormalizedCreationRequest request) {
        StringBuilder canonical = new StringBuilder("POST|/api/v1/reservations|");
        append(canonical, "storeId", Long.toString(request.storeId()));
        append(canonical, "serviceDate", request.serviceDate().toString());
        append(canonical, "startTime", request.startTime().toString());
        append(canonical, "startOffset", request.startOffset() == null
                ? "" : request.startOffset().toString());
        append(canonical, "adultCount", Integer.toString(request.adultCount()));
        append(canonical, "childCount", Integer.toString(request.childCount()));
        append(canonical, "infantCount", Integer.toString(request.infantCount()));
        for (MenuSelection selection : request.menuSelections()) {
            append(canonical, "menuId", Long.toString(selection.menuId()));
            append(canonical, "quantity", Integer.toString(selection.quantity()));
        }
        return RequestFingerprint.of(canonical.toString());
    }

    private static ServiceException outsideReservationWindow() {
        return new ServiceException(ReservationErrorCode.OUTSIDE_RESERVATION_WINDOW);
    }

    private record NormalizedCreationRequest(
            long storeId,
            java.time.LocalDate serviceDate,
            LocalTime startTime,
            ZoneOffset startOffset,
            int adultCount,
            int childCount,
            int infantCount,
            int partySize,
            List<MenuSelection> menuSelections
    ) {
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
     * @param condition 고객이 선택한 시작 시각·일행 조건
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
     * <p>결과는 입력 매장 순서를 그대로 보존한다. 매장별 시간 정책으로 계산한 점유 종료 중 가장
     * 늦은 시각까지 버킷을 한 번 조회한 뒤 각 매장의 실제 종료 시각으로 다시 필터링한다. 현재
     * 수용량 버킷의 현지 날짜·시각 계약으로 모호하게 표현되는 자정 넘김과 DST 중복 구간은 시각을
     * 추측하지 않고 실패 폐쇄한다. 이 조회 결과는 예약 생성 성공을 보장하지 않으며 생성
     * 트랜잭션에서 현재 정책과 점유량을 다시 검증해야 한다.</p>
     *
     * @param storeIds 판정 대상 매장 ID 목록
     * @param condition 모든 대상에 공통으로 적용할 시작 시각·일행 조건
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

        List<ReservationTimeResolutionResult> timeResults = resolveReservationTimes(
                candidateStoreIds,
                new ReservationTimeRequest(
                        condition.serviceDate(),
                        condition.startTime(),
                        condition.startOffset()
                )
        );
        if (timeResults == null || timeResults.size() != candidateStoreIds.size()) {
            return unavailableAvailabilityResults(candidateStoreIds);
        }

        CapacityWindow[] capacityWindows = new CapacityWindow[candidateStoreIds.size()];
        Set<Long> queryStoreIds = new LinkedHashSet<>();
        LocalTime latestEndTime = null;
        for (int index = 0; index < candidateStoreIds.size(); index++) {
            long storeId = candidateStoreIds.get(index);
            CapacityWindow capacityWindow = toCapacityWindow(
                    storeId,
                    timeResults.get(index),
                    condition
            );
            capacityWindows[index] = capacityWindow;
            if (capacityWindow != null) {
                queryStoreIds.add(storeId);
                latestEndTime = latestEndTime == null
                        ? capacityWindow.endTime()
                        : laterOf(latestEndTime, capacityWindow.endTime());
            }
        }
        if (queryStoreIds.isEmpty()) {
            return unavailableAvailabilityResults(candidateStoreIds);
        }

        List<ReservationCapacityBucket> buckets =
                capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                        List.copyOf(queryStoreIds),
                        condition.serviceDate(),
                        condition.startTime(),
                        latestEndTime
                );
        if (buckets == null) {
            return unavailableAvailabilityResults(candidateStoreIds);
        }
        Map<Long, List<ReservationCapacityBucket>> bucketsByStore =
                groupBucketsByStore(buckets);

        List<ReservationAvailabilityResult> results =
                new ArrayList<>(candidateStoreIds.size());
        for (int index = 0; index < candidateStoreIds.size(); index++) {
            long storeId = candidateStoreIds.get(index);
            CapacityWindow window = capacityWindows[index];
            ReservationAvailabilityStatus status = window == null
                    ? ReservationAvailabilityStatus.UNAVAILABLE
                    : availabilityOf(
                            bucketsByStore.get(storeId),
                            window.startTime(),
                            window.endTime(),
                            condition.partySize(),
                            condition.includesInfants()
                    );
            results.add(new ReservationAvailabilityResult(storeId, status));
        }
        return List.copyOf(results);
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

    /**
     * 소비자 본인의 예약 내역을 승인된 상태·페이지·정렬 조건으로 조회한다.
     *
     * @param consumerAccountId 인증된 소비자 계정 식별자
     * @param request 예약 도메인이 검증한 조회 조건
     * @return Entity가 노출되지 않는 예약 내역 페이지
     * @throws ServiceException 계정 식별자 또는 조회 조건이 유효하지 않은 경우
     */
    @Transactional(readOnly = true)
    public ReservationHistoryPageResponse getConsumerReservationHistory(
            Long consumerAccountId,
            ReservationHistorySearchRequest request
    ) {
        validateRequest(consumerAccountId, request);
        consumerAccountService.getMe(consumerAccountId);

        Pageable pageable = PageRequest.of(
                request.page(),
                request.size(),
                deterministicSort(request.order())
        );
        Page<Reservation> reservations = request.status() == null
                ? reservationRepository.findAllByConsumerAccountId(
                        consumerAccountId,
                        pageable
                )
                : reservationRepository.findAllByConsumerAccountIdAndStatus(
                        consumerAccountId,
                        ReservationStatus.valueOf(request.status().name()),
                        pageable
                );

        return ReservationHistoryPageResponse.from(reservations);
    }

    /**
     * 활성 소비자 본인의 예약 상세와 예약 당시 메뉴 거래 스냅샷을 조회한다.
     *
     * @param consumerAccountId 인증된 소비자 계정 식별자
     * @param reservationId 대상 예약 식별자
     * @return 고객 공개 예약 상세
     * @throws ServiceException 계정이 유효하지 않거나 본인 범위에서 예약을 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public ReservationDetailResponse getConsumerReservation(
            Long consumerAccountId,
            Long reservationId
    ) {
        if (consumerAccountId == null || consumerAccountId <= 0) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        consumerAccountService.getMe(consumerAccountId);
        Reservation reservation = reservationRepository.findByIdAndConsumerAccountId(
                        reservationId,
                        consumerAccountId
                )
                .orElseThrow(() ->
                        new ServiceException(ReservationErrorCode.RESERVATION_NOT_FOUND));
        List<MenuHoldItemResult> menuSnapshots =
                menuHoldSnapshotQueryService.findByReservationId(reservation.getId());
        Optional<ReservationCancellationAudit> cancellationAudit =
                findCancellationAudit(reservation.getId());
        return ReservationDetailResponse.from(
                reservation,
                menuSnapshots,
                cancellationAudit.map(ReservationCancellationAudit::getActorType).orElse(null),
                cancellationAudit.map(ReservationCancellationAudit::getCancellationReason)
                        .orElse(null)
        );
    }

    /**
     * 매장 관리 권한을 확인한 운영자에게 대상 매장 범위의 예약 상세를 반환한다.
     *
     * @param operatorAccountId 인증된 매장 운영자 계정 식별자
     * @param storeId 대상 매장 식별자
     * @param reservationId 대상 예약 식별자
     * @return 운영자 공개 예약 상세
     * @throws ServiceException 운영자 계정이 유효하지 않거나 관리 권한 또는 예약이 없는 경우
     */
    @Transactional(readOnly = true)
    public ReservationDetailResponse getStoreReservation(
            Long operatorAccountId,
            Long storeId,
            Long reservationId
    ) {
        if (operatorAccountId == null || operatorAccountId <= 0) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        storeService.requireManagementOwnership(operatorAccountId, storeId);
        Reservation reservation = reservationRepository.findByIdAndStoreId(
                        reservationId,
                        storeId
                )
                .orElseThrow(() ->
                        new ServiceException(ReservationErrorCode.RESERVATION_NOT_FOUND));
        List<MenuHoldItemResult> menuSnapshots =
                menuHoldSnapshotQueryService.findByReservationId(reservation.getId());
        Optional<ReservationCancellationAudit> cancellationAudit =
                findCancellationAudit(reservation.getId());
        return ReservationDetailResponse.from(
                reservation,
                menuSnapshots,
                cancellationAudit.map(ReservationCancellationAudit::getActorType).orElse(null),
                cancellationAudit.map(ReservationCancellationAudit::getCancellationReason)
                        .orElse(null)
        );
    }

    private Optional<ReservationCancellationAudit> findCancellationAudit(Long reservationId) {
        if (cancellationAuditRepository == null) {
            return Optional.empty();
        }
        return cancellationAuditRepository.findByReservationId(reservationId);
    }

    /**
     * 인증된 운영자가 관리하는 매장의 예약 목록을 승인된 조건으로 조회한다.
     *
     * @param operatorAccountId 인증된 매장 운영자 계정 식별자
     * @param storeId 대상 매장 식별자
     * @param request 날짜·상태·페이지·정렬 조회 조건
     * @return 대상 매장 범위의 예약 목록 페이지
     * @throws ServiceException 입력이 유효하지 않거나 대상 매장 관리 권한이 없는 경우
     */
    @Transactional(readOnly = true)
    public StoreReservationPageResponse getStoreReservations(
            Long operatorAccountId,
            Long storeId,
            StoreReservationSearchRequest request
    ) {
        validateStoreRequest(operatorAccountId, storeId, request);
        storeService.requireManagementOwnership(operatorAccountId, storeId);

        Pageable pageable = PageRequest.of(
                request.page(),
                request.size(),
                Sort.by(request.order().sortOrders())
        );
        ReservationStatus status = request.status() == null
                ? null
                : ReservationStatus.valueOf(request.status().name());
        Page<Reservation> reservations = findStoreReservations(
                storeId,
                request,
                status,
                pageable
        );

        return StoreReservationPageResponse.from(reservations);
    }

    private Page<Reservation> findStoreReservations(
            Long storeId,
            StoreReservationSearchRequest request,
            ReservationStatus status,
            Pageable pageable
    ) {
        if (request.serviceDate() != null && status != null) {
            return reservationRepository.findAllByStoreIdAndTimeSnapshotServiceDateAndStatus(
                    storeId,
                    request.serviceDate(),
                    status,
                    pageable
            );
        }
        if (request.serviceDate() != null) {
            return reservationRepository.findAllByStoreIdAndTimeSnapshotServiceDate(
                    storeId,
                    request.serviceDate(),
                    pageable
            );
        }
        if (status != null) {
            return reservationRepository.findAllByStoreIdAndStatus(
                    storeId,
                    status,
                    pageable
            );
        }
        return reservationRepository.findAllByStoreId(storeId, pageable);
    }

    private void validateRequest(
            Long consumerAccountId,
            ReservationHistorySearchRequest request
    ) {
        if (consumerAccountId == null || consumerAccountId <= 0 || request == null) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private void validateStoreRequest(
            Long operatorAccountId,
            Long storeId,
            StoreReservationSearchRequest request
    ) {
        if (operatorAccountId == null || operatorAccountId <= 0
                || storeId == null || storeId <= 0
                || request == null) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private Sort deterministicSort(ReservationHistorySearchRequest.Order order) {
        String primaryProperty = switch (order) {
            case CREATED_AT_DESC, CREATED_AT_ASC -> "createdAt";
            case SERVICE_DATE_DESC, SERVICE_DATE_ASC -> "timeSnapshot.serviceDate";
        };
        Sort.Direction direction = switch (order) {
            case CREATED_AT_DESC, SERVICE_DATE_DESC -> Sort.Direction.DESC;
            case CREATED_AT_ASC, SERVICE_DATE_ASC -> Sort.Direction.ASC;
        };

        return Sort.by(
                new Sort.Order(direction, primaryProperty),
                new Sort.Order(direction, "id")
        );
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
            LocalTime startTime,
            LocalTime endTime,
            int partySize,
            boolean includesInfants
    ) {
        if (buckets == null || buckets.isEmpty()) {
            return ReservationAvailabilityStatus.UNAVAILABLE;
        }

        LocalTime cursor = startTime;
        Long policyVersion = null;
        for (ReservationCapacityBucket bucket : buckets) {
            if (!bucket.getStartTime().isBefore(endTime)
                    || !bucket.getEndTime().isAfter(startTime)) {
                continue;
            }
            if (policyVersion == null) {
                policyVersion = bucket.getPolicyVersion();
            } else if (bucket.getPolicyVersion() != policyVersion) {
                return ReservationAvailabilityStatus.UNAVAILABLE;
            }
            LocalTime coveredStart = laterOf(bucket.getStartTime(), startTime);
            LocalTime coveredEnd = earlierOf(bucket.getEndTime(), endTime);
            if (!coveredStart.equals(cursor) || !coveredEnd.isAfter(coveredStart)) {
                return ReservationAvailabilityStatus.UNAVAILABLE;
            }
            if (!bucket.canAccept(partySize, includesInfants)) {
                return ReservationAvailabilityStatus.UNAVAILABLE;
            }
            cursor = coveredEnd;
        }

        return policyVersion != null && cursor.equals(endTime)
                ? ReservationAvailabilityStatus.AVAILABLE
                : ReservationAvailabilityStatus.UNAVAILABLE;
    }

    private static CapacityWindow toCapacityWindow(
            long storeId,
            ReservationTimeResolutionResult result,
            ReservationAvailabilityCondition condition
    ) {
        if (result == null
                || result.storeId() != storeId
                || result.status() != ReservationTimeResolutionStatus.RESOLVED
                || result.time() == null) {
            return null;
        }
        ResolvedReservationTime time = result.time();
        if (time.policyStoreId() != storeId
                || !time.serviceDate().equals(condition.serviceDate())) {
            return null;
        }

        try {
            ZoneId zoneId = ZoneId.of(time.timeZoneId());
            var start = time.startAt().atZone(zoneId);
            var occupancyEnd = time.occupancyEndAt().atZone(zoneId);
            if (!start.toLocalDate().equals(condition.serviceDate())
                    || !occupancyEnd.toLocalDate().equals(condition.serviceDate())
                    || !start.toLocalTime().equals(condition.startTime())
                    || !start.toLocalTime().isBefore(occupancyEnd.toLocalTime())
                    || start.getSecond() != 0
                    || start.getNano() != 0
                    || occupancyEnd.getSecond() != 0
                    || occupancyEnd.getNano() != 0
                    || start.getOffset().getTotalSeconds() != time.startOffsetSeconds()
                    || occupancyEnd.getOffset().getTotalSeconds()
                    != time.occupancyEndOffsetSeconds()
                    || !start.getOffset().equals(occupancyEnd.getOffset())) {
                return null;
            }
            var startOffsets = zoneId.getRules().getValidOffsets(start.toLocalDateTime());
            var occupancyEndOffsets =
                    zoneId.getRules().getValidOffsets(occupancyEnd.toLocalDateTime());
            if (startOffsets.size() != 1
                    || occupancyEndOffsets.size() != 1
                    || !startOffsets.getFirst().equals(start.getOffset())
                    || !occupancyEndOffsets.getFirst().equals(occupancyEnd.getOffset())) {
                return null;
            }
            return new CapacityWindow(
                    start.toLocalTime(),
                    occupancyEnd.toLocalTime()
            );
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private static List<ReservationAvailabilityResult> unavailableAvailabilityResults(
            List<Long> storeIds
    ) {
        return storeIds.stream()
                .map(storeId -> new ReservationAvailabilityResult(
                        storeId,
                        ReservationAvailabilityStatus.UNAVAILABLE
                ))
                .toList();
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

    private record CapacityWindow(
            LocalTime startTime,
            LocalTime endTime
    ) {
    }
}
