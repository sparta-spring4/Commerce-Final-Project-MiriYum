package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationDepositProcess;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for Reservation-owned deposit orchestration state. */
public interface ReservationDepositProcessRepository
        extends JpaRepository<ReservationDepositProcess, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select process from ReservationDepositProcess process
            where process.id = :processId
              and process.consumerAccountId = :consumerAccountId
            """)
    Optional<ReservationDepositProcess> findByIdAndConsumerAccountIdForUpdate(
            @Param("processId") long processId,
            @Param("consumerAccountId") long consumerAccountId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select process from ReservationDepositProcess process where process.id = :processId")
    Optional<ReservationDepositProcess> findByIdForUpdate(
            @Param("processId") long processId);
}
