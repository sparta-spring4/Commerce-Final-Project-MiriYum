package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingSetting;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaitingSettingRepository extends JpaRepository<WaitingSetting, Long> {
    Optional<WaitingSetting> findByStoreId(long storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select setting from WaitingSetting setting where setting.storeId = :storeId")
    Optional<WaitingSetting> findByStoreIdForUpdate(@Param("storeId") long storeId);
}
