package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 일반 예약 aggregate의 영속성과 소유 범위 조회 경계다.
 */
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

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

    Page<Reservation> findAllByStoreIdAndServiceDate(
            Long storeId,
            LocalDate serviceDate,
            Pageable pageable
    );

    Page<Reservation> findAllByStoreIdAndStatus(
            Long storeId,
            ReservationStatus status,
            Pageable pageable
    );

    Page<Reservation> findAllByStoreIdAndServiceDateAndStatus(
            Long storeId,
            LocalDate serviceDate,
            ReservationStatus status,
            Pageable pageable
    );
}
