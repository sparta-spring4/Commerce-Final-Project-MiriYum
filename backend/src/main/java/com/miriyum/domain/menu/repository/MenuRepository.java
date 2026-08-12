package com.miriyum.domain.menu.repository;

import com.miriyum.domain.menu.entity.Menu;
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "versions")
    @Query("select distinct m from Menu m where m.id in :menuIds order by m.id")
    List<Menu> findAllByIdForUpdate(@Param("menuIds") List<Long> menuIds);

    @EntityGraph(attributePaths = "versions")
    @Query("select distinct m from Menu m where m.id in :menuIds")
    List<Menu> findAllManagedByIds(@Param("menuIds") List<Long> menuIds);

    @Query("select m.storeId from Menu m where m.id = :menuId")
    Optional<Long> findStoreIdById(@Param("menuId") long menuId);

    /**
     * Store를 루트로 현재 메뉴 홀드 선택 후보를 조회한다.
     * 매장이 존재하면 후보가 없어도 nullable 후보 projection 한 행을 반환한다.
     */
    @Query("""
            select s.id as storeId,
                   case when v.id is null then null else m.id end as menuId,
                   v.name as menuName,
                   v.price as unitPrice
            from Store s
            left join Menu m on m.storeId = s.id
              and s.verificationStatus = com.miriyum.domain.store.enums.VerificationStatus.APPROVED
              and s.operationStatus = com.miriyum.domain.store.enums.OperationStatus.OPEN
              and s.reservationEnabled = true
              and s.menuHoldEnabled = true
              and m.retired = false
              and m.visibility = com.miriyum.domain.menu.enums.MenuVisibility.VISIBLE
              and m.sellingStatus = com.miriyum.domain.menu.enums.MenuSellingStatus.SELLING
            left join m.versions v on v.versionNumber = m.publishedVersionNumber
              and v.status = com.miriyum.domain.menu.enums.MenuVersionStatus.PUBLISHED
              and v.holdSelectionAllowed = true
            where s.id = :storeId
            order by m.id
            """)
    List<MenuHoldSelectionRow> findMenuHoldSelectionRows(
            @Param("storeId") long storeId);

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
              and exists (
                  select s.id from Store s
                  where s.id = m.storeId
                    and s.verificationStatus = com.miriyum.domain.store.enums.VerificationStatus.APPROVED
                    and s.operationStatus <> com.miriyum.domain.store.enums.OperationStatus.CLOSED
              )
            order by v.effectiveAt, m.id
            """)
    List<Long> findDueScheduledIds(@Param("now") Instant now, Pageable pageable);
}
