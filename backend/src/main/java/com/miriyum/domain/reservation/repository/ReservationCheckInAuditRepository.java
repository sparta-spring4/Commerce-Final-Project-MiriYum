package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationCheckInAudit;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** QR 발급·완료 append-only 감사의 저장 경계다. */
public interface ReservationCheckInAuditRepository extends Repository<ReservationCheckInAudit, Long> {

    ReservationCheckInAudit save(ReservationCheckInAudit audit);

    @Query(value = """
            SELECT audit.*
              FROM reservation_check_in_audits audit
              LEFT JOIN reservation_deposit_processes process
                ON process.final_reservation_id = audit.reservation_id
             WHERE audit.occurred_at BETWEEN :changedFrom AND :changedTo
               AND (:storeId IS NULL OR audit.store_id = :storeId)
               AND (:allStatuses = TRUE
                    OR FIND_IN_SET(audit.after_status, :statusesCsv) > 0)
               AND (
                    :afterChangedAt IS NULL
                    OR audit.occurred_at < :afterChangedAt
                    OR (
                        audit.occurred_at = :afterChangedAt
                        AND COALESCE(
                            CONCAT('reservation-hold:', process.reservation_hold_id),
                            CONCAT('reservation:', audit.reservation_id)
                        ) < :afterCaseId
                    )
               )
               AND NOT EXISTS (
                    SELECT 1
                      FROM reservation_check_in_audits newer
                     WHERE newer.reservation_id = audit.reservation_id
                       AND newer.occurred_at BETWEEN :changedFrom AND :changedTo
                       AND (:allStatuses = TRUE
                            OR FIND_IN_SET(newer.after_status, :statusesCsv) > 0)
                       AND (newer.occurred_at > audit.occurred_at
                            OR (newer.occurred_at = audit.occurred_at
                                AND newer.reservation_check_in_audit_id
                                    > audit.reservation_check_in_audit_id))
               )
             ORDER BY audit.occurred_at DESC,
                      COALESCE(
                          CONCAT('reservation-hold:', process.reservation_hold_id),
                          CONCAT('reservation:', audit.reservation_id)
                      ) DESC,
                      audit.reservation_check_in_audit_id DESC
            """, nativeQuery = true)
    List<ReservationCheckInAudit> findMonitoringChanges(
            @Param("changedFrom") Instant changedFrom,
            @Param("changedTo") Instant changedTo,
            @Param("storeId") Long storeId,
            @Param("allStatuses") boolean allStatuses,
            @Param("statusesCsv") String statusesCsv,
            @Param("afterChangedAt") Instant afterChangedAt,
            @Param("afterCaseId") String afterCaseId,
            Pageable pageable);

    List<ReservationCheckInAudit> findAllByReservationIdInOrderByOccurredAtAscIdAsc(
            List<Long> reservationIds);
}
