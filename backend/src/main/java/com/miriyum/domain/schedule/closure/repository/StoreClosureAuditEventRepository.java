package com.miriyum.domain.schedule.closure.repository;

import com.miriyum.domain.schedule.closure.entity.StoreClosureAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreClosureAuditEventRepository extends JpaRepository<StoreClosureAuditEvent, Long> {
}
