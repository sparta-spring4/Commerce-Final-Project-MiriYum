package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationHoldWarningTask;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 선점별 최대 한 건인 경고 의무의 영속성 경계다. */
public interface ReservationHoldWarningTaskRepository
        extends JpaRepository<ReservationHoldWarningTask, Long> {

    Optional<ReservationHoldWarningTask> findByReservationHoldId(Long reservationHoldId);
}
