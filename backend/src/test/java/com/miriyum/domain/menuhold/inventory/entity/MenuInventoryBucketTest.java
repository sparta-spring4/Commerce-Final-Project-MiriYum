package com.miriyum.domain.menuhold.inventory.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class MenuInventoryBucketTest {

    @Test
    void rejectsPoolAllocationAboveTotalSupply() {
        assertThatThrownBy(() -> bucket(5, 3, 2, 1, true))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(MenuHoldErrorCode.POOL_ALLOCATION_EXCEEDS_SUPPLY);
    }

    @Test
    void acquiresOnlineFirstThenSharedWithoutUsingOnsite() {
        MenuInventoryBucket bucket = bucket(10, 2, 5, 3, true);

        InventoryAllocation allocation = bucket.acquire(4);

        assertThat(allocation.onlineHoldQuantity()).isEqualTo(2);
        assertThat(allocation.sharedQuantity()).isEqualTo(2);
        assertThat(bucket.getOnlineHoldRemaining()).isZero();
        assertThat(bucket.getSharedRemaining()).isEqualTo(1);
        assertThat(bucket.getOnsiteRemaining()).isEqualTo(5);
    }

    @Test
    void insufficientOnlineQuantityLeavesEveryPoolUnchanged() {
        MenuInventoryBucket bucket = bucket(10, 2, 5, 3, false);

        assertThatThrownBy(() -> bucket.acquire(3))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(MenuHoldErrorCode.INSUFFICIENT_QUANTITY);
        assertThat(bucket.getOnlineHoldRemaining()).isEqualTo(2);
        assertThat(bucket.getSharedRemaining()).isEqualTo(3);
        assertThat(bucket.getOnsiteRemaining()).isEqualTo(5);
    }

    @Test
    void restoresOnlyThePoolsRecordedByTheAllocation() {
        MenuInventoryBucket bucket = bucket(10, 2, 5, 3, true);
        InventoryAllocation allocation = bucket.acquire(4);

        bucket.restore(allocation);

        assertThat(bucket.getOnlineHoldRemaining()).isEqualTo(2);
        assertThat(bucket.getSharedRemaining()).isEqualTo(3);
        assertThat(bucket.getOnsiteRemaining()).isEqualTo(5);
    }

    @Test
    void restoringQuantityDoesNotReleaseManualSoldOut() {
        MenuInventoryBucket bucket = bucket(10, 2, 5, 3, true);
        InventoryAllocation allocation = bucket.acquire(4);
        bucket.markSoldOut();

        bucket.restore(allocation);

        assertThat(bucket.getAvailabilityStatus()).isEqualTo(InventoryAvailabilityStatus.SOLD_OUT);
        assertThat(bucket.availableOnlineQuantity()).isEqualTo(5);
    }

    @Test
    void storesStoreTimeZoneAndEndDateForOvernightInterval() {
        MenuInventoryBucket bucket = MenuInventoryBucket.create(
                11L,
                LocalDate.of(2026, 8, 10),
                LocalTime.of(23, 0),
                LocalDate.of(2026, 8, 11),
                LocalTime.of(1, 0),
                "Asia/Seoul",
                1L,
                5,
                5,
                0,
                0,
                false);

        assertThat(bucket.getEndDate()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(bucket.getTimeZoneId()).isEqualTo("Asia/Seoul");
    }

    private static MenuInventoryBucket bucket(
            int total,
            int online,
            int onsite,
            int shared,
            boolean sharedOnlineAllowed
    ) {
        return MenuInventoryBucket.create(
                11L,
                LocalDate.of(2026, 8, 10),
                LocalTime.of(12, 0),
                LocalDate.of(2026, 8, 10),
                LocalTime.of(13, 0),
                "Asia/Seoul",
                1L,
                total,
                online,
                onsite,
                shared,
                sharedOnlineAllowed);
    }
}
