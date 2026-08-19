package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationHoldTransitionAudit;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

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

    List<ReservationHoldTransitionAudit>
            findAllByReservationHoldIdInOrderByReservationHoldIdAscIdAsc(
                    List<Long> reservationHoldIds);

    @Query("""
            select audit
              from ReservationHoldTransitionAudit audit, ReservationHold hold
             where hold.id = audit.reservationHoldId
               and audit.occurredAt between :changedFrom and :changedTo
               and (:storeId is null or hold.storeId = :storeId)
               and (:allStatuses = true or audit.afterStatus in :statuses)
               and (
                    :afterChangedAt is null
                    or audit.occurredAt < :afterChangedAt
                    or (audit.occurredAt = :afterChangedAt
                        and concat('reservation-hold:',
                                   cast(audit.reservationHoldId as string)) < :afterCaseId)
               )
               and not exists (
                    select newer.id
                      from ReservationHoldTransitionAudit newer
                     where newer.reservationHoldId = audit.reservationHoldId
                       and newer.occurredAt between :changedFrom and :changedTo
                       and (:allStatuses = true or newer.afterStatus in :statuses)
                       and (newer.occurredAt > audit.occurredAt
                            or (newer.occurredAt = audit.occurredAt and newer.id > audit.id))
               )
             order by audit.occurredAt desc,
                      concat('reservation-hold:',
                             cast(audit.reservationHoldId as string)) desc,
                      audit.id desc
            """)
    List<ReservationHoldTransitionAudit> findMonitoringChanges(
            @Param("changedFrom") Instant changedFrom,
            @Param("changedTo") Instant changedTo,
            @Param("storeId") Long storeId,
            @Param("allStatuses") boolean allStatuses,
            @Param("statuses") Set<ReservationHoldStatus> statuses,
            @Param("afterChangedAt") Instant afterChangedAt,
            @Param("afterCaseId") String afterCaseId,
            Pageable pageable);

    /**
     * RECONCILIATION_REQUIRED 전이 후 경계 시각까지 현재도 보호 중인 선점 수를 센다.
     *
     * @param boundary 포함되는 장기 체류 전이 발생 시각 상한
     * @param status 현재 상태와 감사 목표가 모두 일치해야 하는 보호 상태
     * @return 현재 장기 체류 선점 수
     */
    @Query("""
            select count(distinct hold.id)
            from ReservationHoldTransitionAudit audit, ReservationHold hold
            where hold.id = audit.reservationHoldId
              and hold.status = :status
              and audit.afterStatus = :status
              and audit.occurredAt <= :boundary
            """)
    long countCurrentReconciliationRequiredAtOrBefore(
            @Param("boundary") Instant boundary,
            @Param("status") ReservationHoldStatus status
    );
}
