package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingSettingAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WaitingSettingAuditRepository extends JpaRepository<WaitingSettingAudit, Long> {
    long countByStoreId(long storeId);
}
