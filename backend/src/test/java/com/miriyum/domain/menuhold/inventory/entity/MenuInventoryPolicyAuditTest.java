package com.miriyum.domain.menuhold.inventory.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MenuInventoryPolicyAuditTest {

    @Test
    void recordsZeroBeforeForAPolicyCreation() {
        MenuInventoryBucket created = bucket(1L, 10, 5, 2, 3);
        ReflectionTestUtils.setField(created, "id", 41L);

        MenuInventoryPolicyAudit audit = MenuInventoryPolicyAudit.created(
                7L, "MENU_INVENTORY_CREATE",
                "123e4567-e89b-12d3-a456-426614174000", created);

        assertThat(audit.getPreviousBucketId()).isNull();
        assertThat(audit.getTotalSupplyBefore()).isZero();
        assertThat(audit.getTotalSupplyDelta()).isEqualTo(10);
        assertThat(audit.getTotalSupplyAfter()).isEqualTo(10);
        assertThat(audit.getAvailabilityBefore()).isNull();
        assertThat(audit.getAvailabilityAfter())
                .isEqualTo(InventoryAvailabilityStatus.AVAILABLE);
    }

    @Test
    void recordsBeforeDeltaAndAfterForAPolicyUpdate() {
        MenuInventoryBucket current = bucket(1L, 10, 5, 2, 3);
        ReflectionTestUtils.setField(current, "id", 41L);
        MenuInventoryBucket next = current.publishNextPolicy(
                12, 6, 2, 4, true, InventoryAvailabilityStatus.SOLD_OUT);
        ReflectionTestUtils.setField(next, "id", 42L);

        MenuInventoryPolicyAudit audit = MenuInventoryPolicyAudit.updated(
                7L, "MENU_INVENTORY_UPDATE",
                "123e4567-e89b-12d3-a456-426614174000", current, next);

        assertThat(audit.getBucketId()).isEqualTo(42L);
        assertThat(audit.getPreviousBucketId()).isEqualTo(41L);
        assertThat(audit.getPolicyVersion()).isEqualTo(2L);
        assertThat(audit.getTotalSupplyBefore()).isEqualTo(10);
        assertThat(audit.getTotalSupplyDelta()).isEqualTo(2);
        assertThat(audit.getTotalSupplyAfter()).isEqualTo(12);
        assertThat(audit.getOnlineCapacityDelta()).isEqualTo(1);
        assertThat(audit.getAvailabilityBefore())
                .isEqualTo(InventoryAvailabilityStatus.AVAILABLE);
        assertThat(audit.getAvailabilityAfter())
                .isEqualTo(InventoryAvailabilityStatus.SOLD_OUT);
    }

    private static MenuInventoryBucket bucket(
            long version,
            int total,
            int online,
            int onsite,
            int shared
    ) {
        return MenuInventoryBucket.create(
                11L,
                LocalDate.of(2026, 8, 10),
                LocalTime.of(12, 0),
                LocalDate.of(2026, 8, 10),
                LocalTime.of(13, 0),
                "Asia/Seoul",
                version,
                total,
                online,
                onsite,
                shared,
                true);
    }
}
