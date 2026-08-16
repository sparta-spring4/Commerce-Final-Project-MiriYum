package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationDepositProcess;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for Reservation-owned deposit orchestration state. */
public interface ReservationDepositProcessRepository
        extends JpaRepository<ReservationDepositProcess, Long> {
}
