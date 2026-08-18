package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationNoShowAudit;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 예약별 단일 NO_SHOW append-only 감사의 저장 경계다. */
public interface ReservationNoShowAuditRepository extends Repository<ReservationNoShowAudit, Long> {

    ReservationNoShowAudit save(ReservationNoShowAudit audit);

    @Query(value = """
            SELECT
                COUNT(*) AS confirmedNoShowTeams,
                COALESCE(MAX(a.reservation_no_show_audit_id), 0) AS maxAuditId,
                CAST(UNIX_TIMESTAMP(MAX(a.occurred_at)) * 1000000 AS SIGNED)
                    AS dataThroughEpochMicros
            FROM reservation_no_show_audits a
            JOIN reservations r ON r.reservation_id = a.reservation_id
            WHERE a.store_id = :storeId
              AND r.store_id = :storeId
              AND r.service_date = :businessDate
              AND a.occurred_at <= :asOf
            """, nativeQuery = true)
    ReservationNoShowAnalytics aggregateDashboardNoShows(
            @Param("storeId") long storeId,
            @Param("businessDate") LocalDate businessDate,
            @Param("asOf") Instant asOf
    );

    interface ReservationNoShowAnalytics {
        Long getConfirmedNoShowTeams();
        Long getMaxAuditId();
        Long getDataThroughEpochMicros();
    }
}
