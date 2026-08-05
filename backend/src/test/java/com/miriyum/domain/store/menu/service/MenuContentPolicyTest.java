package com.miriyum.domain.store.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.store.core.enums.PickupEligibility;
import com.miriyum.domain.store.core.service.StoreMenuAuthority;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.menu.dto.MenuContentRequest;
import com.miriyum.domain.store.menu.dto.AllergenDisclosureRequest;
import com.miriyum.domain.store.menu.dto.OriginDisclosureRequest;
import com.miriyum.domain.store.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.store.menu.model.AllergenIngredientCode;
import com.miriyum.domain.store.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.store.menu.model.MenuContent;
import com.miriyum.domain.store.service.CatalogKind;
import com.miriyum.domain.store.service.CatalogService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class MenuContentPolicyTest {

    @Mock
    private CatalogService catalogService;

    private MenuContentPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new MenuContentPolicy(catalogService);
    }

    @Test
    void normalizesAndDeduplicatesLocalTags() {
        given(catalogService.findUnknownCodes(
                CatalogKind.MENU_CATEGORY, List.of("COFFEE", "BEVERAGE")))
                .willReturn(List.of());

        MenuContent result = policy.validateAndNormalize(
                request(List.of("  signature  ", "ＳＩＧＮＡＴＵＲＥ", "night")),
                store(PickupEligibility.ELIGIBLE));

        assertThat(result.localTags()).containsExactly("signature", "night");
    }

    @Test
    void rejectsPrimaryCategoryRepeatedAsSecondary() {
        assertValidationFailure(new MenuContentRequest(
                "Americano", "", 5_000, false, "COFFEE",
                List.of("COFFEE"), List.of(), true, false,
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosureRequest(
                        AllergenIngredientCode.MILK, AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.NOT_APPLICABLE, List.of(), false));
    }

    @Test
    void rejectsUnknownCategoryAsStore004() {
        given(catalogService.findUnknownCodes(
                CatalogKind.MENU_CATEGORY, List.of("COFFEE", "UNKNOWN")))
                .willReturn(List.of("UNKNOWN"));

        assertThatThrownBy(() -> policy.validateAndNormalize(
                new MenuContentRequest(
                        "Americano", "", 5_000, false, "COFFEE",
                        List.of("UNKNOWN"), List.of(), true, false,
                        DisclosureRegistrationStatus.REGISTERED,
                        List.of(new AllergenDisclosureRequest(
                                AllergenIngredientCode.MILK,
                                AllergenDisclosureStatus.CONTAINS)),
                        DisclosureRegistrationStatus.NOT_APPLICABLE, List.of(), false),
                store(PickupEligibility.ELIGIBLE)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.CATALOG_CODE_INVALID);
    }

    @Test
    void rejectsPickupForIneligibleStore() {
        given(catalogService.findUnknownCodes(
                CatalogKind.MENU_CATEGORY, List.of("COFFEE", "BEVERAGE")))
                .willReturn(List.of());

        assertThatThrownBy(() -> policy.validateAndNormalize(
                request(List.of()), store(PickupEligibility.INELIGIBLE)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.PICKUP_NOT_ELIGIBLE);
    }

    @Test
    void rejectsUrlAndOfficialImpersonationTags() {
        assertValidationFailure(request(List.of("https://example.com")));
        assertValidationFailure(request(List.of("MiriYum official")));
    }

    @Test
    void rejectsRegisteredAllergenStatusWithoutAnyDisclosure() {
        assertValidationFailure(new MenuContentRequest(
                "Americano", "", 5_000, false, "COFFEE",
                List.of(), List.of(), true, false,
                DisclosureRegistrationStatus.REGISTERED, List.of(),
                DisclosureRegistrationStatus.NOT_APPLICABLE, List.of(), false));
    }

    @Test
    void rejectsDuplicateAllergenIngredientWithDifferentStatuses() {
        assertValidationFailure(new MenuContentRequest(
                "Americano", "", 5_000, false, "COFFEE",
                List.of(), List.of(), true, false,
                DisclosureRegistrationStatus.REGISTERED,
                List.of(
                        new AllergenDisclosureRequest(
                                AllergenIngredientCode.MILK,
                                AllergenDisclosureStatus.CONTAINS),
                        new AllergenDisclosureRequest(
                                AllergenIngredientCode.MILK,
                                AllergenDisclosureStatus.MAY_CONTAIN)),
                DisclosureRegistrationStatus.NOT_APPLICABLE, List.of(), false));
    }

    private void assertValidationFailure(MenuContentRequest request) {
        assertThatThrownBy(() -> policy.validateAndNormalize(
                request, store(PickupEligibility.ELIGIBLE)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    private MenuContentRequest request(List<String> tags) {
        return new MenuContentRequest(
                "Americano", "description", 5_000, false, "COFFEE",
                List.of("BEVERAGE"), tags, true, true,
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosureRequest(
                        AllergenIngredientCode.MILK, AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new OriginDisclosureRequest("원두", "콜롬비아")),
                false);
    }

    private StoreMenuAuthority store(PickupEligibility eligibility) {
        return new StoreMenuAuthority(7L, eligibility);
    }
}
