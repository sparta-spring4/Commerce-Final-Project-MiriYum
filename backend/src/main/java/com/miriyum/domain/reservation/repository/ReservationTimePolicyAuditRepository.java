package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationTimePolicyAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationTimePolicyAuditRepository
        extends JpaRepository<ReservationTimePolicyAudit, Long> {
}
