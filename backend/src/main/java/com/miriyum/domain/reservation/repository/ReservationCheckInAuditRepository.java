package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationCheckInAudit;
import org.springframework.data.repository.Repository;

/** QR 발급·완료 append-only 감사의 저장 경계다. */
public interface ReservationCheckInAuditRepository extends Repository<ReservationCheckInAudit, Long> {

    ReservationCheckInAudit save(ReservationCheckInAudit audit);
}
