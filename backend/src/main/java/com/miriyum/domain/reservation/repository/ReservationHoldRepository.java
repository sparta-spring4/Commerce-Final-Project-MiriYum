package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationHold;
import org.springframework.data.jpa.repository.JpaRepository;

/** 임시 선점 루트의 최소 영속성 경계다. */
public interface ReservationHoldRepository extends JpaRepository<ReservationHold, Long> {
}
