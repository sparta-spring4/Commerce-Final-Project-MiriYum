package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingSetting;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WaitingSettingRepository extends JpaRepository<WaitingSetting, Long> {
    Optional<WaitingSetting> findByStoreId(long storeId);
}
