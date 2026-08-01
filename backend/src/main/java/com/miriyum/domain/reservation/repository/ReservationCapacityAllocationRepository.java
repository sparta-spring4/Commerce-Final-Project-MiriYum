package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 예약 수용량 배정 이력의 기본 영속성 경계다.
 */
public interface ReservationCapacityAllocationRepository
        extends JpaRepository<ReservationCapacityAllocation, Long> {
}
