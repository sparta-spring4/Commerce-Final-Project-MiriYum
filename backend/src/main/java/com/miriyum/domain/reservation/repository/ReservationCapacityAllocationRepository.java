package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 예약 수용량 배정 이력의 기본 영속성 경계다.
 */
public interface ReservationCapacityAllocationRepository
        extends JpaRepository<ReservationCapacityAllocation, Long> {

    /**
     * Returns the immutable original allocation history for one reservation in bucket PK order.
     *
     * @param reservationId reservation aggregate ID
     * @return allocation rows ordered by their capacity bucket ID
     */
    List<ReservationCapacityAllocation> findAllByReservationIdOrderByCapacityBucketIdAsc(
            Long reservationId
    );

    @Query(value = """
            SELECT
                COALESCE(SUM(a.occupied_people), 0) AS reservedPeopleUnits,
                COALESCE(SUM(a.occupied_teams), 0) AS reservedTeamUnits,
                COALESCE((
                    SELECT MAX(watermark.reservation_capacity_allocation_id)
                    FROM reservation_capacity_allocations watermark
                    JOIN reservations watermark_reservation
                      ON watermark_reservation.reservation_id = watermark.reservation_id
                    WHERE watermark_reservation.store_id = :storeId
                      AND watermark_reservation.service_date = :businessDate
                      AND watermark_reservation.created_at <= :asOf
                ), 0) AS allocationHighWatermark
            FROM reservation_capacity_allocations a
            JOIN reservations r ON r.reservation_id = a.reservation_id
            WHERE r.store_id = :storeId
              AND r.service_date = :businessDate
              AND r.created_at <= :asOf
              AND (r.cancelled_at IS NULL OR r.cancelled_at > :asOf)
            """, nativeQuery = true)
    ReservationCapacityUsageAnalytics aggregateDashboardUsage(
            @Param("storeId") long storeId,
            @Param("businessDate") LocalDate businessDate,
            @Param("asOf") Instant asOf
    );

    interface ReservationCapacityUsageAnalytics {
        Long getReservedPeopleUnits();
        Long getReservedTeamUnits();
        Long getAllocationHighWatermark();
    }
}
