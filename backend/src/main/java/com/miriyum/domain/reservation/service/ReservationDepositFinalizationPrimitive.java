package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.ReservationHoldContracts;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationHold;
import com.miriyum.domain.reservation.entity.ReservationHoldCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Converts one already-occupied Hold into an equivalent final Reservation. */
@Service
public class ReservationDepositFinalizationPrimitive {

    private final ReservationHoldRepository holdRepository;
    private final ReservationHoldCapacityAllocationRepository holdAllocationRepository;
    private final ReservationCapacityBucketRepository bucketRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationCapacityAllocationRepository allocationRepository;
    private final ReservationHoldTransitionPrimitive transitionPrimitive;

    public ReservationDepositFinalizationPrimitive(
            ReservationHoldRepository holdRepository,
            ReservationHoldCapacityAllocationRepository holdAllocationRepository,
            ReservationCapacityBucketRepository bucketRepository,
            ReservationRepository reservationRepository,
            ReservationCapacityAllocationRepository allocationRepository,
            ReservationHoldTransitionPrimitive transitionPrimitive
    ) {
        this.holdRepository = holdRepository;
        this.holdAllocationRepository = holdAllocationRepository;
        this.bucketRepository = bucketRepository;
        this.reservationRepository = reservationRepository;
        this.allocationRepository = allocationRepository;
        this.transitionPrimitive = transitionPrimitive;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Reservation finalizeResources(Command command) {
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
        ReservationHold hold = holdRepository.findByIdForUpdate(command.reservationHoldId())
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.RESERVATION_NOT_FOUND));
        requireFinalizable(hold);
        List<ReservationHoldCapacityAllocation> heldAllocations =
                requireHeldAllocations(hold);

        Reservation reservation = Reservation.confirm(
                hold.getConsumerAccountId(),
                hold.getStoreId(),
                hold.getStoreNameSnapshot(),
                hold.getTimeSnapshot(),
                hold.getParty(),
                hold.getContactSnapshot(),
                hold.getCapacityPolicyVersion(),
                new ReservationCancellationPolicyVersion(
                        hold.getCancellationPolicyVersion()),
                command.requestedAt());
        Reservation saved = reservationRepository.saveAndFlush(reservation);
        long reservationId = requirePersistedReservationId(saved);

        transitionPrimitive.transitionFinalizedReservation(
                new ReservationHoldContracts.TransitionCommand(
                        command.reservationHoldId(),
                        ReservationHoldStatus.CONFIRMED,
                        command.operationId(),
                        command.actorType(),
                        command.actorId(),
                        command.requestedAt(),
                        reservationId));

        List<Long> bucketIds = heldAllocations.stream()
                .map(ReservationHoldCapacityAllocation::getCapacityBucketId)
                .toList();
        Map<Long, ReservationCapacityBucket> bucketById = requireLockedBuckets(
                hold,
                bucketIds,
                bucketRepository.findAllByIdInForUpdate(bucketIds));
        for (ReservationHoldCapacityAllocation held : heldAllocations) {
            ReservationCapacityBucket bucket = bucketById.get(held.getCapacityBucketId());
            if (bucket.getOccupiedPeople() < held.getOccupiedPeople()
                    || bucket.getOccupiedTeams() < held.getOccupiedTeams()) {
                throw capacityConfigurationConflict();
            }
        }
        List<ReservationCapacityAllocation> allocations = heldAllocations.stream()
                .map(held -> ReservationCapacityAllocation.allocate(
                        reservationId,
                        held.getCapacityBucketId(),
                        held.getOccupiedPeople(),
                        held.getCapacityPolicyVersion()))
                .toList();
        allocationRepository.saveAll(allocations);
        return saved;
    }

    private static void requireFinalizable(ReservationHold hold) {
        if (hold.getStatus() != ReservationHoldStatus.ACTIVE
                && hold.getStatus() != ReservationHoldStatus.RECONCILIATION_REQUIRED) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private List<ReservationHoldCapacityAllocation> requireHeldAllocations(
            ReservationHold hold
    ) {
        List<ReservationHoldCapacityAllocation> allocations = holdAllocationRepository
                .findAllByReservationHoldIdOrderByCapacityBucketIdAsc(hold.getId());
        if (allocations == null || allocations.isEmpty()) {
            throw capacityConfigurationConflict();
        }
        long previousBucketId = 0;
        for (ReservationHoldCapacityAllocation allocation : allocations) {
            if (allocation == null
                    || allocation.getReservationHoldId() == null
                    || !allocation.getReservationHoldId().equals(hold.getId())
                    || allocation.getCapacityBucketId() == null
                    || allocation.getCapacityBucketId() <= previousBucketId
                    || allocation.getOccupiedPeople() != hold.getParty().totalCount()
                    || allocation.getOccupiedTeams() != 1
                    || allocation.getCapacityPolicyVersion()
                    != hold.getCapacityPolicyVersion()) {
                throw capacityConfigurationConflict();
            }
            previousBucketId = allocation.getCapacityBucketId();
        }
        return allocations;
    }

    private static Map<Long, ReservationCapacityBucket> requireLockedBuckets(
            ReservationHold hold,
            List<Long> expectedIds,
            List<ReservationCapacityBucket> buckets
    ) {
        if (buckets == null || buckets.size() != expectedIds.size()) {
            throw capacityConfigurationConflict();
        }
        Map<Long, ReservationCapacityBucket> byId = new LinkedHashMap<>();
        for (int index = 0; index < buckets.size(); index++) {
            ReservationCapacityBucket bucket = buckets.get(index);
            Long expectedId = expectedIds.get(index);
            if (bucket == null
                    || bucket.getId() == null
                    || !expectedId.equals(bucket.getId())
                    || byId.put(bucket.getId(), bucket) != null
                    || !hold.getStoreId().equals(bucket.getStoreId())
                    || !hold.getServiceDate().equals(bucket.getServiceDate())
                    || bucket.getPolicyVersion() != hold.getCapacityPolicyVersion()) {
                throw capacityConfigurationConflict();
            }
        }
        return byId;
    }

    private static long requirePersistedReservationId(Reservation reservation) {
        if (reservation == null || reservation.getId() == null || reservation.getId() <= 0) {
            throw new IllegalStateException("saved reservation id is required");
        }
        return reservation.getId();
    }

    private static ServiceException capacityConfigurationConflict() {
        return new ServiceException(ReservationErrorCode.CAPACITY_CONFIGURATION_CONFLICT);
    }

    public record Command(
            long reservationHoldId,
            String operationId,
            String actorType,
            Long actorId,
            Instant requestedAt
    ) {
        public Command {
            if (reservationHoldId <= 0) {
                throw new IllegalArgumentException("reservationHoldId must be positive");
            }
            if (operationId == null || operationId.isBlank() || operationId.length() > 100) {
                throw new IllegalArgumentException("operationId must be 1 to 100 characters");
            }
            if (actorType == null || actorType.isBlank() || actorType.length() > 32) {
                throw new IllegalArgumentException("actorType must be 1 to 32 characters");
            }
            if ((actorId == null || actorId <= 0) && !"SYSTEM".equals(actorType)) {
                throw new IllegalArgumentException("non-system actorId must be positive");
            }
            if (requestedAt == null) {
                throw new IllegalArgumentException("requestedAt is required");
            }
        }
    }
}
