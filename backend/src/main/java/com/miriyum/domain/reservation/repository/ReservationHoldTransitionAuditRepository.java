package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationHoldTransitionAudit;
import java.util.List;
import org.springframework.data.repository.Repository;

/** 임시 선점 상태 전이 감사의 append-only 조회·저장 경계다. */
public interface ReservationHoldTransitionAuditRepository
        extends Repository<ReservationHoldTransitionAudit, Long> {

    ReservationHoldTransitionAudit save(ReservationHoldTransitionAudit audit);

    List<ReservationHoldTransitionAudit> findAllByReservationHoldIdOrderByIdAsc(
            Long reservationHoldId
    );
}
