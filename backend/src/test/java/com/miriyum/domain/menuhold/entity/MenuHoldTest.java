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
                List.of(new MenuHoldItemSnapshot(40L, 50L, 2L, 3L, 4)));

        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.CONFIRMED);
        assertThat(hold.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getMenuId()).isEqualTo(40L);
            assertThat(item.getMenuInventoryBucketId()).isEqualTo(50L);
            assertThat(item.getQuantity()).isEqualTo(4);
        });
    }

    @Test
    void rejectsDuplicateSelectedMenus() {
        MenuHoldItemSnapshot item = new MenuHoldItemSnapshot(40L, 50L, 2L, 3L, 4);

        assertThatThrownBy(() -> MenuHold.confirmed(
                10L, 20L, 30L, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0), "operation-1",
                List.of(item, item)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("menu hold items must have unique menus");
    }
}
