package com.miriyum.domain.store.menu.repository;

import com.miriyum.domain.store.menu.entity.Menu;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    @Query("select m.storeId from Menu m where m.id = :menuId")
    Optional<Long> findStoreIdById(@Param("menuId") long menuId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"versions", "versions.secondaryCategoryCodes",
            "versions.localTags", "versions.allergenDisclosures",
            "versions.originDisclosures"})
    @Query("select distinct m from Menu m where m.id = :menuId")
    Optional<Menu> findByIdForUpdate(@Param("menuId") long menuId);

    @EntityGraph(attributePaths = {"versions", "versions.secondaryCategoryCodes",
            "versions.localTags", "versions.allergenDisclosures",
            "versions.originDisclosures"})
    @Query("select distinct m from Menu m where m.id = :menuId")
    Optional<Menu> findManagedById(@Param("menuId") long menuId);

    @EntityGraph(attributePaths = {"versions", "versions.secondaryCategoryCodes",
            "versions.localTags", "versions.allergenDisclosures",
            "versions.originDisclosures"})
    @Query("select distinct m from Menu m where m.storeId = :storeId order by m.id")
    List<Menu> findAllManagedByStoreId(@Param("storeId") long storeId);

    @Query("""
            select m.id from Menu m
            join m.versions v
            where m.retired = false
              and m.scheduledVersionNumber = v.versionNumber
              and v.effectiveAt <= :now
            order by v.effectiveAt, m.id
            """)
    List<Long> findDueScheduledIds(@Param("now") Instant now, Pageable pageable);
}
