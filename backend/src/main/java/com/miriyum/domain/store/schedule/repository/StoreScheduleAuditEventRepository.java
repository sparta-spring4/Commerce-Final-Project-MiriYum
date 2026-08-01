package com.miriyum.domain.store.schedule.repository;

import com.miriyum.domain.store.schedule.entity.StoreScheduleAuditEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreScheduleAuditEventRepository
        extends JpaRepository<StoreScheduleAuditEvent, Long> {

    List<StoreScheduleAuditEvent> findAllByStoreIdOrderById(long storeId);
}
