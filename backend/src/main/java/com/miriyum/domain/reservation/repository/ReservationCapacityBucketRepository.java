package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 예약 수용량 버킷의 기본 영속성 경계다.
 */
public interface ReservationCapacityBucketRepository
        extends JpaRepository<ReservationCapacityBucket, Long> {
}
