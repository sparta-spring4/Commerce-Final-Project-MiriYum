package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.request.ReservationTimeRequest;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import com.miriyum.domain.store.schedule.dto.StoreReservationWindowResult;
import com.miriyum.domain.store.schedule.dto.StoreReservationWindowStatus;
import com.miriyum.domain.store.schedule.service.StoreScheduleService;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일반 예약 유스케이스와 Reservation 소유 시간 정책 계산을 조정하는 주 Service다.
 */
@Service
public class ReservationService {

    private final StoreScheduleService storeScheduleService;
    private final ReservationTimePolicyVersionRepository timePolicyRepository;
    private final Clock clock;

    public ReservationService(
            StoreScheduleService storeScheduleService,
            ReservationTimePolicyVersionRepository timePolicyRepository,
            Clock clock
    ) {
        this.storeScheduleService = storeScheduleService;
        this.timePolicyRepository = timePolicyRepository;
        this.clock = clock;
    }

    /**
     * Store의 시작 접수 window와 Reservation의 현재 시간 정책으로 매장별 실제 종료를 계산한다.
     *
     * <p>결과는 입력 순서·개수·중복을 보존한다. Store의 {@code windowEndAt}은 시작 접수
     * 상한일 뿐이므로 서비스 또는 점유 종료 계산에 사용하지 않는다. 이 결과만으로 전체 서비스
     * 구간의 Store 일정 충돌이나 최종 수용량 가용성이 확인되는 것은 아니다.</p>
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
                timePolicyRepository.findEffectiveActiveByStoreIds(
                        acceptingStoreIds,
                        ReservationTimePolicyStatus.ACTIVE,
                        evaluatedAt
                )) {
            policiesByStore.computeIfAbsent(policy.getStoreId(), ignored -> new ArrayList<>())
                    .add(policy);
        }

        LocalDateTime requestedAt = LocalDateTime.of(
                request.serviceDate(),
                request.startTime()
        );
        List<ReservationTimeResolutionResult> results = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            long storeId = candidates.get(index);
            StoreReservationWindowResult window = windows.get(index);
            ReservationTimePolicyVersion policy = singleEffectivePolicy(
                    policiesByStore.get(storeId),
                    storeId,
                    evaluatedAt
            );
            results.add(resolveTime(
                    storeId,
                    window,
                    policy,
                    requestedAt,
                    request
            ));
        }
        return List.copyOf(results);
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
            return ReservationTimeResolutionResult.resolved(storeId, snapshot);
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
}
