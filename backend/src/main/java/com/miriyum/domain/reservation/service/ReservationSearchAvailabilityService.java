package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.request.ReservationSearchAvailabilityCondition;
import com.miriyum.domain.reservation.dto.request.ReservationTimeRequest;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionStatus;
import com.miriyum.domain.reservation.dto.response.ResolvedReservationTime;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 검색 후보가 현재 예약 수용량을 만족하는지 부분 조건으로 일괄 판정한다. */
@Service
@RequiredArgsConstructor
public class ReservationSearchAvailabilityService {

    private final ReservationTimeResolutionService timeResolutionService;
    private final ReservationCapacityBucketRepository capacityBucketRepository;

    @Transactional(readOnly = true)
    public List<ReservationAvailabilityResult> getAvailabilities(
            List<Long> storeIds,
            ReservationSearchAvailabilityCondition condition
    ) {
        List<Long> candidates = validate(storeIds, condition);
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (condition.startTime() == null) {
            return getDateAvailabilities(candidates, condition);
        }

        List<ReservationTimeResolutionResult> timeResults =
                timeResolutionService.resolveReservationTimes(
                        candidates,
                        new ReservationTimeRequest(
                                condition.serviceDate(),
                                condition.startTime(),
                                condition.startOffset()
                        )
                );
        if (timeResults == null || timeResults.size() != candidates.size()) {
            return unavailableResults(candidates);
        }
        if (!matchesStores(candidates, timeResults)) {
            return unavailableResults(candidates);
        }
        if (hasMalformedResolvedTime(candidates, timeResults, condition)) {
            return unavailableResults(candidates);
        }

        CapacityWindow[] windows = new CapacityWindow[candidates.size()];
        Set<Long> queryStoreIds = new LinkedHashSet<>();
        LocalTime latestEndTime = null;
        for (int index = 0; index < candidates.size(); index++) {
            long storeId = candidates.get(index);
            CapacityWindow window = toCapacityWindow(
                    storeId,
                    timeResults.get(index),
                    condition
            );
            windows[index] = window;
            if (window != null) {
                queryStoreIds.add(storeId);
                latestEndTime = latestEndTime == null
                        ? window.endTime()
                        : laterOf(latestEndTime, window.endTime());
            }
        }
        if (queryStoreIds.isEmpty()) {
            return unavailableResults(candidates);
        }

        List<ReservationCapacityBucket> buckets =
                capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                        List.copyOf(queryStoreIds),
                        condition.serviceDate(),
                        condition.startTime(),
                        latestEndTime
                );
        if (buckets == null) {
            return unavailableResults(candidates);
        }
        if (!validBuckets(
                buckets,
                queryStoreIds,
                condition.serviceDate()
        )) {
            return unavailableResults(candidates);
        }
        Map<Long, List<ReservationCapacityBucket>> bucketsByStore =
                groupByStore(buckets);

