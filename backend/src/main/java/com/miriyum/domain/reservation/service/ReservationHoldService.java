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
import java.util.List;
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
            return resultOf(replay);
        }

        ReservationContactResult contact = consumerAccountService.getReservationContact(
                normalized.consumerAccountId());
        requireContactable(contact);
        StoreReservationTransactionEligibility store =
                storeEligibilityService.requireReservationTransactionEligibility(
                        normalized.storeId());
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

        Instant createdAt = clock.instant();
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
                creationCommandId);
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
        if (hold.getConsumerAccountId() != command.consumerAccountId()
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
            String creationCommandId
    ) {
    }
}
