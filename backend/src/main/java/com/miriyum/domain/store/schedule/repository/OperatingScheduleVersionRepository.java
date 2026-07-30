package com.miriyum.domain.store.schedule.repository;

import com.miriyum.domain.store.schedule.entity.OperatingScheduleVersion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperatingScheduleVersionRepository
        extends JpaRepository<OperatingScheduleVersion, Long> {

    List<OperatingScheduleVersion> findAllByStoreIdOrderByVersionNumber(long storeId);
}