        List<ReservationAvailabilityResult> results = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            long storeId = candidates.get(index);
            CapacityWindow window = windows[index];
            ReservationAvailabilityStatus status = window == null
                    ? ReservationAvailabilityStatus.UNAVAILABLE
                    : availabilityOf(
                            bucketsByStore.get(storeId),
                            window,
                            condition.partySize(),
                            condition.includesInfants()
                    );
            results.add(new ReservationAvailabilityResult(storeId, status));
        }
        return List.copyOf(results);
    }

    private List<ReservationAvailabilityResult> getDateAvailabilities(
            List<Long> candidates,
            ReservationSearchAvailabilityCondition condition
    ) {
        List<Long> uniqueStoreIds = List.copyOf(new LinkedHashSet<>(candidates));
        List<ReservationCapacityBucket> buckets =
                capacityBucketRepository.findLatestPolicyBuckets(
                        uniqueStoreIds,
                        condition.serviceDate()
                );
        if (!validDateBuckets(buckets, uniqueStoreIds, condition)) {
            return unavailableResults(candidates);
        }
        Map<Long, List<ReservationCapacityBucket>> bucketsByStore =
                groupByStore(buckets);
        Map<LocalTime, Set<Long>> storesByCandidateTime = new TreeMap<>();
        for (ReservationCapacityBucket bucket : buckets) {
            storesByCandidateTime
                    .computeIfAbsent(bucket.getStartTime(), ignored -> new LinkedHashSet<>())
                    .add(bucket.getStoreId());
        }

        Set<Long> availableStoreIds = new LinkedHashSet<>();
        for (Map.Entry<LocalTime, Set<Long>> entry : storesByCandidateTime.entrySet()) {
            List<Long> unresolvedStoreIds = entry.getValue().stream()
                    .filter(storeId -> !availableStoreIds.contains(storeId))
                    .toList();
            if (unresolvedStoreIds.isEmpty()) {
                continue;
            }
            ReservationSearchAvailabilityCondition candidateCondition =
                    new ReservationSearchAvailabilityCondition(
                            condition.serviceDate(),
                            entry.getKey(),
                            null,
                            condition.partySize(),
                            condition.includesInfants()
                    );
            List<ReservationTimeResolutionResult> timeResults =
                    timeResolutionService.resolveReservationTimes(
                            unresolvedStoreIds,
                            new ReservationTimeRequest(
                                    condition.serviceDate(),
                                    entry.getKey(),
                                    null
                            )
                    );
            if (!matchesStores(unresolvedStoreIds, timeResults)) {
                return unavailableResults(candidates);
            }
            if (hasMalformedResolvedTime(
                    unresolvedStoreIds,
                    timeResults,
                    candidateCondition
            )) {
                return unavailableResults(candidates);
            }
            for (int index = 0; index < unresolvedStoreIds.size(); index++) {
                long storeId = unresolvedStoreIds.get(index);
                CapacityWindow window = toCapacityWindow(
                        storeId,
                        timeResults.get(index),
                        candidateCondition
                );
                if (window != null
                        && availabilityOf(
                                bucketsByStore.get(storeId),
                                window,
                                condition.partySize(),
                                condition.includesInfants()
                        ) == ReservationAvailabilityStatus.AVAILABLE) {
                    availableStoreIds.add(storeId);
                }
            }
        }
        return candidates.stream()
                .map(storeId -> new ReservationAvailabilityResult(
                        storeId,
                        availableStoreIds.contains(storeId)
                                ? ReservationAvailabilityStatus.AVAILABLE
                                : ReservationAvailabilityStatus.UNAVAILABLE
                ))
                .toList();
    }

    private static boolean validDateBuckets(
            List<ReservationCapacityBucket> buckets,
            List<Long> storeIds,
            ReservationSearchAvailabilityCondition condition
    ) {
        return validBuckets(buckets, Set.copyOf(storeIds), condition.serviceDate());
    }

    private static boolean validBuckets(
            List<ReservationCapacityBucket> buckets,
            Set<Long> storeIds,
            java.time.LocalDate serviceDate
    ) {
        if (buckets == null) {
            return false;
        }
        Map<Long, Long> policyVersions = new HashMap<>();
        for (ReservationCapacityBucket bucket : buckets) {
            if (bucket == null
                    || !storeIds.contains(bucket.getStoreId())
                    || !serviceDate.equals(bucket.getServiceDate())) {
                return false;
            }
            Long previousVersion = policyVersions.putIfAbsent(
                    bucket.getStoreId(),
                    bucket.getPolicyVersion()
            );
            if (previousVersion != null
                    && previousVersion.longValue() != bucket.getPolicyVersion()) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesStores(
            List<Long> storeIds,
            List<ReservationTimeResolutionResult> results
    ) {
        if (results == null || results.size() != storeIds.size()) {
            return false;
        }
        for (int index = 0; index < storeIds.size(); index++) {
            ReservationTimeResolutionResult result = results.get(index);
            if (result == null || result.storeId() != storeIds.get(index)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasMalformedResolvedTime(
            List<Long> storeIds,
            List<ReservationTimeResolutionResult> results,
            ReservationSearchAvailabilityCondition condition
    ) {
        for (int index = 0; index < storeIds.size(); index++) {
            ReservationTimeResolutionResult result = results.get(index);
            if (result.status() == ReservationTimeResolutionStatus.RESOLVED
                    && toCapacityWindow(
                            storeIds.get(index),
                            result,
                            condition
                    ) == null) {
                return true;
            }
        }
        return false;
    }

    private static List<Long> validate(
            List<Long> storeIds,
            ReservationSearchAvailabilityCondition condition
    ) {
        if (storeIds == null || condition == null) {
            throw new IllegalArgumentException("storeIds and condition are required");
        }
        if (storeIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("storeIds must be positive");
        }
        return List.copyOf(storeIds);
    }

    private static Map<Long, List<ReservationCapacityBucket>> groupByStore(
            List<ReservationCapacityBucket> buckets
    ) {
        Map<Long, List<ReservationCapacityBucket>> grouped = new HashMap<>();
        for (ReservationCapacityBucket bucket : buckets) {
            if (bucket == null) {
                return Map.of();
            }
            grouped.computeIfAbsent(bucket.getStoreId(), ignored -> new ArrayList<>())
                    .add(bucket);
        }
        grouped.values().forEach(storeBuckets -> storeBuckets.sort(
                java.util.Comparator.comparing(ReservationCapacityBucket::getStartTime)
                        .thenComparing(ReservationCapacityBucket::getEndTime)
                        .thenComparing(ReservationCapacityBucket::getId,
                                java.util.Comparator.nullsLast(Long::compareTo))
        ));
        return grouped;
    }

    private static ReservationAvailabilityStatus availabilityOf(
            List<ReservationCapacityBucket> buckets,
            CapacityWindow window,
            Integer partySize,
            boolean includesInfants
    ) {
        if (buckets == null || buckets.isEmpty()) {
            return ReservationAvailabilityStatus.UNAVAILABLE;
        }
        LocalTime cursor = window.startTime();
        Long policyVersion = null;
        int minimumPartySize = 1;
        int maximumPartySize = 100;
        for (ReservationCapacityBucket bucket : buckets) {
            if (!bucket.getStartTime().isBefore(window.endTime())
                    || !bucket.getEndTime().isAfter(window.startTime())) {
                continue;
            }
            if (policyVersion == null) {
                policyVersion = bucket.getPolicyVersion();
            } else if (bucket.getPolicyVersion() != policyVersion) {
                return ReservationAvailabilityStatus.UNAVAILABLE;
            }
            LocalTime coveredStart = laterOf(bucket.getStartTime(), window.startTime());
            LocalTime coveredEnd = earlierOf(bucket.getEndTime(), window.endTime());
            if (!coveredStart.equals(cursor) || !coveredEnd.isAfter(coveredStart)) {
                return ReservationAvailabilityStatus.UNAVAILABLE;
            }
            if (partySize != null) {
                if (!bucket.canAccept(partySize, includesInfants)) {
                    return ReservationAvailabilityStatus.UNAVAILABLE;
                }
            } else {
                if (includesInfants && !bucket.isInfantsAllowed()) {
                    return ReservationAvailabilityStatus.UNAVAILABLE;
                }
                if (bucket.getOccupiedTeams() >= bucket.getMaxTeams()) {
                    return ReservationAvailabilityStatus.UNAVAILABLE;
                }
                minimumPartySize = Math.max(minimumPartySize, bucket.getMinPartySize());
                maximumPartySize = Math.min(
                        maximumPartySize,
                        Math.min(
                                bucket.getMaxPartySize(),
                                bucket.getMaxPeople() - bucket.getOccupiedPeople()
                        )
                );
            }
            cursor = coveredEnd;
        }
        boolean available = policyVersion != null
                && cursor.equals(window.endTime())
                && (partySize != null || minimumPartySize <= maximumPartySize);
        return available
                ? ReservationAvailabilityStatus.AVAILABLE
                : ReservationAvailabilityStatus.UNAVAILABLE;
    }

    private static CapacityWindow toCapacityWindow(
            long storeId,
            ReservationTimeResolutionResult result,
            ReservationSearchAvailabilityCondition condition
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
            var endOffsets = zoneId.getRules().getValidOffsets(
                    occupancyEnd.toLocalDateTime());
            if (startOffsets.size() != 1
                    || endOffsets.size() != 1
                    || !startOffsets.getFirst().equals(start.getOffset())
                    || !endOffsets.getFirst().equals(occupancyEnd.getOffset())) {
                return null;
            }
            return new CapacityWindow(start.toLocalTime(), occupancyEnd.toLocalTime());
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private static List<ReservationAvailabilityResult> unavailableResults(
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

    private record CapacityWindow(LocalTime startTime, LocalTime endTime) {
    }
}
