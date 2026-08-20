package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationFulfillmentAudit;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationFulfillmentAuditRepository
        extends JpaRepository<ReservationFulfillmentAudit, Long> {

    Optional<ReservationFulfillmentAudit> findByReservationId(Long reservationId);

    List<ReservationFulfillmentAudit> findAllByReservationIdInOrderByOccurredAtAscIdAsc(
            List<Long> reservationIds);

    @Query(value = """
            SELECT
                COALESCE(MAX(a.reservation_fulfillment_audit_id), 0) AS maxAuditId,
                CAST(UNIX_TIMESTAMP(MAX(a.occurred_at)) * 1000000 AS SIGNED)
                    AS dataThroughEpochMicros
            FROM reservation_fulfillment_audits a
            JOIN reservations r ON r.reservation_id = a.reservation_id
            WHERE r.store_id = :storeId
              AND r.service_date = :businessDate
              AND a.occurred_at <= :asOf
            """, nativeQuery = true)
    ReservationFulfillmentAnalytics aggregateDashboardFulfillments(
            @Param("storeId") long storeId,
            @Param("businessDate") LocalDate businessDate,
            @Param("asOf") Instant asOf
    );

    interface ReservationFulfillmentAnalytics {
        Long getMaxAuditId();
        Long getDataThroughEpochMicros();
    }
}
