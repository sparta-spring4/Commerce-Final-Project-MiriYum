package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import jakarta.persistence.LockModeType;
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

    Optional<Reservation> findByIdAndStoreId(
            Long reservationId,
            Long storeId
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
}
