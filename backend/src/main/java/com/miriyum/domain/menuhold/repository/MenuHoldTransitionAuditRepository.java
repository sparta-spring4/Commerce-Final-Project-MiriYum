package com.miriyum.domain.menuhold.repository;

import com.miriyum.domain.menuhold.entity.MenuHoldTransitionAudit;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuHoldTransitionAuditRepository
        extends JpaRepository<MenuHoldTransitionAudit, Long> {

    List<MenuHoldTransitionAudit> findByMenuHold_IdAndOccurredAtLessThanEqualOrderByResultVersionAsc(
            long menuHoldId,
            Instant asOf);

    Optional<MenuHoldTransitionAudit> findFirstByMenuHold_IdOrderByResultVersionAsc(long menuHoldId);
}
