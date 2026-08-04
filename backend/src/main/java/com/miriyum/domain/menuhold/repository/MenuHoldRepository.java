package com.miriyum.domain.menuhold.repository;

import com.miriyum.domain.menuhold.entity.MenuHold;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuHoldRepository extends JpaRepository<MenuHold, Long> {
    boolean existsByReservationId(long reservationId);
    boolean existsByAcquireOperationId(String acquireOperationId);
}
