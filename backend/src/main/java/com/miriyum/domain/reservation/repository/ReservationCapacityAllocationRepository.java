package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 예약 수용량 배정 이력의 기본 영속성 경계다.
 */
public interface ReservationCapacityAllocationRepository
        extends JpaRepository<ReservationCapacityAllocation, Long> {

    /**
     * Returns the immutable original allocation history for one reservation in bucket PK order.
     *
     * @param reservationId reservation aggregate ID
     * @return allocation rows ordered by their capacity bucket ID
     */
    List<ReservationCapacityAllocation> findAllByReservationIdOrderByCapacityBucketIdAsc(
            Long reservationId
    );
}
