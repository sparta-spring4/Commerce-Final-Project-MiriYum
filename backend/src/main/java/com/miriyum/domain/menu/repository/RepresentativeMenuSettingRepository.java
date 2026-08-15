package com.miriyum.domain.menu.repository;

import com.miriyum.domain.menu.entity.RepresentativeMenuSetting;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepresentativeMenuSettingRepository
        extends JpaRepository<RepresentativeMenuSetting, Long> {

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO representative_menu_settings
                (store_id, version, status, lock_version, created_at, updated_at)
            VALUES (:storeId, 0, 'UNCONFIGURED', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """, nativeQuery = true)
    int ensureExists(@Param("storeId") long storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "entries")
    @Query("select s from RepresentativeMenuSetting s where s.storeId = :storeId")
    Optional<RepresentativeMenuSetting> findByStoreIdForUpdate(
            @Param("storeId") long storeId);

    @EntityGraph(attributePaths = "entries")
    @Query("select s from RepresentativeMenuSetting s where s.storeId = :storeId")
    Optional<RepresentativeMenuSetting> findDetailedByStoreId(
            @Param("storeId") long storeId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM representative_menu_entries WHERE store_id = :storeId
            """, nativeQuery = true)
    int deleteEntriesForReplacement(@Param("storeId") long storeId);
}
