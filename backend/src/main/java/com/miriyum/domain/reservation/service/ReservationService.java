package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.request.ReservationAvailabilityCondition;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일반 예약 유스케이스와 예약 수용량 판정을 소유하는 주 Service다.
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final Comparator<ReservationCapacityBucket> BUCKET_ORDER =
            Comparator.comparing(ReservationCapacityBucket::getStartTime)
                    .thenComparing(ReservationCapacityBucket::getEndTime)
                    .thenComparingLong(ReservationCapacityBucket::getPolicyVersion);

    private final ReservationCapacityBucketRepository capacityBucketRepository;

    /**
     * 매장 한 곳의 현재 예약 가능 여부를 판정한다.
     *
     * @param storeId 대상 매장 ID
     * @param condition 서버가 확정한 날짜·점유 구간·일행 조건
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
     * <p>결과는 입력 매장 순서를 그대로 보존한다. 이 조회 결과는 예약 생성 성공을 보장하지 않으며
     * 생성 트랜잭션에서 현재 정책과 점유량을 다시 검증해야 한다.</p>
     *
     * @param storeIds 판정 대상 매장 ID 목록
     * @param condition 모든 대상에 공통으로 적용할 날짜·점유 구간·일행 조건
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

        List<ReservationCapacityBucket> buckets =
                capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                        candidateStoreIds,
                        condition.serviceDate(),
                        condition.startTime(),
                        condition.endTime()
                );
        Map<Long, List<ReservationCapacityBucket>> bucketsByStore =
                groupBucketsByStore(buckets);

        return candidateStoreIds.stream()
                .map(storeId -> new ReservationAvailabilityResult(
                        storeId,
                        availabilityOf(bucketsByStore.get(storeId), condition)
                ))
                .toList();
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
            ReservationAvailabilityCondition condition
    ) {
        if (buckets == null || buckets.isEmpty()) {
            return ReservationAvailabilityStatus.UNAVAILABLE;
        }

        LocalTime cursor = condition.startTime();
        long policyVersion = buckets.getFirst().getPolicyVersion();
        for (ReservationCapacityBucket bucket : buckets) {
            if (bucket.getPolicyVersion() != policyVersion) {
                return ReservationAvailabilityStatus.UNAVAILABLE;
            }
            LocalTime coveredStart = laterOf(bucket.getStartTime(), condition.startTime());
            LocalTime coveredEnd = earlierOf(bucket.getEndTime(), condition.endTime());
            if (!coveredStart.equals(cursor) || !coveredEnd.isAfter(coveredStart)) {
                return ReservationAvailabilityStatus.UNAVAILABLE;
            }
            if (!bucket.canAccept(condition.partySize(), condition.includesInfants())) {
                return ReservationAvailabilityStatus.UNAVAILABLE;
            }
            cursor = coveredEnd;
        }

        return cursor.equals(condition.endTime())
                ? ReservationAvailabilityStatus.AVAILABLE
                : ReservationAvailabilityStatus.UNAVAILABLE;
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
}
