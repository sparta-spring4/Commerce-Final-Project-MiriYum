package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingReceptionWindow;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaitingReceptionWindowRepository
        extends JpaRepository<WaitingReceptionWindow, Long> {

    @Query("""
            select (count(window) > 0)
            from WaitingReceptionWindow window
            where window.storeId = :storeId
              and window.businessIntervalKey = :businessIntervalKey
              and window.businessDate = :businessDate
              and window.openedSettingsVersion = :settingsVersion
              and window.acceptingFrom <= :now
              and window.acceptingUntil > :now
            """)
    boolean existsAccepting(
            @Param("storeId") long storeId,
            @Param("businessIntervalKey") String businessIntervalKey,
            @Param("businessDate") LocalDate businessDate,
            @Param("settingsVersion") long settingsVersion,
            @Param("now") Instant now);
}
