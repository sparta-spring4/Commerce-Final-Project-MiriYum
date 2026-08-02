package com.miriyum.domain.store.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.service.CatalogKind;
import com.miriyum.domain.store.service.CatalogService;
import com.miriyum.global.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreSearchCatalogPolicyTest {

    @Mock
    private CatalogService catalogService;

    @InjectMocks
    private StoreSearchCatalogPolicy policy;

    @Test
    void mapsInactiveStoreCategoryToStore004() {
        // given
        given(catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, "UNKNOWN"))
                .willReturn(false);

        // when & then
        assertThatThrownBy(() -> policy.requireActiveStoreCategory("UNKNOWN"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.CATALOG_CODE_INVALID));
    }

    @Test
    void acceptsActiveStoreCategory() {
        // given
        given(catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, "KOREAN"))
                .willReturn(true);

        // when & then
        assertThatCode(() -> policy.requireActiveStoreCategory("KOREAN"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAbsentStoreCategoryFilter() {
        // when & then
        assertThatCode(() -> policy.requireActiveStoreCategory(null))
                .doesNotThrowAnyException();
    }
}
