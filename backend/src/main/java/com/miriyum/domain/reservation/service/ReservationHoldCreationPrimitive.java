package com.miriyum.domain.reservation.service;

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
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates a ReservationHold inside a caller-owned transaction after Store serialization. */
@Service
public class ReservationHoldCreationPrimitive {

    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final String CREATION_AUDIT_COMMAND_PREFIX = "reservation-hold-create:";

    private final ReservationHoldRepository holdRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationHoldCapacityAllocationRepository allocationRepository;
    private final ReservationHoldTransitionAuditRepository auditRepository;
    private final ReservationHoldWarningTaskRepository warningTaskRepository;
    private final ReservationCapacityBucketRepository capacityBucketRepository;
    private final ReservationTemporaryMenuHoldPort temporaryMenuHoldPort;
    private final ReservationTimeResolutionService timeResolutionService;
    private final ReservationCancellationPolicySelector cancellationPolicySelector;
    private final Clock clock;
    private final Supplier<UUID> auditCommandIdSupplier;
    private final ReservationCreationCapacityValidator capacityValidator =
            new ReservationCreationCapacityValidator();

    public ReservationHoldCreationPrimitive(
            ReservationHoldRepository holdRepository,
            ReservationRepository reservationRepository,
            ReservationHoldCapacityAllocationRepository allocationRepository,
            ReservationHoldTransitionAuditRepository auditRepository,
            ReservationHoldWarningTaskRepository warningTaskRepository,
            ReservationCapacityBucketRepository capacityBucketRepository,
            ReservationTemporaryMenuHoldPort temporaryMenuHoldPort,
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
                timeResolutionService,
                cancellationPolicySelector,
                clock,
                UUID::randomUUID
        );
    }

    ReservationHoldCreationPrimitive(
            ReservationHoldRepository holdRepository,
            ReservationRepository reservationRepository,
            ReservationHoldCapacityAllocationRepository allocationRepository,
            ReservationHoldTransitionAuditRepository auditRepository,
            ReservationHoldWarningTaskRepository warningTaskRepository,
            ReservationCapacityBucketRepository capacityBucketRepository,
            ReservationTemporaryMenuHoldPort temporaryMenuHoldPort,
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
        this.timeResolutionService = timeResolutionService;
        this.cancellationPolicySelector = cancellationPolicySelector;
        this.clock = clock;
        this.auditCommandIdSupplier = auditCommandIdSupplier;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationHold create(Command command) {
        ReservationHold concurrentReplay = holdRepository
                .findByConsumerAccountIdAndCreationCommandId(
                        command.consumerAccountId(), command.creationCommandId())
                .orElse(null);
        if (concurrentReplay != null) {
            if (!sameUserControlledMeaning(concurrentReplay, command)) {
                throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            verifyMenuCreationReplay(concurrentReplay, command.menuSelections());
            return concurrentReplay;
        }

        ReservationTimeSnapshot timeSnapshot = timeResolutionService.resolveCreationTime(
                command.store().storeId(),
                new ReservationTimeRequest(
                        command.serviceDate(), command.startTime(), command.startOffset()));
        List<Reservation> confirmedReservations =
                reservationRepository.findConfirmedOverlappingForUpdate(
                        command.consumerAccountId(),
                        command.store().storeId(),
                        timeSnapshot.getStartAt(),
                        timeSnapshot.getServiceEndAt());
        List<ReservationHold> protectedHolds = holdRepository.findProtectedOverlappingForUpdate(
                command.consumerAccountId(),
                command.store().storeId(),
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
                        command.store().storeId(),
                        command.serviceDate(),
                        command.startTime(),
                        occupancyEndTime);
        long capacityPolicyVersion = capacityValidator.validate(
                buckets,
                command.store().storeId(),
                command.serviceDate(),
                command.startTime(),
                occupancyEndTime,
                command.party().totalCount(),
                command.party().getInfantCount() > 0);
        for (ReservationCapacityBucket bucket : buckets) {
            bucket.occupy(command.party().totalCount());
        }

        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ReservationHold hold = ReservationHold.active(
                command.consumerAccountId(),
                command.store().storeId(),
                command.store().storeName(),
                timeSnapshot,
                command.party(),
                ReservationContactSnapshot.contactable(
                        command.notificationTargetReference()),
                capacityPolicyVersion,
                requireCancellationPolicy(),
                command.creationCommandId(),
                createdAt);
        ReservationHold saved = holdRepository.saveAndFlush(hold);
        long holdId = requirePersistedId(saved);

        List<ReservationHoldCapacityAllocation> allocations = buckets.stream()
                .map(bucket -> ReservationHoldCapacityAllocation.allocate(
                        holdId,
                        requireBucketId(bucket),
                        command.party().totalCount(),
                        capacityPolicyVersion))
                .toList();
        allocationRepository.saveAll(allocations);
        createTemporaryMenuHold(saved, command.menuSelections());
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
                CREATION_AUDIT_COMMAND_PREFIX + requireAuditCommandId()));
        warningTaskRepository.save(ReservationHoldWarningTask.schedule(
                holdId, saved.getCreatedAt(), saved.getExpiresAt()));
        return saved;
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
            Command command
    ) {
        if (!command.creationCommandId().equals(hold.getCreationCommandId())
                || hold.getConsumerAccountId() != command.consumerAccountId()
                || hold.getStoreId() != command.store().storeId()
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

    public record Command(
            long consumerAccountId,
            StoreReservationTransactionEligibility store,
            LocalDate serviceDate,
            LocalTime startTime,
            ZoneOffset startOffset,
            PartyComposition party,
            String notificationTargetReference,
            String creationCommandId,
            List<ReservationTemporaryMenuHoldSelection> menuSelections
    ) {
        public Command {
            menuSelections = List.copyOf(menuSelections);
        }
    }
}
