package com.miriyum.store.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.miriyum.store.catalog.domain.CatalogItem;
import com.miriyum.store.catalog.domain.CatalogKind;
import com.miriyum.store.catalog.repository.CatalogItemRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock
    private CatalogItemRepository catalogItemRepository;

    private CatalogService catalogService;

    @BeforeEach
    void setUp() {
        catalogService = new CatalogService(catalogItemRepository);
    }

    @Test
    @DisplayName("getItems는 활성 항목을 정렬 순서대로 반환한다")
    void getItems_returnsActiveItemsInSortOrder() {
        when(catalogItemRepository.findByKindAndActiveTrueOrderBySortOrderAsc(CatalogKind.STORE_CATEGORY))
                .thenReturn(List.of(
                        new CatalogItem(CatalogKind.STORE_CATEGORY, "KOREAN", "한식", true, 1),
                        new CatalogItem(CatalogKind.STORE_CATEGORY, "CAFE_BAKERY", "카페·베이커리", true, 6)));

        List<CatalogItemView> result = catalogService.getItems(CatalogKind.STORE_CATEGORY);

        assertThat(result).containsExactly(
                new CatalogItemView("KOREAN", "한식"),
                new CatalogItemView("CAFE_BAKERY", "카페·베이커리"));
    }

    @Test
    @DisplayName("isActiveCode는 활성 코드에 true를 반환한다")
    void isActiveCode_activeCode_returnsTrue() {
        when(catalogItemRepository.existsByKindAndCodeAndActiveTrue(CatalogKind.STORE_CATEGORY, "KOREAN"))
                .thenReturn(true);

        assertThat(catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, "KOREAN")).isTrue();
    }

    @Test
    @DisplayName("isActiveCode는 미승인 코드에 false를 반환한다")
    void isActiveCode_unknownCode_returnsFalse() {
        when(catalogItemRepository.existsByKindAndCodeAndActiveTrue(CatalogKind.STORE_CATEGORY, "UNKNOWN"))
                .thenReturn(false);

        assertThat(catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, "UNKNOWN")).isFalse();
    }

    @Test
    @DisplayName("isActiveCode는 null 코드에 저장소 조회 없이 false를 반환한다")
    void isActiveCode_nullCode_returnsFalse() {
        assertThat(catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, null)).isFalse();
    }

    @Test
    @DisplayName("findUnknownCodes는 미승인·비활성 코드만 반환한다")
    void findUnknownCodes_returnsOnlyUnknownCodes() {
        when(catalogItemRepository.findByKindAndActiveTrueAndCodeIn(CatalogKind.MENU_CATEGORY, List.of("RICE", "XXX")))
                .thenReturn(List.of(new CatalogItem(CatalogKind.MENU_CATEGORY, "RICE", "밥요리", true, 1)));

        assertThat(catalogService.findUnknownCodes(CatalogKind.MENU_CATEGORY, List.of("RICE", "XXX")))
                .containsExactly("XXX");
    }

    @Test
    @DisplayName("findUnknownCodes는 전부 유효하면 빈 목록을 반환한다")
    void findUnknownCodes_allValid_returnsEmpty() {
        when(catalogItemRepository.findByKindAndActiveTrueAndCodeIn(CatalogKind.STORE_TAG, List.of("DATE", "QUIET")))
                .thenReturn(List.of(
                        new CatalogItem(CatalogKind.STORE_TAG, "DATE", "데이트", true, 1),
                        new CatalogItem(CatalogKind.STORE_TAG, "QUIET", "조용한", true, 2)));

        assertThat(catalogService.findUnknownCodes(CatalogKind.STORE_TAG, List.of("DATE", "QUIET"))).isEmpty();
    }

    @Test
    @DisplayName("findUnknownCodes는 빈 입력에 저장소 조회 없이 빈 목록을 반환한다")
    void findUnknownCodes_emptyInput_returnsEmpty() {
        assertThat(catalogService.findUnknownCodes(CatalogKind.STORE_TAG, List.of())).isEmpty();
    }
}
