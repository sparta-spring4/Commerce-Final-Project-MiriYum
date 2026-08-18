package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationDepositDispositionObligation;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for Reservation-owned deposit disposition obligations. */
public interface ReservationDepositDispositionObligationRepository
        extends JpaRepository<ReservationDepositDispositionObligation, Long> {

    Optional<ReservationDepositDispositionObligation> findByPaymentIdAndSourceEventId(
            String paymentId,
            String sourceEventId);

    Optional<ReservationDepositDispositionObligation>
            findFirstByReservationIdOrderByIdDesc(long reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select obligation from ReservationDepositDispositionObligation obligation
            where ((obligation.status = com.miriyum.domain.reservation.entity.ReservationDepositDispositionObligation.Status.PENDING
                    or obligation.status = com.miriyum.domain.reservation.entity.ReservationDepositDispositionObligation.Status.RECONCILIATION_REQUIRED)
                   and obligation.nextAttemptAt <= :now)
               or (obligation.status = com.miriyum.domain.reservation.entity.ReservationDepositDispositionObligation.Status.PROCESSING
                   and obligation.leaseUntil <= :now)
            order by obligation.id asc
            """)
    List<ReservationDepositDispositionObligation> findClaimableForUpdate(
            @Param("now") Instant now,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select obligation from ReservationDepositDispositionObligation obligation
            where obligation.id = :obligationId
            """)
    Optional<ReservationDepositDispositionObligation> findByIdForUpdate(
            @Param("obligationId") long obligationId);
}
