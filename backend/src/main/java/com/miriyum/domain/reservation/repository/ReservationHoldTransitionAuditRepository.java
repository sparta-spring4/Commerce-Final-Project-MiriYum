package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationHoldTransitionAudit;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** 임시 선점 상태 전이 감사의 append-only 조회·저장 경계다. */
public interface ReservationHoldTransitionAuditRepository
        extends Repository<ReservationHoldTransitionAudit, Long> {

    ReservationHoldTransitionAudit save(ReservationHoldTransitionAudit audit);

    /**
     * 전역 고유 operation ID의 기존 전이 결과를 replay 판정용으로 조회한다.
     *
     * @param commandId 상위 서버 조정자가 생성한 operation ID
     * @return 기존 전이 감사 또는 빈 결과
     */
    Optional<ReservationHoldTransitionAudit> findByCommandId(String commandId);

    List<ReservationHoldTransitionAudit> findAllByReservationHoldIdOrderByIdAsc(
            Long reservationHoldId
    );
}
