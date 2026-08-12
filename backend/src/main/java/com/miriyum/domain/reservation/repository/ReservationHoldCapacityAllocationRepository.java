package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationHoldCapacityAllocation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 임시 선점의 구간별 수용량 배정 스냅샷 경계다. */
public interface ReservationHoldCapacityAllocationRepository
        extends JpaRepository<ReservationHoldCapacityAllocation, Long> {

    List<ReservationHoldCapacityAllocation>
            findAllByReservationHoldIdOrderByCapacityBucketIdAsc(Long reservationHoldId);
}
