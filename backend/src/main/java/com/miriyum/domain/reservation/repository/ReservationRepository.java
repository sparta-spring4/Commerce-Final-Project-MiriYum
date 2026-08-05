package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.Reservation;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
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
}
