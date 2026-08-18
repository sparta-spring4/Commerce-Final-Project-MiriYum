package com.miriyum.domain.reservation.service;

import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.consumer.dto.contract.ReservationContactResult;
import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.menu.dto.contract.RepresentativeMenuSnapshot;
import com.miriyum.domain.menu.service.RepresentativeMenuQueryService;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareReservationDepositCommand;
import com.miriyum.domain.payment.service.PaymentService;
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
import com.miriyum.domain.reservation.dto.response.ReservationDepositDispositionResponse;
import com.miriyum.domain.reservation.dto.response.ReservationHistoryPageResponse;
import com.miriyum.domain.reservation.dto.response.ReservationRequestResponse;
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
import com.miriyum.domain.reservation.entity.ReservationFulfillmentActorType;
import com.miriyum.domain.reservation.entity.ReservationFulfillmentAudit;
import com.miriyum.domain.reservation.entity.ReservationDepositProcess;
import com.miriyum.domain.reservation.entity.ReservationDepositProcessStatus;
import com.miriyum.domain.reservation.entity.ReservationDepositDispositionObligation;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyAudit;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.notification.ReservationNotificationPublisher;
import com.miriyum.domain.reservation.port.ReservationMenuHoldPort;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldCreateCommand;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldItemSnapshot;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldResult;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldSelection;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldTerminationPresence;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldSelection;
import com.miriyum.domain.reservation.repository.ReservationCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationCancellationAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationFulfillmentAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositDispositionObligationRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import com.miriyum.domain.store.dto.contract.StoreReservationTransactionEligibility;
import com.miriyum.domain.store.dto.contract.StoreReservationDepositPolicy;
import com.miriyum.domain.store.service.StoreReservationDepositPolicyQueryService;
import com.miriyum.domain.store.service.StoreScheduledActivationDecision;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.domain.store.service.StoreTransactionEligibilityService;
import com.miriyum.domain.schedule.service.StoreScheduleService;
import com.miriyum.domain.schedule.service.StoreServiceIntervalValidationService;
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
import java.time.Instant;
import java.time.LocalDate;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
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
    private final ReservationMenuHoldPort menuHoldPort;
    private final ReservationTimeResolutionService timeResolutionService;
    private final ReservationCreationCapacityValidator creationCapacityValidator =
            new ReservationCreationCapacityValidator();
    private StoreTransactionEligibilityService storeTransactionEligibilityService;
    private ReservationCapacityAllocationRepository capacityAllocationRepository;
    private ReservationCancellationPolicySelector cancellationPolicySelector;
    private ReservationCancellationAuditRepository cancellationAuditRepository;
    private ReservationCancellationPolicyEvaluator cancellationPolicyEvaluator;
    private ReservationFulfillmentAuditRepository fulfillmentAuditRepository;
    private ReservationNotificationPublisher notificationPublisher;
    private StoreReservationDepositPolicyQueryService depositPolicyQueryService;
    private RepresentativeMenuQueryService representativeMenuQueryService;
    private ReservationDepositCalculator depositCalculator;
    private ReservationHoldCreationPrimitive holdCreationPrimitive;
    private PaymentService paymentService;
    private ReservationDepositProcessRepository depositProcessRepository;
    private ReservationDepositDispositionObligationRepository dispositionObligationRepository;

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
            ReservationMenuHoldPort menuHoldPort,
            ReservationTimeResolutionService timeResolutionService
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
        this.menuHoldPort = menuHoldPort;
        this.timeResolutionService = timeResolutionService;
    }

    /** Spring constructor including the public contracts used by creation and cancellation. */
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
            ReservationMenuHoldPort menuHoldPort,
            ReservationTimeResolutionService timeResolutionService,
            StoreTransactionEligibilityService storeTransactionEligibilityService,
            ReservationCapacityAllocationRepository capacityAllocationRepository,
            ReservationCancellationPolicySelector cancellationPolicySelector,
            ReservationCancellationAuditRepository cancellationAuditRepository,
            ReservationCancellationPolicyEvaluator cancellationPolicyEvaluator,
            ReservationFulfillmentAuditRepository fulfillmentAuditRepository
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
                menuHoldPort,
                timeResolutionService
        );
        this.storeTransactionEligibilityService = storeTransactionEligibilityService;
        this.capacityAllocationRepository = capacityAllocationRepository;
        this.cancellationPolicySelector = cancellationPolicySelector;
        this.cancellationAuditRepository = cancellationAuditRepository;
        this.cancellationPolicyEvaluator = cancellationPolicyEvaluator;
        this.fulfillmentAuditRepository = fulfillmentAuditRepository;
    }

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
            ReservationMenuHoldPort menuHoldPort,
            ReservationTimeResolutionService timeResolutionService,
            StoreTransactionEligibilityService storeTransactionEligibilityService,
            ReservationCapacityAllocationRepository capacityAllocationRepository,
            ReservationCancellationPolicySelector cancellationPolicySelector,
            ReservationCancellationAuditRepository cancellationAuditRepository,
            ReservationCancellationPolicyEvaluator cancellationPolicyEvaluator,
            ReservationFulfillmentAuditRepository fulfillmentAuditRepository,
            ReservationNotificationPublisher notificationPublisher,
            StoreReservationDepositPolicyQueryService depositPolicyQueryService,
            RepresentativeMenuQueryService representativeMenuQueryService,
            ReservationDepositCalculator depositCalculator,
            ReservationHoldCreationPrimitive holdCreationPrimitive,
            PaymentService paymentService,
            ReservationDepositProcessRepository depositProcessRepository,
            ReservationDepositDispositionObligationRepository dispositionObligationRepository
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
                menuHoldPort,
                timeResolutionService,
                storeTransactionEligibilityService,
                capacityAllocationRepository,
                cancellationPolicySelector,
                cancellationAuditRepository,
                cancellationPolicyEvaluator,
                fulfillmentAuditRepository
        );
        this.notificationPublisher = notificationPublisher;
        this.depositPolicyQueryService = depositPolicyQueryService;
        this.representativeMenuQueryService = representativeMenuQueryService;
        this.depositCalculator = depositCalculator;
        this.holdCreationPrimitive = holdCreationPrimitive;
        this.paymentService = paymentService;
        this.depositProcessRepository = depositProcessRepository;
        this.dispositionObligationRepository = dispositionObligationRepository;
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
        if (outcome.httpStatus() == HttpStatus.ACCEPTED.value()) {
            ReservationRequestResponse response = objectMapper.treeToValue(
                    outcome.data(), ReservationRequestResponse.class);
            return ReservationCreationCommandResult.depositRequested(response);
        }
        ReservationDetailResponse response = reservationCreationResponse(outcome.data());
        return new ReservationCreationCommandResult(outcome.httpStatus(), response);
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

    private BusinessResult<Object> createReservationWork(
            long consumerAccountId,
            IdempotencyKey key,
            NormalizedCreationRequest request,
            ReservationContactResult contact
    ) {
        StoreReservationTransactionEligibility store =
                requireCreationDependencies().requireReservationTransactionEligibility(
                        request.storeId());
        Optional<ReservationDepositCalculator.Calculation> depositCalculation =
                selectDepositCalculation(request.storeId(), request.partySize());
        if (depositCalculation.isPresent()) {
            return createDepositReservationWork(
                    consumerAccountId,
                    key,
                    request,
                    contact,
                    store,
                    depositCalculation.orElseThrow());
        }
        ReservationTimeSnapshot timeSnapshot = timeResolutionService.resolveCreationTime(
                request.storeId(),
                new ReservationTimeRequest(
                        request.serviceDate(),
                        request.startTime(),
                        request.startOffset()));

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
        long capacityPolicyVersion = creationCapacityValidator.validate(
                buckets,
                request.storeId(),
                request.serviceDate(),
                request.startTime(),
                occupancyEndTime,
                request.partySize(),
                request.infantCount() > 0
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
            ReservationMenuHoldResult menuResult = menuHoldPort.create(
                    new ReservationMenuHoldCreateCommand(
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
                    || menuResult.outcome() != ReservationMenuHoldResult.Outcome.CONFIRMED) {
                throw new IllegalStateException("menu hold creation result is inconsistent");
            }
        }

        ReservationDetailResponse response = ReservationDetailResponse.from(
                saved,
                request.menuSelections().isEmpty()
                        ? List.of()
                        : menuHoldPort.findSnapshots(saved.getId())
        );
        notificationPublisher.recordConfirmed(saved, saved.getCreatedAt(), key.value());
        return new BusinessResult<Object>(
                HttpStatus.CREATED.value(),
                SUCCESS_RESPONSE_CODE,
                "RESERVATION",
                String.valueOf(saved.getId()),
                response
        );
    }

    private BusinessResult<Object> createDepositReservationWork(
            long consumerAccountId,
            IdempotencyKey key,
            NormalizedCreationRequest request,
            ReservationContactResult contact,
            StoreReservationTransactionEligibility store,
            ReservationDepositCalculator.Calculation calculation
    ) {
        PartyComposition party = PartyComposition.of(
                request.adultCount(), request.childCount(), request.infantCount());
        List<ReservationTemporaryMenuHoldSelection> temporaryMenuSelections =
                request.menuSelections().stream()
                        .map(selection -> new ReservationTemporaryMenuHoldSelection(
                                selection.menuId(), selection.quantity()))
                        .toList();
        var hold = holdCreationPrimitive.createDeposit(
                new ReservationHoldCreationPrimitive.Command(
                        consumerAccountId,
                        store,
                        request.serviceDate(),
                        request.startTime(),
                        request.startOffset(),
                        party,
                        contact.notificationTargetReference(),
                        "reservation-deposit-create:" + key.value(),
                        temporaryMenuSelections));
        if (hold == null || hold.getId() == null || hold.getId() <= 0) {
            throw new IllegalStateException("saved reservation hold id is required");
        }
        PaymentPreparation preparation = paymentService.prepareReservationDeposit(
                new PrepareReservationDepositCommand(
                        String.valueOf(hold.getId()),
                        consumerAccountId,
                        calculation.amountMinor(),
                        calculation.currency(),
                        hold.getExpiresAt(),
                        calculation.algorithmVersion(),
                        key.value()));
        ReservationDepositProcess process = depositProcessRepository.saveAndFlush(
                ReservationDepositProcess.awaitingPayment(
                        hold.getId(),
                        consumerAccountId,
                        hold.getExpiresAt(),
                        calculation,
                        preparation,
                        clock.instant()));
        if (process == null || process.getId() == null || process.getId() <= 0) {
            throw new IllegalStateException("saved reservation deposit process id is required");
        }
        ReservationRequestResponse response = reservationRequestResponse(process);
        return new BusinessResult<Object>(
                HttpStatus.ACCEPTED.value(),
                SUCCESS_RESPONSE_CODE,
                "RESERVATION_DEPOSIT_PROCESS",
                String.valueOf(process.getId()),
                response);
    }

    private static ReservationRequestResponse reservationRequestResponse(
            ReservationDepositProcess process
    ) {
        return new ReservationRequestResponse(
                String.valueOf(process.getId()),
                process.getStatus(),
                process.getExpiresAt().atOffset(ZoneOffset.UTC),
                new ReservationRequestResponse.PaymentPreparationSnapshot(
                        process.getPaymentId(),
                        process.getPortOnePaymentId(),
                        process.getPaymentOrderName(),
                        process.getPaymentAmountMinor(),
                        process.getPaymentCurrency(),
                        process.getPaymentSourceExpiresAt().atOffset(ZoneOffset.UTC),
                        process.getPaymentPreparationStatus().name()),
                process.isAbandonmentRequested(),
                null);
    }

    private StoreTransactionEligibilityService requireCreationDependencies() {
        if (storeTransactionEligibilityService == null
                || capacityAllocationRepository == null
                || cancellationPolicySelector == null
                || menuHoldPort == null
                || notificationPublisher == null
                || depositPolicyQueryService == null
                || representativeMenuQueryService == null
                || depositCalculator == null
                || holdCreationPrimitive == null
                || paymentService == null
                || depositProcessRepository == null) {
            throw new IllegalStateException("reservation creation dependencies are required");
        }
        return storeTransactionEligibilityService;
    }

    private Optional<ReservationDepositCalculator.Calculation> selectDepositCalculation(
            long storeId,
            int partySize
    ) {
        StoreReservationDepositPolicy policy = depositPolicyQueryService.getCurrent(storeId);
        if (policy == null) {
            throw new IllegalStateException("store reservation deposit policy is required");
        }
        if (policy.status() != StoreReservationDepositPolicy.Status.ENABLED) {
            return Optional.empty();
        }
        RepresentativeMenuSnapshot representativeMenus =
                representativeMenuQueryService.getCurrent(storeId);
        return Optional.of(depositCalculator.calculate(
                policy, representativeMenus, partySize));
    }

    private ReservationCancellationPolicyVersion requireCancellationPolicy() {
        ReservationCancellationPolicyVersion selected = cancellationPolicySelector.select();
        if (selected == null) {
            throw new IllegalStateException("cancellation policy selection is required");
        }
        return selected;
    }

    /** 현재 대표 운영자가 자기 매장의 확정 예약을 방문 완료로 종결한다. */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ReservationFulfillmentCommandResult fulfillStoreReservation(
            long operatorAccountId,
            long storeId,
            long reservationId,
            IdempotencyCommand command,
            Instant requestedAt,
            String correlationId
    ) {
        requireFulfillmentArguments(
                operatorAccountId,
                storeId,
                reservationId,
                command,
                requestedAt,
                correlationId
        );
        requireFulfillmentDependencies();
        storeService.requireManagementOwnership(operatorAccountId, storeId);
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            Reservation reservation = reservationRepository
                    .findByIdAndStoreIdForUpdate(reservationId, storeId)
                    .orElseThrow(() -> new ServiceException(
                            ReservationErrorCode.RESERVATION_NOT_FOUND
                    ));
            return fulfillReservationWork(
                    reservation,
                    operatorAccountId,
                    requestedAt,
                    correlationId
            );
        });
        return fulfillmentResult(outcome);
    }

    private void requireFulfillmentDependencies() {
        if (menuHoldPort == null || fulfillmentAuditRepository == null) {
            throw new IllegalStateException("reservation fulfillment dependencies are required");
        }
    }

    private static void requireFulfillmentArguments(
            long operatorAccountId,
            long storeId,
            long reservationId,
            IdempotencyCommand command,
            Instant requestedAt,
            String correlationId
    ) {
        String normalizedKey = command == null ? null : command.idempotencyKey();
        String expectedCorrelation = normalizedKey == null
                ? null
                : "reservation-fulfill:store-operator:"
                        + operatorAccountId + ":" + normalizedKey;
        if (operatorAccountId <= 0
                || storeId <= 0
                || reservationId <= 0
                || command == null
                || !"store-operator".equals(command.principalNamespace())
                || operatorAccountId != command.principalId()
                || !"RESERVATION_FULFILL".equals(command.commandType())
                || requestedAt == null
                || correlationId == null
                || correlationId.length() > 91
                || !correlationId.equals(expectedCorrelation)) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private BusinessResult<ReservationDetailResponse> fulfillReservationWork(
            Reservation reservation,
            long operatorAccountId,
            Instant requestedAt,
            String correlationId
    ) {
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION);
        }
        DepositDispositionPlan dispositionPlan = fulfillmentDispositionPlan(
                reservation,
                correlationId
        );
        ReservationMenuHoldTerminationPresence presence =
                menuHoldPort.lockForTermination(reservation.getId());
        if (presence == null) {
            throw new IllegalStateException("menu hold termination presence is required");
        }

        Instant occurredAt = clock.instant();
        reservation.fulfill(occurredAt);
        if (presence == ReservationMenuHoldTerminationPresence.HOLD_PRESENT) {
            ReservationMenuHoldResult result = menuHoldPort.fulfill(
                    reservation.getId(), correlationId);
            if (result == null
                    || result.reservationId() != reservation.getId()
                    || result.outcome() != ReservationMenuHoldResult.Outcome.FULFILLED) {
                throw new IllegalStateException(
                        "menu hold fulfillment result is inconsistent"
                );
            }
        }

        ReservationFulfillmentAudit audit = ReservationFulfillmentAudit.recordSuccess(
                reservation.getId(),
                ReservationFulfillmentActorType.STORE_OPERATOR,
                operatorAccountId,
                requestedAt,
                occurredAt,
                ReservationStatus.CONFIRMED,
                ReservationStatus.FULFILLED,
                reservation.getTimeSnapshot().getReservationTimePolicyVersion(),
                reservation.getCapacityPolicyVersion(),
                correlationId
        );
        fulfillmentAuditRepository.saveAndFlush(audit);
        ReservationDepositDispositionResponse depositDisposition =
                persistDispositionObligation(dispositionPlan, occurredAt);
        List<ReservationMenuHoldItemSnapshot> menuSnapshots =
                presence == ReservationMenuHoldTerminationPresence.HOLD_PRESENT
                        ? menuHoldPort.findSnapshots(reservation.getId())
                        : List.of();
        ReservationDetailResponse response = ReservationDetailResponse.from(
                reservation,
                menuSnapshots,
                null,
                null,
                depositDisposition
        );
        return new BusinessResult<>(
                HttpStatus.OK.value(),
                SUCCESS_RESPONSE_CODE,
                "RESERVATION",
                String.valueOf(reservation.getId()),
                response
        );
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
                    correlationId,
                    command.idempotencyKey()
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
                    correlationId,
                    command.idempotencyKey()
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
            String correlationId,
            String cancellationIdempotencyKey
    ) {
        requireCancellationDependencies();
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
        DepositDispositionPlan dispositionPlan = depositDispositionPlan(
                reservation,
                actorType,
                cancellationPolicyVersion,
                requestedAt,
                correlationId,
                cancellationIdempotencyKey
        );

        ReservationMenuHoldTerminationPresence menuHoldPresence =
                menuHoldPort.lockForTermination(reservation.getId());
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
        validateLockedOriginalBucketSet(
                lockedById,
                originalBucketIds,
                reservation.getStoreId(),
                window,
                capacityPolicyVersion
        );
        if (latestPolicyVersion > capacityPolicyVersion) {
            validateLockedCurrentBucketSet(
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

        if (menuHoldPresence == ReservationMenuHoldTerminationPresence.HOLD_PRESENT) {
            ReservationMenuHoldResult releaseResult = menuHoldPort.release(
                    reservation.getId(), correlationId);
            if (releaseResult == null
                    || releaseResult.reservationId() != reservation.getId()
                    || releaseResult.outcome() != ReservationMenuHoldResult.Outcome.RELEASED) {
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
        List<ReservationMenuHoldItemSnapshot> menuSnapshots =
                menuHoldPresence == ReservationMenuHoldTerminationPresence.HOLD_PRESENT
                        ? menuHoldPort.findSnapshots(managedReservation.getId())
                        : List.of();
        if (menuSnapshots == null) {
            throw new IllegalStateException("menu hold snapshots are required");
        }
        ReservationDepositDispositionResponse depositDisposition =
                persistDispositionObligation(dispositionPlan, occurredAt);
        ReservationDetailResponse response = ReservationDetailResponse.from(
                managedReservation,
                menuSnapshots,
                audit.getActorType(),
                audit.getCancellationReason(),
                depositDisposition
        );
        notificationPublisher.recordCancelled(
                managedReservation, occurredAt, correlationId
        );
        return new BusinessResult<>(
                HttpStatus.OK.value(),
                SUCCESS_RESPONSE_CODE,
                "RESERVATION",
                String.valueOf(managedReservation.getId()),
                response
        );
    }

    private void requireCancellationDependencies() {
        if (capacityAllocationRepository == null
                || cancellationPolicyEvaluator == null
                || cancellationAuditRepository == null
                || menuHoldPort == null
                || notificationPublisher == null) {
            throw new IllegalStateException("reservation cancellation dependencies are required");
        }
    }

    private DepositDispositionPlan depositDispositionPlan(
            Reservation reservation,
            ReservationCancellationActorType actorType,
            long policyVersion,
            Instant requestedAt,
            String sourceEventId,
            String cancellationIdempotencyKey
    ) {
        if (policyVersion != 2L) {
            return null;
        }
        if (depositProcessRepository == null || dispositionObligationRepository == null) {
            throw new IllegalStateException(
                    "reservation deposit disposition dependencies are required");
        }
        ReservationDepositProcessRepository.DepositProcessLink link =
                depositProcessRepository
                        .findDepositProcessLinkByFinalReservationId(reservation.getId())
                        .orElseThrow(() -> new ServiceException(
                                ReservationErrorCode.CANCELLATION_NOT_ALLOWED));
        if (link.getProcessId() <= 0
                || link.getStatus() != ReservationDepositProcessStatus.COMPLETED
                || link.getFinalReservationId() == null
                || !Objects.equals(link.getFinalReservationId(), reservation.getId())
                || link.getPaymentId() == null
                || !link.getPaymentId().matches("^[1-9][0-9]{0,18}$")) {
            throw new ServiceException(ReservationErrorCode.CANCELLATION_NOT_ALLOWED);
        }
        ReservationDepositDispositionDecision.Responsibility responsibility =
                switch (actorType) {
                    case CONSUMER ->
                            ReservationDepositDispositionDecision.Responsibility.CONSUMER;
                    case STORE_OPERATOR ->
                            ReservationDepositDispositionDecision.Responsibility.STORE_RESPONSIBLE;
                };
        ReservationDepositDispositionDecision disposition =
                cancellationPolicyEvaluator.evaluateDepositDisposition(
                        policyVersion,
                        responsibility,
                        reservation.getCreatedAt(),
                        reservation.getStartAt(),
                        requestedAt
                );
        return new DepositDispositionPlan(
                link.getProcessId(),
                reservation.getId(),
                link.getPaymentId(),
                sourceEventId,
                "RESERVATION_CANCELLED",
                cancellationIdempotencyKey,
                disposition
        );
    }

    private DepositDispositionPlan fulfillmentDispositionPlan(
            Reservation reservation,
            String sourceEventId
    ) {
        if (!Long.valueOf(2L).equals(reservation.getCancellationPolicyVersion())) {
            return null;
        }
        if (depositProcessRepository == null || dispositionObligationRepository == null) {
            throw new IllegalStateException(
                    "reservation deposit disposition dependencies are required");
        }
        ReservationDepositProcessRepository.DepositProcessLink link =
                depositProcessRepository
                        .findDepositProcessLinkByFinalReservationId(reservation.getId())
                        .orElseThrow(() -> new IllegalStateException(
                                "completed reservation deposit process link is required"));
        if (link.getProcessId() <= 0
                || link.getStatus() != ReservationDepositProcessStatus.COMPLETED
                || link.getFinalReservationId() == null
                || link.getFinalReservationId() != reservation.getId()
                || link.getPaymentId() == null
                || !link.getPaymentId().matches("^[1-9][0-9]{0,18}$")) {
            throw new IllegalStateException(
                    "completed reservation deposit process link is required");
        }
        String obligationKey = UUID.randomUUID().toString();
        return new DepositDispositionPlan(
                link.getProcessId(),
                reservation.getId(),
                link.getPaymentId(),
                sourceEventId,
                "RESERVATION_FULFILLED",
                obligationKey,
                new ReservationDepositDispositionDecision(
                        2L,
                        ReservationDepositDispositionDecision.Responsibility.CONSUMER,
                        10_000)
        );
    }

    private ReservationDepositDispositionResponse persistDispositionObligation(
            DepositDispositionPlan plan,
            Instant now
    ) {
        if (plan == null) {
            return null;
        }
        ReservationDepositDispositionObligation obligation =
                ReservationDepositDispositionObligation.pending(
                        plan.processId(),
                        plan.reservationId(),
                        plan.paymentId(),
                        plan.sourceEventId(),
                        plan.sourceEventType(),
                        null,
                        plan.decision().policyVersion(),
                        plan.decision().responsibility().name(),
                        plan.decision().targetRefundRateBasisPoints(),
                        UUID.randomUUID().toString(),
                        plan.cancellationIdempotencyKey(),
                        now
                );
        return ReservationDepositDispositionResponse.from(
                dispositionObligationRepository.saveAndFlush(obligation));
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
        if (bucketIds == null) {
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

    private static List<ReservationCapacityBucket> validateLockedBucketMetadata(
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
        return buckets;
    }

    private static void validateLockedOriginalBucketSet(
            Map<Long, ReservationCapacityBucket> lockedById,
            Set<Long> expectedIds,
            long storeId,
            CancellationCapacityWindow window,
            long expectedVersion
    ) {
        List<ReservationCapacityBucket> buckets = validateLockedBucketMetadata(
                lockedById, expectedIds, storeId, window, expectedVersion);
        validateCancellationCoverage(buckets, window);
    }

    private static void validateLockedCurrentBucketSet(
            Map<Long, ReservationCapacityBucket> lockedById,
            Set<Long> expectedIds,
            long storeId,
            CancellationCapacityWindow window,
            long expectedVersion
    ) {
        List<ReservationCapacityBucket> buckets = validateLockedBucketMetadata(
                lockedById, expectedIds, storeId, window, expectedVersion);
        for (ReservationCapacityBucket bucket : buckets) {
            if (bucket.getStartTime() == null
                    || bucket.getEndTime() == null
                    || !bucket.getStartTime().isBefore(window.occupancyEndTime())
                    || !bucket.getEndTime().isAfter(window.startTime())) {
                throw capacityPolicyChanged();
            }
        }
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

    private ReservationFulfillmentCommandResult fulfillmentResult(
            IdempotentOutcome outcome
    ) {
        if (outcome == null
                || outcome.data() == null
                || outcome.httpStatus() != HttpStatus.OK.value()
                || !SUCCESS_RESPONSE_CODE.equals(outcome.responseCode())
                || !"RESERVATION".equals(outcome.resourceType())) {
            throw new IllegalStateException("fulfillment idempotent outcome is inconsistent");
        }
        ReservationDetailResponse deserialized = objectMapper.treeToValue(
                outcome.data(),
                ReservationDetailResponse.class
        );
        if (!outcome.resourceId().equals(deserialized.reservationId())) {
            throw new IllegalStateException("fulfillment resource id is inconsistent");
        }
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
                deserialized.cancellationReason(),
                deserialized.depositDisposition()
        );
        return new ReservationFulfillmentCommandResult(outcome.httpStatus(), response);
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
                deserialized.cancellationReason(),
                deserialized.depositDisposition()
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

    private record DepositDispositionPlan(
            long processId,
            long reservationId,
            String paymentId,
            String sourceEventId,
            String sourceEventType,
            String cancellationIdempotencyKey,
            ReservationDepositDispositionDecision decision
    ) {
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
            List<ReservationMenuHoldSelection> menuSelections = quantitiesByMenuId.entrySet().stream()
                    .map(entry -> new ReservationMenuHoldSelection(
                            entry.getKey(), entry.getValue()))
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
        StringBuilder canonical = new StringBuilder(
                "POST|/api/v1/consumers/me/reservations|");
        append(canonical, "storeId", Long.toString(request.storeId()));
        append(canonical, "serviceDate", request.serviceDate().toString());
        append(canonical, "startTime", request.startTime().toString());
        append(canonical, "startOffset", request.startOffset() == null
                ? "" : request.startOffset().toString());
        append(canonical, "adultCount", Integer.toString(request.adultCount()));
        append(canonical, "childCount", Integer.toString(request.childCount()));
        append(canonical, "infantCount", Integer.toString(request.infantCount()));
        for (ReservationMenuHoldSelection selection : request.menuSelections()) {
            append(canonical, "menuId", Long.toString(selection.menuId()));
            append(canonical, "quantity", Integer.toString(selection.quantity()));
        }
        return RequestFingerprint.of(canonical.toString());
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
            List<ReservationMenuHoldSelection> menuSelections
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

        List<ReservationTimeResolutionResult> timeResults =
                timeResolutionService.resolveReservationTimes(
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
        List<ReservationMenuHoldItemSnapshot> menuSnapshots =
                menuHoldPort.findSnapshots(reservation.getId());
        Optional<ReservationCancellationAudit> cancellationAudit =
                findCancellationAudit(reservation.getId());
        return ReservationDetailResponse.from(
                reservation,
                menuSnapshots,
                cancellationAudit.map(ReservationCancellationAudit::getActorType).orElse(null),
                cancellationAudit.map(ReservationCancellationAudit::getCancellationReason)
                        .orElse(null),
                latestDepositDisposition(reservation)
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
        List<ReservationMenuHoldItemSnapshot> menuSnapshots =
                menuHoldPort.findSnapshots(reservation.getId());
        Optional<ReservationCancellationAudit> cancellationAudit =
                findCancellationAudit(reservation.getId());
        return ReservationDetailResponse.from(
                reservation,
                menuSnapshots,
                cancellationAudit.map(ReservationCancellationAudit::getActorType).orElse(null),
                cancellationAudit.map(ReservationCancellationAudit::getCancellationReason)
                        .orElse(null),
                latestDepositDisposition(reservation)
        );
    }

    private ReservationDepositDispositionResponse latestDepositDisposition(
            Reservation reservation
    ) {
        if (reservation.getCancellationPolicyVersion() == null
                || reservation.getCancellationPolicyVersion() != 2L
                || dispositionObligationRepository == null) {
            return null;
        }
        return dispositionObligationRepository
                .findFirstByReservationIdOrderByIdDesc(reservation.getId())
                .map(ReservationDepositDispositionResponse::from)
                .orElse(null);
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
            case START_AT_DESC, START_AT_ASC -> "timeSnapshot.startAt";
        };
        Sort.Direction direction = switch (order) {
            case CREATED_AT_DESC, SERVICE_DATE_DESC, START_AT_DESC -> Sort.Direction.DESC;
            case CREATED_AT_ASC, SERVICE_DATE_ASC, START_AT_ASC -> Sort.Direction.ASC;
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
                "PUT|/api/v1/store-operators/stores/{storeId}"
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
                "POST|/api/v1/store-operators/stores/{storeId}"
                        + "/reservation-time-policies/{version}/publications|"
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
                "POST|/api/v1/store-operators/stores/{storeId}"
                        + "/reservation-time-policies/{version}"
                        + "/publication-cancellations|"
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

    private record CapacityWindow(
            LocalTime startTime,
            LocalTime endTime
    ) {
    }
}
