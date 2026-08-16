package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationDepositRefundObligation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for durable deposit refund obligations. */
public interface ReservationDepositRefundObligationRepository
        extends JpaRepository<ReservationDepositRefundObligation, Long> {

    Optional<ReservationDepositRefundObligation>
            findByReservationDepositProcessIdAndPaymentIdAndReasonCode(
                    long processId,
                    String paymentId,
                    String reasonCode);
}
