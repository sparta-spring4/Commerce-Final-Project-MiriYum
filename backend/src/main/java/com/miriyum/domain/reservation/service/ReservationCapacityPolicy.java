package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.request.CapacityBucketRequest;
import com.miriyum.domain.reservation.dto.request.ReservationCapacitiesRequest;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 날짜별 수용량 전체 게시 요청의 예약 도메인 불변식을 검증한다.
 */
@Component
public class ReservationCapacityPolicy {

    private static final int MAX_BUCKET_COUNT = 96;
    private static final int MAX_CAPACITY = 10_000;
    private static final int MAX_PARTY_SIZE = 100;
    private static final Comparator<CapacityBucketRequest> BUCKET_ORDER =
            Comparator.comparing(CapacityBucketRequest::startTime)
                    .thenComparing(CapacityBucketRequest::endTime);

    /**
     * 버킷별 수용량과 시간 구간을 검증하고 결정적인 시작 시각 순서로 반환한다.
     *
     * <p>활성 영업·예약 접수 구간의 누락 여부는 Store 도메인의 날짜별 공개 계약을 함께 받은
     * 게시 Service가 추가 검증해야 한다.</p>
     *
     * @param request 날짜별 전체 수용량 게시 요청
     * @return 검증되고 시간순으로 정렬된 불변 목록
     * @throws ServiceException 시간·수용량·일행 범위 또는 구간 겹침이 충돌하는 경우
     */
    public List<CapacityBucketRequest> validateAndSort(
            ReservationCapacitiesRequest request
    ) {
        if (request == null || request.buckets() == null) {
            throw conflict();
        }
        if (request.buckets().isEmpty()
                || request.buckets().size() > MAX_BUCKET_COUNT
                || request.buckets().stream().anyMatch(java.util.Objects::isNull)) {
            throw conflict();
        }

        List<CapacityBucketRequest> sorted = new ArrayList<>(request.buckets());
        sorted.forEach(ReservationCapacityPolicy::validateBucket);
        sorted.sort(BUCKET_ORDER);
        for (int index = 1; index < sorted.size(); index++) {
            CapacityBucketRequest previous = sorted.get(index - 1);
            CapacityBucketRequest current = sorted.get(index);
            if (current.startTime().isBefore(previous.endTime())) {
                throw conflict();
            }
        }
        return List.copyOf(sorted);
    }

    private static void validateBucket(CapacityBucketRequest bucket) {
        if (bucket.startTime() == null
                || bucket.endTime() == null
                || !bucket.startTime().isBefore(bucket.endTime())) {
            throw conflict();
        }
        if (bucket.maxPeople() == null
                || bucket.maxTeams() == null
                || bucket.maxPeople() < 0
                || bucket.maxPeople() > MAX_CAPACITY
                || bucket.maxTeams() < 0
                || bucket.maxTeams() > MAX_CAPACITY) {
            throw conflict();
        }
        if (bucket.minPartySize() < 1
                || bucket.minPartySize() > MAX_PARTY_SIZE
                || bucket.maxPartySize() < bucket.minPartySize()
                || bucket.maxPartySize() > MAX_PARTY_SIZE
                || bucket.maxPartySize() > bucket.maxPeople()
                || bucket.infantsAllowed() == null) {
            throw conflict();
        }
    }

    private static ServiceException conflict() {
        return new ServiceException(
                ReservationErrorCode.CAPACITY_CONFIGURATION_CONFLICT
        );
    }
}
