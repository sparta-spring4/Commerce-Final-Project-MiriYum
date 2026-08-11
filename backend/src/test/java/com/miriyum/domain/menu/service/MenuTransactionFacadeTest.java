package com.miriyum.domain.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;

import com.miriyum.domain.menu.dto.contract.MenuTransactionEligibility;
import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.model.AllergenDisclosure;
import com.miriyum.domain.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.menu.model.AllergenIngredientCode;
import com.miriyum.domain.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.menu.model.MenuContent;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.store.dto.contract.StoreMenuTransactionEligibility;
import com.miriyum.domain.store.service.StoreTransactionEligibilityService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MenuTransactionFacadeTest {

    private static final long STORE_ID = 7L;
    private static final long MENU_ID = 21L;
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

    @Mock
    private StoreTransactionEligibilityService storeEligibilityService;

    @Mock
    private MenuRepository menuRepository;

    private MenuTransactionFacade service;

    @BeforeEach
    void setUp() {
        service = new MenuTransactionFacade(storeEligibilityService, menuRepository);
    }

    @Test
    void locksStoreBeforeMenuAndBuildsTransactionSnapshot() {
        StoreMenuTransactionEligibility store =
                new StoreMenuTransactionEligibility(STORE_ID, true, true, true);
        Menu menu = publishedMenu(true, true);
        given(storeEligibilityService.requireMenuTransactionEligibility(STORE_ID))
                .willReturn(store);
        given(menuRepository.findByIdForUpdate(MENU_ID)).willReturn(Optional.of(menu));

        MenuTransactionEligibility result =
                service.requireTransactionEligibility(STORE_ID, MENU_ID);

        assertThat(result).isEqualTo(new MenuTransactionEligibility(
                STORE_ID,
                MENU_ID,
                1,
                "아메리카노",
                5_000,
                true,
                true));
        InOrder order = inOrder(storeEligibilityService, menuRepository);
        order.verify(storeEligibilityService).requireMenuTransactionEligibility(STORE_ID);
        order.verify(menuRepository).findByIdForUpdate(MENU_ID);
    }

    private Menu publishedMenu(boolean holdAllowed, boolean pickupAllowed) {
        Menu menu = Menu.create(
                STORE_ID,
                new MenuContent(
                        "아메리카노",
                        "설명",
                        5_000,
                        false,
                        "COFFEE",
                        List.of(),
                        List.of(),
                        holdAllowed,
                        pickupAllowed,
                        DisclosureRegistrationStatus.REGISTERED,
                        List.of(new AllergenDisclosure(
                                AllergenIngredientCode.MILK,
                                AllergenDisclosureStatus.CONTAINS)),
                        DisclosureRegistrationStatus.NOT_APPLICABLE,
                        List.of(),
                        false),
                11L,
                NOW);
        ReflectionTestUtils.setField(menu, "id", MENU_ID);
        menu.publish(NOW.plusSeconds(1));
        return menu;
    }
}
