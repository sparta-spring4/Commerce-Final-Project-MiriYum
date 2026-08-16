package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationDepositProcess;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for Reservation-owned deposit orchestration state. */
public interface ReservationDepositProcessRepository
        extends JpaRepository<ReservationDepositProcess, Long> {

    Optional<ReservationDepositProcess> findByIdAndConsumerAccountId(
            long processId,
            long consumerAccountId);

    @Query("select process.id from ReservationDepositProcess process "
            + "where process.reservationHoldId = :reservationHoldId")
    Optional<Long> findProcessIdByReservationHoldId(
            @Param("reservationHoldId") long reservationHoldId);

    @Query("""
            select process.reservationHoldId from ReservationDepositProcess process
            where process.finalReservationId in :finalReservationIds
              and process.reservationHoldId in :reservationHoldIds
            """)
    List<Long> findLinkedHoldIdsForFinalReservations(
            @Param("finalReservationIds") List<Long> finalReservationIds,
            @Param("reservationHoldIds") List<Long> reservationHoldIds);

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
