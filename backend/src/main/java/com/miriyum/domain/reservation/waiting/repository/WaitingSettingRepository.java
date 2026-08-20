package com.miriyum.domain.reservation.waiting.repository;

import com.miriyum.domain.reservation.waiting.entity.WaitingSetting;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 매장별 단일 현재 설정의 조회와 명령 직렬화 잠금을 제공한다.
 */
public interface WaitingSettingRepository extends JpaRepository<WaitingSetting, Long> {
    Optional<WaitingSetting> findByStoreId(long storeId);

    /**
     * 설정 교체·신규 팀 생성·closure fence가 공유하는 매장 설정 행을 배타 잠금한다.
     *
     * @param storeId 잠글 매장 ID
     * @return 현재 설정, 아직 생성되지 않았으면 empty
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select setting from WaitingSetting setting where setting.storeId = :storeId")
    Optional<WaitingSetting> findByStoreIdForUpdate(@Param("storeId") long storeId);

    @Query("""
            select setting
            from WaitingSetting setting
            where setting.storeId > :afterStoreId
              and setting.enabled = true
              and setting.receptionMode = com.miriyum.domain.reservation.waiting.entity.WaitingReceptionMode.AUTO
            order by setting.storeId
            """)
    List<WaitingSetting> findAutoOpenPlanningCandidates(
            @Param("afterStoreId") long afterStoreId,
            Pageable pageable);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE waiting_settings
            SET lock_version = lock_version + 1,
                updated_at = :now
            WHERE store_id = :storeId
              AND version = :expectedVersion
              AND enabled = TRUE
              AND reception_mode = 'AUTO'
              AND advance_open_minutes = :expectedAdvanceOpenMinutes
            """, nativeQuery = true)
    int compareAndFenceAutoOpen(
            @Param("storeId") long storeId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedAdvanceOpenMinutes") int expectedAdvanceOpenMinutes,
            @Param("now") Instant now);
}
