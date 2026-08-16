package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingSettingAudit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 매장과 설정 version별 불변 감사 snapshot을 저장한다.
 */
public interface WaitingSettingAuditRepository extends JpaRepository<WaitingSettingAudit, Long> {
    long countByStoreId(long storeId);
}
