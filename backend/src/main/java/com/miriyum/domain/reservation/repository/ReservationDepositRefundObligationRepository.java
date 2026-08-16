package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationDepositRefundObligation;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for durable deposit refund obligations. */
public interface ReservationDepositRefundObligationRepository
        extends JpaRepository<ReservationDepositRefundObligation, Long> {

    Optional<ReservationDepositRefundObligation>
            findByReservationDepositProcessIdAndPaymentIdAndReasonCode(
                    long processId,
                    String paymentId,
                    String reasonCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select obligation from ReservationDepositRefundObligation obligation
            where (obligation.status = com.miriyum.domain.reservation.entity.ReservationDepositRefundObligation.Status.REQUIRED
                   and obligation.nextAttemptAt <= :now)
               or (obligation.status = com.miriyum.domain.reservation.entity.ReservationDepositRefundObligation.Status.PROCESSING
                   and obligation.leaseUntil <= :now)
            order by obligation.id asc
            """)
    List<ReservationDepositRefundObligation> findClaimableForUpdate(
            @Param("now") Instant now,
            Pageable pageable);
}
