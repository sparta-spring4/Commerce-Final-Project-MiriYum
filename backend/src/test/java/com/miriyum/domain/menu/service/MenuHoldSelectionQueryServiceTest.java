package com.miriyum.domain.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.menu.dto.contract.MenuHoldSelectableMenu;
import com.miriyum.domain.menu.repository.MenuHoldSelectionRow;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MenuHoldSelectionQueryServiceTest {

    @Mock
    private MenuRepository menuRepository;

    private MenuHoldSelectionQueryService service;

    @BeforeEach
    void setUp() {
        service = new MenuHoldSelectionQueryService(menuRepository);
    }

    @Test
    void rejectsMissingStoreWhenQueryReturnsNoRows() {
        given(menuRepository.findMenuHoldSelectionRows(10L)).willReturn(List.of());

        assertThatThrownBy(() -> service.findSelectableMenus(10L))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_NOT_FOUND);
    }

    @Test
    void returnsEmptyListForExistingStoreWithoutSelectableMenus() {
        given(menuRepository.findMenuHoldSelectionRows(10L))
                .willReturn(List.of(row(10L, null, null, null)));

        List<MenuHoldSelectableMenu> result = service.findSelectableMenus(10L);

        assertThat(result).isEmpty();
        assertThatThrownBy(() -> result.add(
                new MenuHoldSelectableMenu(1L, "Americano", 5_000)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mapsCurrentSnapshotsInMenuIdOrder() {
        given(menuRepository.findMenuHoldSelectionRows(10L)).willReturn(List.of(
                row(10L, 22L, "Latte", 6_000),
                row(10L, 11L, "Americano", 5_000)));

        List<MenuHoldSelectableMenu> result = service.findSelectableMenus(10L);

        assertThat(result).containsExactly(
                new MenuHoldSelectableMenu(11L, "Americano", 5_000),
                new MenuHoldSelectableMenu(22L, "Latte", 6_000));
        assertThatThrownBy(() -> result.add(
                new MenuHoldSelectableMenu(33L, "Tea", 4_000)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static MenuHoldSelectionRow row(
            Long storeId,
            Long menuId,
            String menuName,
            Integer unitPrice
    ) {
        return new TestMenuHoldSelectionRow(storeId, menuId, menuName, unitPrice);
    }

    private record TestMenuHoldSelectionRow(
            Long storeId,
            Long menuId,
            String menuName,
            Integer unitPrice
    ) implements MenuHoldSelectionRow {

        @Override
        public Long getStoreId() {
            return storeId;
        }

        @Override
        public Long getMenuId() {
            return menuId;
        }

        @Override
        public String getMenuName() {
            return menuName;
        }

        @Override
        public Integer getUnitPrice() {
            return unitPrice;
        }
    }
}
