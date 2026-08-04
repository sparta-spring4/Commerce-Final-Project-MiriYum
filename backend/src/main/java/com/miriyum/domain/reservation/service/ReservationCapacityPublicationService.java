package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.request.CapacityBucketRequest;
import com.miriyum.domain.reservation.dto.request.ReservationCapacitiesRequest;
import com.miriyum.domain.reservation.dto.response.ReservationCapacitiesResponse;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.core.service.StoreScheduleAuthority;
import com.miriyum.domain.store.core.service.StoreService;
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
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
    private final StoreScheduleService storeScheduleService;
    private final StoreServiceIntervalValidationService intervalValidationService;
    private final ReservationCapacityPolicy capacityPolicy;
    private final ReservationCapacityBucketRepository capacityBucketRepository;
    private final ReservationCapacityAllocationRepository capacityAllocationRepository;
    private final ReservationRepository reservationRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;

    public ReservationCapacityPublicationService(
            StoreService storeService,
            StoreScheduleService storeScheduleService,
            StoreServiceIntervalValidationService intervalValidationService,
            ReservationCapacityPolicy capacityPolicy,
            ReservationCapacityBucketRepository capacityBucketRepository,
            ReservationCapacityAllocationRepository capacityAllocationRepository,
            ReservationRepository reservationRepository,
            IdempotencyExecutor idempotencyExecutor,
            ObjectMapper objectMapper
    ) {
        this.storeService = storeService;
        this.storeScheduleService = storeScheduleService;
        this.intervalValidationService = intervalValidationService;
        this.capacityPolicy = capacityPolicy;
        this.capacityBucketRepository = capacityBucketRepository;
        this.capacityAllocationRepository = capacityAllocationRepository;
        this.reservationRepository = reservationRepository;
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
                    confirmed
            );
            List<ReservationCapacityBucket> saved =
                    capacityBucketRepository.saveAllAndFlush(next);
            capacityAllocationRepository.saveAllAndFlush(createAllocations(
                    saved,
                    resolved,
                    confirmed,
                    nextVersion
            ));
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

        requireCompleteRepresentedReservationWindows(
                storeId,
                serviceDate,
                authority.timeZoneId(),
                buckets
        );

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

    private void requireCompleteRepresentedReservationWindows(
            long storeId,
            LocalDate serviceDate,
            String timeZoneId,
            List<CapacityBucketRequest> buckets
    ) {
        int bucketIndex = 0;
        while (bucketIndex < buckets.size()) {
            CapacityBucketRequest first = buckets.get(bucketIndex);
            StoreReservationWindowResult window = singleWindow(
                    storeScheduleService.resolveReservationWindows(
                            List.of(storeId),
                            serviceDate,
                            first.startTime()
                    ),
                    storeId,
                    timeZoneId
            );
            LocalDateTime windowStart = window.windowStartAt();
            LocalDateTime windowEnd = window.windowEndAt();
            LocalDateTime cursor = LocalDateTime.of(serviceDate, first.startTime());
            if (!windowStart.equals(cursor)
                    || !windowStart.toLocalDate().equals(serviceDate)
                    || !windowEnd.toLocalDate().equals(serviceDate)) {
                throw conflict();
            }

            while (bucketIndex < buckets.size() && cursor.isBefore(windowEnd)) {
                CapacityBucketRequest bucket = buckets.get(bucketIndex);
                LocalDateTime bucketStart = LocalDateTime.of(
                        serviceDate,
                        bucket.startTime()
                );
                LocalDateTime bucketEnd = LocalDateTime.of(
                        serviceDate,
                        bucket.endTime()
                );
                if (!bucketStart.equals(cursor)
                        || bucketEnd.isAfter(windowEnd)) {
                    throw conflict();
                }
                cursor = bucketEnd;
                bucketIndex++;
            }
            if (!cursor.equals(windowEnd)) {
                throw conflict();
            }
        }
    }

    private static StoreReservationWindowResult singleWindow(
            List<StoreReservationWindowResult> windows,
            long storeId,
            String timeZoneId
    ) {
        if (windows == null || windows.size() != 1) {
            throw conflict();
        }
        StoreReservationWindowResult window = windows.getFirst();
        if (window == null
                || window.storeId() != storeId
                || window.status() != StoreReservationWindowStatus.ACCEPTING
                || !timeZoneId.equals(window.timeZoneId())) {
            throw conflict();
        }
        return window;
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
            List<Reservation> confirmed
    ) {
        if (confirmed == null) {
            throw conflict();
        }
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

    private static List<ReservationCapacityAllocation> createAllocations(
            List<ReservationCapacityBucket> savedBuckets,
            List<ResolvedBucket> resolvedBuckets,
            List<Reservation> confirmed,
            long policyVersion
    ) {
        if (savedBuckets == null
                || savedBuckets.size() != resolvedBuckets.size()
                || savedBuckets.stream().anyMatch(bucket -> bucket.getId() == null)) {
            throw conflict();
        }
        List<ReservationCapacityAllocation> allocations = new ArrayList<>();
        for (int index = 0; index < savedBuckets.size(); index++) {
            ReservationCapacityBucket bucket = savedBuckets.get(index);
            ResolvedBucket resolved = resolvedBuckets.get(index);
            if (!bucket.getStartTime().equals(resolved.request().startTime())
                    || !bucket.getEndTime().equals(resolved.request().endTime())) {
                throw conflict();
            }
            for (Reservation reservation : confirmed) {
                if (reservation.getStartAt().isBefore(resolved.endAt())
                        && reservation.getOccupancyEndAt().isAfter(resolved.startAt())) {
                    allocations.add(ReservationCapacityAllocation.allocate(
                            reservation.getId(),
                            bucket.getId(),
                            reservation.getParty().totalCount(),
                            policyVersion
                    ));
                }
            }
        }
        return List.copyOf(allocations);
    }

    private static String fingerprint(
            long storeId,
            LocalDate serviceDate,
            List<CapacityBucketRequest> buckets
    ) {
        StringBuilder canonical = new StringBuilder(
                "PUT|/api/v1/store-operator/stores/{storeId}"
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
