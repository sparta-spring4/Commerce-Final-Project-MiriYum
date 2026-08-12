package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationFulfillmentAudit;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationFulfillmentAuditRepository
        extends JpaRepository<ReservationFulfillmentAudit, Long> {

    Optional<ReservationFulfillmentAudit> findByReservationId(Long reservationId);
}
