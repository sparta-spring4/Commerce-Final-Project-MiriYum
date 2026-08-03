package com.miriyum.domain.menuhold.inventory.entity;

import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;
import com.miriyum.global.entity.BaseEntity;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "menu_inventory_buckets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuInventoryBucket extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_inventory_bucket_id")
    private Long id;

    @Column(name = "menu_id", nullable = false)
    private long menuId;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "time_zone_id", nullable = false, length = 64)
    private String timeZoneId;

    @Column(name = "inventory_policy_version", nullable = false)
    private long inventoryPolicyVersion;

    @Column(name = "total_supply", nullable = false)
    private int totalSupply;

    @Column(name = "online_hold_remaining", nullable = false)
    private int onlineHoldRemaining;

    @Column(name = "online_hold_capacity", nullable = false)
    private int onlineHoldCapacity;

    @Column(name = "onsite_remaining", nullable = false)
    private int onsiteRemaining;

    @Column(name = "onsite_capacity", nullable = false)
    private int onsiteCapacity;

    @Column(name = "shared_remaining", nullable = false)
    private int sharedRemaining;

    @Column(name = "shared_capacity", nullable = false)
    private int sharedCapacity;

    @Column(name = "shared_online_allowed", nullable = false)
    private boolean sharedOnlineAllowed;

    @Enumerated(EnumType.STRING)
    @Column(name = "availability_status", nullable = false, length = 20)
    private InventoryAvailabilityStatus availabilityStatus;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    public static MenuInventoryBucket create(
            long menuId,
            LocalDate serviceDate,
            LocalTime startTime,
            LocalDate endDate,
            LocalTime endTime,
            String timeZoneId,
            long inventoryPolicyVersion,
            int totalSupply,
            int onlineHoldQuantity,
            int onsiteQuantity,
            int sharedQuantity,
            boolean sharedOnlineAllowed
    ) {
        if (menuId <= 0 || serviceDate == null || startTime == null || endDate == null
                || endTime == null || timeZoneId == null || timeZoneId.isBlank()
                || !LocalDateTime.of(serviceDate, startTime)
                        .isBefore(LocalDateTime.of(endDate, endTime))
                || inventoryPolicyVersion <= 0
                || totalSupply < 0 || onlineHoldQuantity < 0 || onsiteQuantity < 0
                || sharedQuantity < 0) {
            throw new IllegalArgumentException("invalid menu inventory bucket");
        }
        ZoneId.of(timeZoneId);
        if ((long) onlineHoldQuantity + onsiteQuantity + sharedQuantity > totalSupply) {
            throw new ServiceException(MenuHoldErrorCode.POOL_ALLOCATION_EXCEEDS_SUPPLY);
        }
        MenuInventoryBucket bucket = new MenuInventoryBucket();
        bucket.menuId = menuId;
        bucket.serviceDate = serviceDate;
        bucket.startTime = startTime;
        bucket.endDate = endDate;
        bucket.endTime = endTime;
        bucket.timeZoneId = timeZoneId;
        bucket.inventoryPolicyVersion = inventoryPolicyVersion;
        bucket.totalSupply = totalSupply;
        bucket.onlineHoldRemaining = onlineHoldQuantity;
        bucket.onlineHoldCapacity = onlineHoldQuantity;
        bucket.onsiteRemaining = onsiteQuantity;
        bucket.onsiteCapacity = onsiteQuantity;
        bucket.sharedRemaining = sharedQuantity;
        bucket.sharedCapacity = sharedQuantity;
        bucket.sharedOnlineAllowed = sharedOnlineAllowed;
        bucket.availabilityStatus = InventoryAvailabilityStatus.AVAILABLE;
        return bucket;
    }

    public int availableOnlineQuantity() {
        return onlineHoldRemaining + (sharedOnlineAllowed ? sharedRemaining : 0);
    }

    public void markSoldOut() {
        availabilityStatus = InventoryAvailabilityStatus.SOLD_OUT;
    }

    public InventoryAllocation acquire(int quantity) {
        InventoryAllocation allocation = planAcquire(quantity);
        onlineHoldRemaining -= allocation.onlineHoldQuantity();
        sharedRemaining -= allocation.sharedQuantity();
        return allocation;
    }

    public InventoryAllocation planAcquire(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("inventory quantity must be positive");
        }
        int usableShared = sharedOnlineAllowed ? sharedRemaining : 0;
        if (availableOnlineQuantity() < quantity
                || availabilityStatus == InventoryAvailabilityStatus.SOLD_OUT) {
            throw new ServiceException(MenuHoldErrorCode.INSUFFICIENT_QUANTITY);
        }
        int fromOnline = Math.min(onlineHoldRemaining, quantity);
        int fromShared = quantity - fromOnline;
        return new InventoryAllocation(fromOnline, fromShared);
    }

    public void restore(InventoryAllocation allocation) {
        validateRestore(allocation);
        onlineHoldRemaining += allocation.onlineHoldQuantity();
        sharedRemaining += allocation.sharedQuantity();
    }

    public void validateRestore(InventoryAllocation allocation) {
        if (allocation == null
                || onlineHoldRemaining + allocation.onlineHoldQuantity() > onlineHoldCapacity
                || sharedRemaining + allocation.sharedQuantity() > sharedCapacity) {
            throw new ServiceException(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        }
    }
}
