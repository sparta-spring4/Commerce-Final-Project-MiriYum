package com.miriyum.domain.store.closure.repository;

import com.miriyum.domain.store.closure.entity.StoreClosureAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreClosureAuditEventRepository extends JpaRepository<StoreClosureAuditEvent, Long> {
}
