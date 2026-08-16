package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.ReservationHoldContracts;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationHold;
import com.miriyum.domain.reservation.entity.ReservationHoldCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.entity.ReservationHoldTransitionAudit;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.port.ReservationTemporaryMenuHoldPort;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldCommand;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldResult;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldTransitionAuditRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Applies the shared ReservationHold transition rules inside a caller-owned transaction. */
@Service
public class ReservationHoldTransitionPrimitive {

    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final String EXPIRATION_COMMAND_PREFIX = "reservation-hold-expire:";

    private final ReservationHoldRepository holdRepository;
    private final ReservationHoldCapacityAllocationRepository allocationRepository;
    private final ReservationHoldTransitionAuditRepository auditRepository;
    private final ReservationCapacityBucketRepository capacityBucketRepository;
    private final ReservationTemporaryMenuHoldPort temporaryMenuHoldPort;
    private final Clock clock;

    public ReservationHoldTransitionPrimitive(
            ReservationHoldRepository holdRepository,
            ReservationHoldCapacityAllocationRepository allocationRepository,
            ReservationHoldTransitionAuditRepository auditRepository,
            ReservationCapacityBucketRepository capacityBucketRepository,
            ReservationTemporaryMenuHoldPort temporaryMenuHoldPort,
            Clock clock
    ) {
        this.holdRepository = holdRepository;
        this.allocationRepository = allocationRepository;
        this.auditRepository = auditRepository;
        this.capacityBucketRepository = capacityBucketRepository;
        this.temporaryMenuHoldPort = temporaryMenuHoldPort;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
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
                    .findByIdForUpdate(normalized.reservationHoldId())
                    .orElseThrow(ReservationHoldTransitionPrimitive::holdNotFound);
            ReservationTemporaryMenuHoldResult replayMenuHold =
                    temporaryMenuHoldPort.lockForTransition(
                            normalized.reservationHoldId());
            requireFinalLinkageMeaning(
                    replayMenuHold, normalized, current.getStatus(), true);
            return resultOf(current);
        }

