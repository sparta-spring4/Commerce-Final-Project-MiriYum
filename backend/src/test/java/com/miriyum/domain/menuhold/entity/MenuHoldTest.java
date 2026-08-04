package com.miriyum.domain.menuhold.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuHoldTest {

    @Test
    void confirmsOneItemPerSelectedMenu() {
        MenuHold hold = MenuHold.confirmed(
                10L, 20L, 30L, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0), "operation-1",
                List.of(new MenuHoldItemSnapshot(
                        40L, 50L, 2L, "아메리카노", 5_000, 3L, 4)));

        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.CONFIRMED);
        assertThat(hold.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getMenuId()).isEqualTo(40L);
            assertThat(item.getMenuInventoryBucketId()).isEqualTo(50L);
            assertThat(item.getMenuNameSnapshot()).isEqualTo("아메리카노");
            assertThat(item.getUnitPriceSnapshot()).isEqualTo(5_000);
            assertThat(item.getQuantity()).isEqualTo(4);
        });
    }

    @Test
    void acceptsSameMenuInDifferentInventoryBuckets() {
        MenuHold hold = MenuHold.confirmed(
                10L, 20L, 30L, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0), "operation-1",
                List.of(
                        new MenuHoldItemSnapshot(
                                40L, 50L, 2L, "아메리카노", 5_000, 3L, 4),
                        new MenuHoldItemSnapshot(
                                40L, 51L, 2L, "아메리카노", 5_000, 3L, 1)));

        assertThat(hold.getItems()).hasSize(2);
    }

    @Test
    void rejectsDuplicateInventoryBuckets() {
        MenuHoldItemSnapshot item = new MenuHoldItemSnapshot(
                40L, 50L, 2L, "아메리카노", 5_000, 3L, 4);

        assertThatThrownBy(() -> MenuHold.confirmed(
                10L, 20L, 30L, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0), "operation-1",
                List.of(item, item)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("menu hold items must have unique inventory buckets");
    }

    @Test
    void rejectsInvalidMenuDisplaySnapshot() {
        assertThatThrownBy(() -> new MenuHoldItemSnapshot(
                40L, 50L, 2L, " ", 5_000, 3L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MenuHoldItemSnapshot(
                40L, 50L, 2L, "가".repeat(101), 5_000, 3L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MenuHoldItemSnapshot(
                40L, 50L, 2L, "아메리카노", -1, 3L, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
