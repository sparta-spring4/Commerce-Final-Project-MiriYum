package com.miriyum.domain.store.menu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.miriyum.domain.store.menu.dto.MenuHoldSelectableMenu;
import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;

class MenuHoldSelectionPublicContractTest {

    @Test
    void exposesOnlyMenuHoldSelectionSnapshotFields() {
        assertThat(MenuHoldSelectableMenu.class.isRecord()).isTrue();
        assertThat(MenuHoldSelectableMenu.class.getRecordComponents())
                .extracting(RecordComponent::getName, RecordComponent::getType)
                .containsExactly(
                        tuple("menuId", long.class),
                        tuple("menuName", String.class),
                        tuple("unitPrice", int.class));
    }

    @Test
    void rejectsInvalidMenuHoldSelectionSnapshot() {
        assertThatThrownBy(() -> new MenuHoldSelectableMenu(0L, "Americano", 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("menuId must be positive");
        assertThatThrownBy(() -> new MenuHoldSelectableMenu(1L, " ", 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("menuName must not be blank");
        assertThatThrownBy(() -> new MenuHoldSelectableMenu(1L, "Americano", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unitPrice must not be negative");
    }
}
