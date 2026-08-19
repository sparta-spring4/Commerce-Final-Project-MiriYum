package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingPartyAudit;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WaitingPartyAuditRepository extends JpaRepository<WaitingPartyAudit, Long> {
    Optional<WaitingPartyAudit> findByCommandId(String commandId);
}
