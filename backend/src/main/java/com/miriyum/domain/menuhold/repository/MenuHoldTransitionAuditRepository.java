package com.miriyum.domain.menuhold.repository;

import com.miriyum.domain.menuhold.entity.MenuHoldTransitionAudit;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

public interface MenuHoldTransitionAuditRepository
        extends JpaRepository<MenuHoldTransitionAudit, Long> {

    List<MenuHoldTransitionAudit> findByMenuHold_IdAndOccurredAtLessThanEqualOrderByResultVersionAsc(
            long menuHoldId,
            Instant asOf);

    Optional<MenuHoldTransitionAudit> findFirstByMenuHold_IdOrderByResultVersionAsc(long menuHoldId);

    List<MenuHoldTransitionAudit> findByOccurredAtBetweenOrderByOccurredAtDescIdDesc(
            Instant changedFrom,
            Instant changedTo,
            Pageable pageable);

    List<MenuHoldTransitionAudit> findByMenuHold_IdInOrderByMenuHold_IdAscResultVersionAsc(
            List<Long> menuHoldIds);
}
