package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 일반 예약 aggregate의 기본 영속성 경계다.
 */
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findAllByIdIn(List<Long> reservationIds);

    @Query(value = """
            SELECT r.*
              FROM reservations r
             WHERE (:storeId IS NULL OR r.store_id = :storeId)
               AND r.created_at <= :changedTo
               AND (
                    r.created_at BETWEEN :changedFrom AND :changedTo
                    OR r.cancelled_at BETWEEN :changedFrom AND :changedTo
                    OR r.fulfilled_at BETWEEN :changedFrom AND :changedTo
                    OR r.no_show_at BETWEEN :changedFrom AND :changedTo
               )
             ORDER BY GREATEST(
                    r.created_at,
                    COALESCE(r.cancelled_at, r.created_at),
                    COALESCE(r.fulfilled_at, r.created_at),
                    COALESCE(r.no_show_at, r.created_at)) DESC,
                    r.reservation_id DESC
            """, nativeQuery = true)
    List<Reservation> findMonitoringChanges(
            @Param("changedFrom") Instant changedFrom,
            @Param("changedTo") Instant changedTo,
            @Param("storeId") Long storeId,
            Pageable pageable);

    @Query("""
            select reservation.id
            from Reservation reservation
            where reservation.storeId = :storeId
              and reservation.status = :#{T(com.miriyum.domain.reservation.entity.ReservationStatus).CONFIRMED}
              and reservation.timeSnapshot.serviceEndAt > :now
            order by reservation.id
            """)
    List<Long> findConfirmedFutureIdsByStoreId(
            @Param("storeId") long storeId,
            @Param("now") Instant now
    );

    /**
     * Locks confirmed reservations for one consumer and store whose service interval overlaps
     * the requested half-open interval. This is called after the Store serialization lock and
     * before capacity-bucket locks.
     *
     * @param consumerAccountId authenticated consumer account ID
     * @param storeId target store ID
     * @param requestedStart inclusive requested service start
     * @param requestedEnd exclusive requested service end
     * @return matching confirmed reservations in primary-key order
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select reservation
            from Reservation reservation
            where reservation.consumerAccountId = :consumerAccountId
              and reservation.storeId = :storeId
              and reservation.status = :#{T(com.miriyum.domain.reservation.entity.ReservationStatus).CONFIRMED}
              and reservation.timeSnapshot.startAt < :requestedEnd
              and reservation.timeSnapshot.serviceEndAt > :requestedStart
            order by reservation.id asc
            """)
    List<Reservation> findConfirmedOverlappingForUpdate(
            @Param("consumerAccountId") long consumerAccountId,
            @Param("storeId") long storeId,
            @Param("requestedStart") Instant requestedStart,
            @Param("requestedEnd") Instant requestedEnd
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select reservation
            from Reservation reservation
            where reservation.storeId = :storeId
              and reservation.timeSnapshot.serviceDate = :serviceDate
              and reservation.status = :#{T(com.miriyum.domain.reservation.entity.ReservationStatus).CONFIRMED}
            order by reservation.id asc
            """)
    List<Reservation> findConfirmedForCapacityPublication(
            @Param("storeId") long storeId,
            @Param("serviceDate") LocalDate serviceDate
    );

    Optional<Reservation> findByIdAndConsumerAccountId(
            Long reservationId,
            Long consumerAccountId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select reservation from Reservation reservation
            where reservation.id = :reservationId
              and reservation.consumerAccountId = :consumerAccountId
            """)
    Optional<Reservation> findByIdAndConsumerAccountIdForUpdate(
            @Param("reservationId") Long reservationId,
            @Param("consumerAccountId") Long consumerAccountId
    );

    Optional<Reservation> findByIdAndStoreId(
            Long reservationId,
            Long storeId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select reservation from Reservation reservation
            where reservation.id = :reservationId
              and reservation.storeId = :storeId
            """)
    Optional<Reservation> findByIdAndStoreIdForUpdate(
            @Param("reservationId") Long reservationId,
            @Param("storeId") Long storeId
    );

    Page<Reservation> findAllByConsumerAccountId(
            Long consumerAccountId,
            Pageable pageable
    );

    Page<Reservation> findAllByConsumerAccountIdAndStatus(
            Long consumerAccountId,
            ReservationStatus status,
            Pageable pageable
    );

    Page<Reservation> findAllByStoreId(
            Long storeId,
            Pageable pageable
    );

    Page<Reservation> findAllByStoreIdAndTimeSnapshotServiceDate(
            Long storeId,
            LocalDate serviceDate,
            Pageable pageable
    );

    Page<Reservation> findAllByStoreIdAndStatus(
            Long storeId,
            ReservationStatus status,
            Pageable pageable
    );

    Page<Reservation> findAllByStoreIdAndTimeSnapshotServiceDateAndStatus(
            Long storeId,
            LocalDate serviceDate,
            ReservationStatus status,
            Pageable pageable
    );

    @Query(value = """
            SELECT
                COALESCE(SUM(CASE
                    WHEN r.cancelled_at IS NULL OR r.cancelled_at > :asOf
                    THEN 1 ELSE 0 END), 0) AS todayReservationTeams,
                COALESCE(SUM(CASE
                    WHEN r.cancelled_at IS NOT NULL AND r.cancelled_at <= :asOf
                    THEN 1 ELSE 0 END), 0) AS cancelledTeams,
                COUNT(*) AS everConfirmedTeams,
                COALESCE(MAX(r.reservation_id), 0) AS maxReservationId,
                COALESCE(MAX(r.capacity_policy_version), 0) AS maxCapacityPolicyVersion,
                CAST(UNIX_TIMESTAMP(MAX(GREATEST(
                    r.created_at,
                    CASE WHEN r.cancelled_at <= :asOf THEN r.cancelled_at ELSE r.created_at END,
                    CASE WHEN r.fulfilled_at <= :asOf THEN r.fulfilled_at ELSE r.created_at END
                ))) * 1000000 AS SIGNED) AS dataThroughEpochMicros
            FROM reservations r
            WHERE r.store_id = :storeId
              AND r.service_date = :businessDate
              AND r.created_at <= :asOf
            """, nativeQuery = true)
    ReservationAnalyticsLifecycle aggregateDashboardLifecycle(
            @Param("storeId") long storeId,
            @Param("businessDate") LocalDate businessDate,
            @Param("asOf") Instant asOf
    );

    interface ReservationAnalyticsLifecycle {
        Long getTodayReservationTeams();
        Long getCancelledTeams();
        Long getEverConfirmedTeams();
        Long getMaxReservationId();
        Long getMaxCapacityPolicyVersion();
        Long getDataThroughEpochMicros();
    }
}
