package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationCancellationAudit;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationCancellationAuditRepository
        extends JpaRepository<ReservationCancellationAudit, Long> {

    Optional<ReservationCancellationAudit> findByReservationId(Long reservationId);
}
