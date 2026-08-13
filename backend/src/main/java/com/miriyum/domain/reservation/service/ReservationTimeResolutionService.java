package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.request.ReservationTimeRequest;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionStatus;
import com.miriyum.domain.reservation.dto.response.ResolvedReservationTime;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import com.miriyum.domain.schedule.dto.contract.StoreReservationWindowResult;
import com.miriyum.domain.schedule.dto.contract.StoreReservationWindowStatus;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalRequest;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalResult;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalStatus;
import com.miriyum.domain.schedule.service.StoreScheduleService;
import com.miriyum.domain.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 매장 일정과 예약 시간 정책으로 실제 서비스 구간을 계산하는 좁은 조회 서비스다. */
@Service
@RequiredArgsConstructor
public class ReservationTimeResolutionService {

    private final StoreScheduleService storeScheduleService;
    private final StoreServiceIntervalValidationService storeServiceIntervalValidationService;
    private final ReservationTimePolicyVersionRepository timePolicyRepository;
    private final Clock clock;

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
                        candidates, request.serviceDate(), request.startTime());
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
                        evaluatedAt)) {
            policiesByStore.computeIfAbsent(policy.getStoreId(), ignored -> new ArrayList<>())
                    .add(policy);
        }

        LocalDateTime requestedAt = LocalDateTime.of(
                request.serviceDate(), request.startTime());
        List<ReservationTimeResolutionResult> provisionalResults =
                new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            long storeId = candidates.get(index);
            provisionalResults.add(resolveTime(
                    storeId,
                    windows.get(index),
                    singleEffectivePolicy(
                            policiesByStore.get(storeId), storeId, evaluatedAt),
                    requestedAt,
                    request));
        }

        List<StoreServiceIntervalRequest> intervalRequests = provisionalResults.stream()
                .filter(result -> result.status() == ReservationTimeResolutionStatus.RESOLVED)
                .map(result -> new StoreServiceIntervalRequest(
                        result.storeId(),
                        result.time().startAt(),
                        result.time().serviceEndAt()))
                .toList();
        if (intervalRequests.isEmpty()) {
            return List.copyOf(provisionalResults);
        }

        List<StoreServiceIntervalResult> intervalResults =
                storeServiceIntervalValidationService.validateServiceIntervals(intervalRequests);
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
                            provisionalResult.storeId()));
        }
        return List.copyOf(results);
    }

    ReservationTimeSnapshot resolveCreationTime(long storeId, ReservationTimeRequest request) {
        List<StoreReservationWindowResult> windows =
                storeScheduleService.resolveReservationWindows(
                        List.of(storeId), request.serviceDate(), request.startTime());
        if (windows == null || windows.size() != 1) {
            throw outsideReservationWindow();
        }
        StoreReservationWindowResult window = windows.getFirst();
        if (window == null
                || window.storeId() != storeId
                || window.status() != StoreReservationWindowStatus.ACCEPTING) {
            throw outsideReservationWindow();
        }

        Instant evaluatedAt = clock.instant();
        List<ReservationTimePolicyVersion> policies =
                timePolicyRepository.findResolutionCandidatesByStoreIds(
                        Set.of(storeId),
                        ReservationTimePolicyStatus.ACTIVE,
                        ReservationTimePolicyStatus.SCHEDULED,
                        evaluatedAt);
        ReservationTimePolicyVersion policy = singleEffectivePolicy(
                policies, storeId, evaluatedAt);
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
                storeId, snapshot.getStartAt(), snapshot.getServiceEndAt());
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

    private static ServiceException outsideReservationWindow() {
        return new ServiceException(ReservationErrorCode.OUTSIDE_RESERVATION_WINDOW);
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
                    request.startOffset());
            return ReservationTimeResolutionResult.resolved(
                    storeId, ResolvedReservationTime.from(snapshot));
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
}