        ReservationHold hold = holdRepository
                .findByIdForUpdate(normalized.reservationHoldId())
                .orElseThrow(ReservationHoldTransitionPrimitive::holdNotFound);
        requireCanonicalExpirationCommand(hold, normalized);
        ReservationHoldTransitionAudit concurrentReplay = auditRepository
                .findByCommandId(normalized.operationId())
                .orElse(null);
        if (concurrentReplay != null) {
            requireSameTransitionMeaning(concurrentReplay, normalized);
            ReservationTemporaryMenuHoldResult replayMenuHold =
                    temporaryMenuHoldPort.lockForTransition(
                            normalized.reservationHoldId());
            requireFinalLinkageMeaning(
                    replayMenuHold, normalized, hold.getStatus(), true);
            return resultOf(hold);
        }
        ReservationTemporaryMenuHoldResult menuHold =
                temporaryMenuHoldPort.lockForTransition(normalized.reservationHoldId());
        if (isUnexecutedExpirationNoOp(hold, normalized)) {
            return resultOf(hold);
        }
        Instant occurredAt = clock.instant();
        NormalizedTransitionCommand effective = effectiveTransitionCommand(
                hold, normalized, occurredAt);
        requireFinalLinkageMeaning(menuHold, effective, hold.getStatus(), false);
        ReservationHoldStatus beforeStatus = hold.getStatus();
        validateReservationHoldTransition(
                hold, effective.targetStatus(), occurredAt);
        if (effective.targetStatus().requiresCapacityRelease()) {
            restoreCapacity(hold);
        }
        applyTemporaryMenuHoldTransition(menuHold, effective);
        applyTransition(hold, effective.targetStatus(), occurredAt);
        auditRepository.save(ReservationHoldTransitionAudit.record(
                effective.reservationHoldId(),
                effective.actorType(),
                effective.actorId(),
                effective.requestedAt(),
                occurredAt,
                beforeStatus,
                effective.targetStatus(),
                hold.getReservationTimePolicyVersion(),
                hold.getCapacityPolicyVersion(),
                effective.operationId()));
        ReservationHold persisted = holdRepository.saveAndFlush(hold);
        return resultOf(persisted);
    }

    private static void requireCanonicalExpirationCommand(
            ReservationHold hold,
            NormalizedTransitionCommand requested
    ) {
        if (!requested.operationId().startsWith(EXPIRATION_COMMAND_PREFIX)) {
            return;
        }
        String expectedOperationId = EXPIRATION_COMMAND_PREFIX + hold.getId();
        if (requested.targetStatus() != ReservationHoldStatus.EXPIRED
                || !expectedOperationId.equals(requested.operationId())
                || !SYSTEM_ACTOR.equals(requested.actorType())
                || requested.actorId() != null
                || !hold.getExpiresAt().equals(requested.requestedAt())) {
            throw new IllegalArgumentException(
                    "reserved namespace requires the canonical expiration command");
        }
    }

    private static boolean isUnexecutedExpirationNoOp(
            ReservationHold hold,
            NormalizedTransitionCommand requested
    ) {
        if (hold.getStatus() == ReservationHoldStatus.EXPIRED) {
            return true;
        }
        return requested.targetStatus() == ReservationHoldStatus.EXPIRED
                && hold.getStatus() != ReservationHoldStatus.ACTIVE;
    }

    private static NormalizedTransitionCommand effectiveTransitionCommand(
            ReservationHold hold,
            NormalizedTransitionCommand requested,
            Instant occurredAt
    ) {
        if (hold.getStatus() != ReservationHoldStatus.ACTIVE
                || requested.targetStatus() == ReservationHoldStatus.EXPIRED
                || occurredAt.isBefore(hold.getExpiresAt())) {
            return requested;
        }
        return new NormalizedTransitionCommand(
                requested.reservationHoldId(),
                ReservationHoldStatus.EXPIRED,
                EXPIRATION_COMMAND_PREFIX + requested.reservationHoldId(),
                SYSTEM_ACTOR,
                null,
                hold.getExpiresAt(),
                null);
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
                .orElseThrow(ReservationHoldTransitionPrimitive::capacityConfigurationConflict);
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
                .orElseThrow(ReservationHoldTransitionPrimitive::capacityConfigurationConflict);
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

    private static void validateReservationHoldTransition(
            ReservationHold hold,
            ReservationHoldStatus targetStatus,
            Instant occurredAt
    ) {
        switch (targetStatus) {
            case CONFIRMED -> requireHoldStatus(
                    hold,
                    ReservationHoldStatus.ACTIVE,
                    ReservationHoldStatus.RECONCILIATION_REQUIRED);
            case RELEASED -> hold.validateRelease();
            case EXPIRED -> hold.validateExpiry(occurredAt);
            case RECONCILIATION_REQUIRED -> requireHoldStatus(
                    hold, ReservationHoldStatus.ACTIVE);
            case ACTIVE -> throw new ServiceException(
                    ReservationErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private static void requireHoldStatus(
            ReservationHold hold,
            ReservationHoldStatus... allowed
    ) {
        for (ReservationHoldStatus status : allowed) {
            if (hold.getStatus() == status) {
                return;
            }
        }
        throw new ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION);
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

    private void applyTemporaryMenuHoldTransition(
            ReservationTemporaryMenuHoldResult menuHold,
            NormalizedTransitionCommand command
    ) {
        if (menuHold == null) {
            throw new IllegalStateException("temporary MenuHold lock result is required");
        }
        if (menuHold.presence()
                == ReservationTemporaryMenuHoldResult.Presence.NO_HOLD) {
            return;
        }
        temporaryMenuHoldPort.applyTransition(
                new ReservationTemporaryMenuHoldCommand.ApplyTransition(
                        command.reservationHoldId(),
                        toTemporaryMenuHoldTarget(command.targetStatus()),
                        command.operationId(),
                        command.finalReservationId()));
    }

    private static void requireFinalLinkageMeaning(
            ReservationTemporaryMenuHoldResult menuHold,
            NormalizedTransitionCommand command,
            ReservationHoldStatus currentHoldStatus,
            boolean replay
    ) {
        if (menuHold == null) {
            throw new IllegalStateException("temporary MenuHold lock result is required");
        }
        boolean matches;
        if (menuHold.presence()
                == ReservationTemporaryMenuHoldResult.Presence.NO_HOLD) {
            matches = command.finalReservationId() == null;
        } else if (replay) {
            matches = isLegalReplayCurrentStatus(
                    command.targetStatus(), currentHoldStatus)
                    && isLegalReplayMenuHoldState(menuHold, currentHoldStatus)
                    && (command.targetStatus() != ReservationHoldStatus.CONFIRMED
                    || Objects.equals(
                            menuHold.finalReservationId(),
                            command.finalReservationId()));
        } else if (command.targetStatus() != ReservationHoldStatus.CONFIRMED) {
            matches = command.finalReservationId() == null;
        } else {
            matches = command.finalReservationId() != null
                    && command.finalReservationId() > 0;
        }
        if (matches) {
            return;
        }
        if (replay) {
            throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        throw new IllegalArgumentException(
                "finalReservationId must match persistent temporary MenuHold presence");
    }

    private static boolean isLegalReplayMenuHoldState(
            ReservationTemporaryMenuHoldResult menuHold,
            ReservationHoldStatus currentHoldStatus
    ) {
        if (menuHold.state() == expectedMenuHoldState(currentHoldStatus)) {
            return true;
        }
        return currentHoldStatus == ReservationHoldStatus.CONFIRMED
                && (menuHold.state() == ReservationTemporaryMenuHoldResult.State.RELEASED
                || menuHold.state() == ReservationTemporaryMenuHoldResult.State.FULFILLED
                || menuHold.state() == ReservationTemporaryMenuHoldResult.State.FORFEITED)
                && menuHold.finalReservationId() != null
                && menuHold.finalReservationId() > 0;
    }

    private static boolean isLegalReplayCurrentStatus(
            ReservationHoldStatus auditedTargetStatus,
            ReservationHoldStatus currentStatus
    ) {
        return switch (auditedTargetStatus) {
            case RECONCILIATION_REQUIRED ->
                    currentStatus == ReservationHoldStatus.RECONCILIATION_REQUIRED
                            || currentStatus == ReservationHoldStatus.CONFIRMED
                            || currentStatus == ReservationHoldStatus.RELEASED;
            case CONFIRMED, RELEASED, EXPIRED -> currentStatus == auditedTargetStatus;
            case ACTIVE -> false;
        };
    }

    private static ReservationTemporaryMenuHoldResult.State expectedMenuHoldState(
            ReservationHoldStatus targetStatus
    ) {
        return switch (targetStatus) {
            case CONFIRMED -> ReservationTemporaryMenuHoldResult.State.CONFIRMED;
            case RELEASED -> ReservationTemporaryMenuHoldResult.State.RELEASED;
            case EXPIRED -> ReservationTemporaryMenuHoldResult.State.EXPIRED;
            case RECONCILIATION_REQUIRED ->
                    ReservationTemporaryMenuHoldResult.State.RECONCILIATION_REQUIRED;
            case ACTIVE -> throw new ServiceException(
                    ReservationErrorCode.INVALID_STATE_TRANSITION);
        };
    }

    private static ReservationTemporaryMenuHoldCommand.Target toTemporaryMenuHoldTarget(
            ReservationHoldStatus targetStatus
    ) {
        return switch (targetStatus) {
            case CONFIRMED -> ReservationTemporaryMenuHoldCommand.Target.CONFIRM;
            case RELEASED -> ReservationTemporaryMenuHoldCommand.Target.RELEASE;
            case EXPIRED -> ReservationTemporaryMenuHoldCommand.Target.EXPIRE;
            case RECONCILIATION_REQUIRED ->
                    ReservationTemporaryMenuHoldCommand.Target.REQUIRE_RECONCILIATION;
            case ACTIVE -> throw new ServiceException(
                    ReservationErrorCode.INVALID_STATE_TRANSITION);
        };
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
        if (command.targetStatus() == ReservationHoldStatus.CONFIRMED) {
            if (command.finalReservationId() != null
                    && command.finalReservationId() <= 0) {
                throw new IllegalArgumentException(
                        "finalReservationId must be positive when present");
            }
        } else if (command.finalReservationId() != null) {
            throw new IllegalArgumentException(
                    "finalReservationId is allowed only for CONFIRMED");
        }
        String operationId = normalizeText(command.operationId(), 100, "operationId");
        if (operationId.startsWith(EXPIRATION_COMMAND_PREFIX)
                && command.targetStatus() != ReservationHoldStatus.EXPIRED) {
            throw new IllegalArgumentException(
                    "expiration operation namespace is reserved for EXPIRED");
        }
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
                command.requestedAt().truncatedTo(ChronoUnit.MICROS),
                command.finalReservationId());
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

    private static long requirePersistedId(ReservationHold hold) {
        if (hold == null || hold.getId() == null || hold.getId() <= 0) {
            throw new IllegalStateException("saved reservation hold id is required");
        }
        return hold.getId();
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

    private record NormalizedTransitionCommand(
            long reservationHoldId,
            ReservationHoldStatus targetStatus,
            String operationId,
            String actorType,
            Long actorId,
            Instant requestedAt,
            Long finalReservationId
    ) {
    }
}
