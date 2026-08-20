package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.request.CapacityBucketRequest;
import com.miriyum.domain.reservation.dto.request.ReservationCapacitiesRequest;
import com.miriyum.domain.reservation.dto.response.ReservationCapacitiesResponse;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationHold;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.service.StoreScheduleAuthority;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalRequest;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalResult;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalStatus;
import com.miriyum.domain.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReservationCapacityPublicationService {

    private static final String PRINCIPAL_NAMESPACE = "store-operator";
    private static final String COMMAND_TYPE = "RESERVATION_CAPACITY_REPLACE";
    private static final String RESOURCE_TYPE = "RESERVATION_CAPACITY_POLICY";

    private final StoreService storeService;
    private final StoreServiceIntervalValidationService intervalValidationService;
    private final ReservationCapacityPolicy capacityPolicy;
    private final ReservationCapacityBucketRepository capacityBucketRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationHoldRepository reservationHoldRepository;
    private final ReservationDepositProcessRepository depositProcessRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;

    public ReservationCapacityPublicationService(
            StoreService storeService,
            StoreServiceIntervalValidationService intervalValidationService,
            ReservationCapacityPolicy capacityPolicy,
            ReservationCapacityBucketRepository capacityBucketRepository,
            ReservationRepository reservationRepository,
            ReservationHoldRepository reservationHoldRepository,
            ReservationDepositProcessRepository depositProcessRepository,
            IdempotencyExecutor idempotencyExecutor,
            ObjectMapper objectMapper
    ) {
        this.storeService = storeService;
        this.intervalValidationService = intervalValidationService;
        this.capacityPolicy = capacityPolicy;
        this.capacityBucketRepository = capacityBucketRepository;
        this.reservationRepository = reservationRepository;
        this.reservationHoldRepository = reservationHoldRepository;
        this.depositProcessRepository = depositProcessRepository;
        this.idempotencyExecutor = idempotencyExecutor;
        this.objectMapper = objectMapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ReservationCapacityCommandResult replaceCapacities(
            long operatorId,
            long storeId,
            LocalDate serviceDate,
            IdempotencyKey key,
            ReservationCapacitiesRequest request
    ) {
        requireArguments(operatorId, storeId, serviceDate, key, request);
        storeService.requireManagementOwnership(operatorId, storeId);
        List<CapacityBucketRequest> normalized = capacityPolicy.validateAndSort(request);
        IdempotencyCommand command = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorId,
                COMMAND_TYPE,
                key.value(),
                fingerprint(storeId, serviceDate, normalized)
        );
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            StoreScheduleAuthority authority =
                    storeService.requireSchedulePublicationAuthority(operatorId, storeId);
            List<ResolvedBucket> resolved = resolveAndValidateIntervals(
                    storeId,
                    serviceDate,
                    authority,
                    normalized
            );
            List<Reservation> confirmed =
                    reservationRepository.findConfirmedForCapacityPublication(
                            storeId,
                            serviceDate
                    );
            List<ReservationHold> protectedHolds =
                    reservationHoldRepository
                            .findProtectedByStoreIdAndServiceDateForUpdateOrderByIdAsc(
                            storeId,
                            serviceDate
                    );
            Set<Long> linkedHoldIds = linkedHoldIds(confirmed, protectedHolds);
            List<ReservationCapacityBucket> current =
                    capacityBucketRepository.findLatestPolicyBucketsForUpdate(
                            storeId,
                            serviceDate
                    );
            long nextVersion = nextVersion(current);
            List<ReservationCapacityBucket> next = createBuckets(
                    storeId,
                    serviceDate,
                    nextVersion,
                    resolved,
                    confirmed,
                    protectedHolds,
                    linkedHoldIds
            );
            List<ReservationCapacityBucket> saved =
                    capacityBucketRepository.saveAllAndFlush(next);
            ReservationCapacitiesResponse response = ReservationCapacitiesResponse.from(
                    serviceDate,
                    nextVersion,
                    saved
            );
            return new BusinessResult<>(
                    HttpStatus.OK.value(),
                    "SUCCESS",
                    RESOURCE_TYPE,
                    resourceId(storeId, serviceDate, nextVersion),
                    response
            );
        });
        return new ReservationCapacityCommandResult(
                outcome.httpStatus(),
                objectMapper.treeToValue(outcome.data(), ReservationCapacitiesResponse.class)
        );
    }

    private List<ResolvedBucket> resolveAndValidateIntervals(
            long storeId,
            LocalDate serviceDate,
            StoreScheduleAuthority authority,
            List<CapacityBucketRequest> buckets
    ) {
        if (authority == null
                || authority.storeId() != storeId
                || authority.timeZoneId() == null) {
            throw conflict();
        }
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(authority.timeZoneId());
        } catch (DateTimeException exception) {
            throw conflict(exception);
        }

        List<ResolvedBucket> resolved = buckets.stream()
                .map(bucket -> new ResolvedBucket(
                        bucket,
                        resolveInstant(serviceDate, bucket.startTime(), zoneId),
                        resolveInstant(serviceDate, bucket.endTime(), zoneId)
                ))
                .toList();
        List<StoreServiceIntervalRequest> intervalRequests = resolved.stream()
                .map(bucket -> new StoreServiceIntervalRequest(
                        storeId,
                        bucket.startAt(),
                        bucket.endAt()
                ))
                .toList();
        List<StoreServiceIntervalResult> intervalResults =
                intervalValidationService.validateServiceIntervals(intervalRequests);
        if (!matchesIntervals(intervalRequests, intervalResults)) {
            throw conflict();
        }
        return resolved;
    }

    private static boolean matchesIntervals(
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
                    || !result.startAt().equals(request.startAt())
                    || !result.serviceEndAt().equals(request.serviceEndAt())
                    || result.status() != StoreServiceIntervalStatus.ACCEPTING) {
                return false;
            }
        }
        return true;
    }

    private static Instant resolveInstant(
            LocalDate serviceDate,
            java.time.LocalTime time,
            ZoneId zoneId
    ) {
        LocalDateTime localDateTime = LocalDateTime.of(serviceDate, time);
        List<ZoneOffset> offsets = zoneId.getRules().getValidOffsets(localDateTime);
        if (offsets.size() != 1) {
            throw conflict();
        }
        return localDateTime.toInstant(offsets.getFirst());
    }

    private static long nextVersion(List<ReservationCapacityBucket> current) {
        if (current == null) {
            throw conflict();
        }
        if (current.isEmpty()) {
            return 1L;
        }
        long version = current.getFirst().getPolicyVersion();
        if (current.stream().anyMatch(bucket -> bucket.getPolicyVersion() != version)) {
            throw conflict();
        }
        try {
            return Math.addExact(version, 1L);
        } catch (ArithmeticException exception) {
            throw conflict(exception);
        }
    }

    private static List<ReservationCapacityBucket> createBuckets(
            long storeId,
            LocalDate serviceDate,
            long policyVersion,
            List<ResolvedBucket> buckets,
            List<Reservation> confirmed,
            List<ReservationHold> protectedHolds,
            Set<Long> linkedHoldIds
    ) {
        if (confirmed == null || protectedHolds == null || linkedHoldIds == null) {
            throw conflict();
        }
        protectedHolds.forEach(hold -> validateProtectedHold(hold, storeId, serviceDate));
        List<ReservationCapacityBucket> created = new ArrayList<>(buckets.size());
        for (ResolvedBucket resolved : buckets) {
            int occupiedPeople = 0;
            int occupiedTeams = 0;
            for (Reservation reservation : confirmed) {
                if (reservation == null
                        || reservation.getTimeSnapshot() == null
                        || !reservation.getTimeSnapshot().hasResolvedTime()) {
                    throw conflict();
                }
                if (reservation.getStartAt().isBefore(resolved.endAt())
                        && reservation.getOccupancyEndAt().isAfter(resolved.startAt())) {
                    try {
                        occupiedPeople = Math.addExact(
                                occupiedPeople,
                                reservation.getParty().totalCount()
                        );
                        occupiedTeams = Math.addExact(occupiedTeams, 1);
                    } catch (ArithmeticException exception) {
                        throw conflict(exception);
                    }
                }
            }
            for (ReservationHold hold : protectedHolds) {
                if (linkedHoldIds.contains(hold.getId())) {
                    continue;
                }
                if (hold.getStartAt().isBefore(resolved.endAt())
                        && hold.getOccupancyEndAt().isAfter(resolved.startAt())) {
                    try {
                        occupiedPeople = Math.addExact(
                                occupiedPeople,
                                hold.getParty().totalCount()
                        );
                        occupiedTeams = Math.addExact(occupiedTeams, 1);
                    } catch (ArithmeticException exception) {
                        throw conflict(exception);
                    }
                }
            }
            CapacityBucketRequest request = resolved.request();
            created.add(ReservationCapacityBucket.create(
                    storeId,
                    serviceDate,
                    request.startTime(),
                    request.endTime(),
                    request.maxPeople(),
                    request.maxTeams(),
                    occupiedPeople,
                    occupiedTeams,
                    request.minPartySize(),
                    request.maxPartySize(),
                    request.infantsAllowed(),
                    policyVersion
            ));
        }
        return List.copyOf(created);
    }

    private Set<Long> linkedHoldIds(
            List<Reservation> confirmed,
            List<ReservationHold> protectedHolds
    ) {
        if (confirmed == null || protectedHolds == null) {
            throw conflict();
        }
        if (confirmed.isEmpty() || protectedHolds.isEmpty()) {
            return Set.of();
        }
        List<Long> finalReservationIds = confirmed.stream()
                .map(Reservation::getId)
                .toList();
        List<Long> reservationHoldIds = protectedHolds.stream()
                .map(ReservationHold::getId)
                .toList();
        if (finalReservationIds.stream().anyMatch(id -> id == null || id <= 0)
                || reservationHoldIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw conflict();
        }
        List<Long> linked = depositProcessRepository
                .findLinkedHoldIdsForFinalReservations(
                        finalReservationIds,
                        reservationHoldIds);
        if (linked == null) {
            throw conflict();
        }
        Set<Long> eligibleHoldIds = Set.copyOf(reservationHoldIds);
        Set<Long> deduplicated = new HashSet<>();
        for (Long holdId : linked) {
            if (holdId == null
                    || !eligibleHoldIds.contains(holdId)
                    || !deduplicated.add(holdId)) {
                throw conflict();
            }
        }
        return Set.copyOf(deduplicated);
    }

    private static void validateProtectedHold(
            ReservationHold hold,
            long storeId,
            LocalDate serviceDate
    ) {
        if (hold == null
                || !isProtected(hold.getStatus())
                || !Long.valueOf(storeId).equals(hold.getStoreId())
                || !hasValidPublicationTime(hold, storeId, serviceDate)
                || !hasValidParty(hold)) {
            throw conflict();
        }
    }

    private static boolean hasValidPublicationTime(
            ReservationHold hold,
            long storeId,
            LocalDate serviceDate
    ) {
        ReservationTimeSnapshot snapshot = hold.getTimeSnapshot();
        if (snapshot == null
                || !snapshot.hasResolvedTime()
                || !serviceDate.equals(snapshot.getServiceDate())
                || !Long.valueOf(storeId).equals(
                        snapshot.getReservationTimePolicyStoreId()
                )) {
            return false;
        }
        Instant startAt = snapshot.getStartAt();
        Instant serviceEndAt = snapshot.getServiceEndAt();
        Instant occupancyEndAt = snapshot.getOccupancyEndAt();
        if (!hasMinutePrecision(startAt)
                || !hasMinutePrecision(serviceEndAt)
                || !hasMinutePrecision(occupancyEndAt)
                || !startAt.isBefore(serviceEndAt)
                || serviceEndAt.isAfter(occupancyEndAt)) {
            return false;
        }
        try {
            ZoneId zoneId = ZoneId.of(snapshot.getTimeZoneId());
            return serviceDate.equals(startAt.atZone(zoneId).toLocalDate())
                    && snapshot.getStartOffsetSeconds()
                    == zoneId.getRules().getOffset(startAt).getTotalSeconds()
                    && snapshot.getServiceEndOffsetSeconds()
                    == zoneId.getRules().getOffset(serviceEndAt).getTotalSeconds()
                    && snapshot.getOccupancyEndOffsetSeconds()
                    == zoneId.getRules().getOffset(occupancyEndAt).getTotalSeconds();
        } catch (DateTimeException exception) {
            return false;
        }
    }

    private static boolean hasMinutePrecision(Instant instant) {
        return Math.floorMod(instant.getEpochSecond(), 60L) == 0
                && instant.getNano() == 0;
    }

    private static boolean hasValidParty(ReservationHold hold) {
        if (hold.getParty() == null
                || hold.getParty().getAdultCount() < 0
                || hold.getParty().getChildCount() < 0
                || hold.getParty().getInfantCount() < 0) {
            return false;
        }
        long totalCount = (long) hold.getParty().getAdultCount()
                + hold.getParty().getChildCount()
                + hold.getParty().getInfantCount();
        return totalCount > 0
                && totalCount <= Integer.MAX_VALUE
                && hold.getParty().totalCount() == (int) totalCount;
    }

    private static boolean isProtected(ReservationHoldStatus status) {
        return status == ReservationHoldStatus.ACTIVE
                || status == ReservationHoldStatus.RECONCILIATION_REQUIRED
                || status == ReservationHoldStatus.CONFIRMED;
    }

    private static String fingerprint(
            long storeId,
            LocalDate serviceDate,
            List<CapacityBucketRequest> buckets
    ) {
        StringBuilder canonical = new StringBuilder(
                "PUT|/api/v1/store-operators/stores/{storeId}"
                        + "/reservation-capacities/{serviceDate}|"
        );
        append(canonical, "storeId", Long.toString(storeId));
        append(canonical, "serviceDate", serviceDate.toString());
        for (int index = 0; index < buckets.size(); index++) {
            CapacityBucketRequest bucket = buckets.get(index);
            append(canonical, "buckets[" + index + "].startTime", bucket.startTime().toString());
            append(canonical, "buckets[" + index + "].endTime", bucket.endTime().toString());
            append(canonical, "buckets[" + index + "].maxPeople", bucket.maxPeople().toString());
            append(canonical, "buckets[" + index + "].maxTeams", bucket.maxTeams().toString());
            append(canonical, "buckets[" + index + "].minPartySize",
                    Integer.toString(bucket.minPartySize()));
            append(canonical, "buckets[" + index + "].maxPartySize",
                    Integer.toString(bucket.maxPartySize()));
            append(canonical, "buckets[" + index + "].infantsAllowed",
                    bucket.infantsAllowed().toString());
        }
        return RequestFingerprint.of(canonical.toString());
    }

    private static void append(StringBuilder target, String name, String value) {
        target.append(name)
                .append('=')
                .append(value.length())
                .append(':')
                .append(value)
                .append('|');
    }

    private static String resourceId(
            long storeId,
            LocalDate serviceDate,
            long policyVersion
    ) {
        return storeId + ":" + serviceDate + ":" + policyVersion;
    }

    private static void requireArguments(
            long operatorId,
            long storeId,
            LocalDate serviceDate,
            IdempotencyKey key,
            ReservationCapacitiesRequest request
    ) {
        if (operatorId <= 0
                || storeId <= 0
                || serviceDate == null
                || key == null
                || request == null) {
            throw new IllegalArgumentException("capacity publication arguments are required");
        }
    }

    private static ServiceException conflict() {
        return new ServiceException(
                ReservationErrorCode.CAPACITY_CONFIGURATION_CONFLICT
        );
    }

    private static ServiceException conflict(Throwable cause) {
        ServiceException conflict = conflict();
        conflict.initCause(cause);
        return conflict;
    }

    private record ResolvedBucket(
            CapacityBucketRequest request,
            Instant startAt,
            Instant endAt
    ) {
    }
}
