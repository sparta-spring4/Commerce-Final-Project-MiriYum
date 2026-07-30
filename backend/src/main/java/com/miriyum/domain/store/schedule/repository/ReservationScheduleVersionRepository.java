package com.miriyum.domain.store.schedule.repository;

import com.miriyum.domain.store.schedule.entity.ReservationScheduleVersion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationScheduleVersionRepository
        extends JpaRepository<ReservationScheduleVersion, Long> {

    List<ReservationScheduleVersion> findAllByStoreIdOrderByVersionNumber(long storeId);
}
