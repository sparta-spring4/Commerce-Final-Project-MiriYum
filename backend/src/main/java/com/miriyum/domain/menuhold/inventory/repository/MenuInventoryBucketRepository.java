package com.miriyum.domain.menuhold.inventory.repository;

import com.miriyum.domain.menuhold.inventory.dto.InventoryBucketKey;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MenuInventoryBucketRepository
        extends JpaRepository<MenuInventoryBucket, Long> {

    default Long findBucketId(InventoryBucketKey key) {
        return findBucketIdByKey(
                key.menuId(), key.serviceDate(), key.startTime(), key.endDate(),
                key.endTime(), key.inventoryPolicyVersion());
    }

    @Query("""
            select bucket.id
            from MenuInventoryBucket bucket
            where bucket.menuId = :menuId
              and bucket.serviceDate = :serviceDate
              and bucket.startTime = :startTime
              and bucket.endDate = :endDate
              and bucket.endTime = :endTime
              and bucket.inventoryPolicyVersion = :policyVersion
            """)
    Long findBucketIdByKey(
            @Param("menuId") long menuId,
            @Param("serviceDate") LocalDate serviceDate,
            @Param("startTime") LocalTime startTime,
            @Param("endDate") LocalDate endDate,
            @Param("endTime") LocalTime endTime,
            @Param("policyVersion") long policyVersion);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select bucket
            from MenuInventoryBucket bucket
            where bucket.menuId = :menuId
              and bucket.serviceDate = :serviceDate
              and bucket.startTime = :startTime
              and bucket.endDate = :endDate
              and bucket.endTime = :endTime
              and bucket.inventoryPolicyVersion = (
                  select max(candidate.inventoryPolicyVersion)
                  from MenuInventoryBucket candidate
                  where candidate.menuId = :menuId
                    and candidate.serviceDate = :serviceDate
                    and candidate.startTime = :startTime
                    and candidate.endDate = :endDate
                    and candidate.endTime = :endTime
              )
            """)
    Optional<MenuInventoryBucket> findCurrentForUpdate(
            @Param("menuId") long menuId,
            @Param("serviceDate") LocalDate serviceDate,
            @Param("startTime") LocalTime startTime,
            @Param("endDate") LocalDate endDate,
            @Param("endTime") LocalTime endTime);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select bucket
            from MenuInventoryBucket bucket
            where bucket.id in :ids
            order by bucket.id
            """)
    List<MenuInventoryBucket> findAllForUpdate(@Param("ids") Collection<Long> ids);

    @Modifying
    @Query(value = """
            update menu_inventory_buckets
            set online_hold_remaining = online_hold_remaining - :onlineQuantity,
                shared_remaining = shared_remaining - :sharedQuantity,
                lock_version = lock_version + 1,
                updated_at = current_timestamp(6)
            where menu_inventory_bucket_id = :bucketId
              and lock_version = :expectedVersion
              and availability_status = 'AVAILABLE'
              and online_hold_remaining >= :onlineQuantity
              and shared_remaining >= :sharedQuantity
            """, nativeQuery = true)
    int decrementIfCurrent(
            @Param("bucketId") long bucketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("onlineQuantity") int onlineQuantity,
            @Param("sharedQuantity") int sharedQuantity);

    @Modifying
    @Query(value = """
            update menu_inventory_buckets
            set online_hold_remaining = online_hold_remaining + :onlineQuantity,
                shared_remaining = shared_remaining + :sharedQuantity,
                lock_version = lock_version + 1,
                updated_at = current_timestamp(6)
            where menu_inventory_bucket_id = :bucketId
              and lock_version = :expectedVersion
              and online_hold_remaining + :onlineQuantity <= online_hold_capacity
              and shared_remaining + :sharedQuantity <= shared_capacity
            """, nativeQuery = true)
    int incrementIfCurrent(
            @Param("bucketId") long bucketId,
            @Param("expectedVersion") long expectedVersion,
            @Param("onlineQuantity") int onlineQuantity,
            @Param("sharedQuantity") int sharedQuantity);
}
