package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationNoShowAudit;
import org.springframework.data.repository.Repository;

/** 예약별 단일 NO_SHOW append-only 감사의 저장 경계다. */
public interface ReservationNoShowAuditRepository extends Repository<ReservationNoShowAudit, Long> {

    ReservationNoShowAudit save(ReservationNoShowAudit audit);
}
