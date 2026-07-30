package com.miriyum.domain.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.miriyum.domain.store.entity.MenuCategory;
import com.miriyum.domain.store.entity.StoreCategory;
import com.miriyum.domain.store.entity.StoreTag;
import com.miriyum.domain.store.repository.MenuCategoryRepository;
import com.miriyum.domain.store.repository.StoreCategoryRepository;
import com.miriyum.domain.store.repository.StoreTagRepository;
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
    private StoreCategoryRepository storeCategoryRepository;

    @Mock
    private MenuCategoryRepository menuCategoryRepository;

    @Mock
    private StoreTagRepository storeTagRepository;

    private CatalogService catalogService;

    @BeforeEach
    void setUp() {
        catalogService = new CatalogService(storeCategoryRepository, menuCategoryRepository, storeTagRepository);
    }

    @Test
    @DisplayName("getItems는 종류 저장소의 활성 항목을 순서대로 뷰로 변환한다")
    void getItems_mapsActiveItemsToViews() {
        // given
        when(storeCategoryRepository.findByActiveTrueOrderBySortOrderAscCodeAsc()).thenReturn(List.of(
                StoreCategory.of("KOREAN", "한식", true, 1),
                StoreCategory.of("CAFE_BAKERY", "카페·베이커리", true, 6)));

        // when
        List<CatalogItemView> result = catalogService.getItems(CatalogKind.STORE_CATEGORY);

        // then
        assertThat(result).containsExactly(
                new CatalogItemView("KOREAN", "한식"),
                new CatalogItemView("CAFE_BAKERY", "카페·베이커리"));
    }

    @Test
    @DisplayName("getItems는 종류에 맞는 저장소를 선택한다")
    void getItems_selectsRepositoryByKind() {
        // given
        when(menuCategoryRepository.findByActiveTrueOrderBySortOrderAscCodeAsc()).thenReturn(List.of(
                MenuCategory.of("RICE", "밥요리", true, 1)));

        // when & then
        assertThat(catalogService.getItems(CatalogKind.MENU_CATEGORY))
                .containsExactly(new CatalogItemView("RICE", "밥요리"));
    }

    @Test
    @DisplayName("isActiveCode는 활성 코드에 true를 반환한다")
    void isActiveCode_activeCode_returnsTrue() {
        // given
        when(storeTagRepository.existsByCodeAndActiveTrue("DATE")).thenReturn(true);

        // when & then
        assertThat(catalogService.isActiveCode(CatalogKind.STORE_TAG, "DATE")).isTrue();
    }

    @Test
    @DisplayName("isActiveCode는 미승인 코드에 false를 반환한다")
    void isActiveCode_unknownCode_returnsFalse() {
        // given
        when(storeCategoryRepository.existsByCodeAndActiveTrue("UNKNOWN")).thenReturn(false);

        // when & then
        assertThat(catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, "UNKNOWN")).isFalse();
    }

    @Test
    @DisplayName("isActiveCode는 null 코드에 저장소 조회 없이 false를 반환한다")
    void isActiveCode_nullCode_returnsFalse() {
        // when & then
        assertThat(catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, null)).isFalse();
    }

    @Test
    @DisplayName("findUnknownCodes는 미승인·비활성 코드만 반환한다")
    void findUnknownCodes_returnsOnlyUnknownCodes() {
        // given
        when(menuCategoryRepository.findByActiveTrueAndCodeIn(List.of("RICE", "XXX")))
                .thenReturn(List.of(MenuCategory.of("RICE", "밥요리", true, 1)));

        // when & then
        assertThat(catalogService.findUnknownCodes(CatalogKind.MENU_CATEGORY, List.of("RICE", "XXX")))
                .containsExactly("XXX");
    }

    @Test
    @DisplayName("findUnknownCodes는 전부 유효하면 빈 목록을 반환한다")
    void findUnknownCodes_allValid_returnsEmpty() {
        // given
        when(storeTagRepository.findByActiveTrueAndCodeIn(List.of("DATE", "QUIET"))).thenReturn(List.of(
                StoreTag.of("DATE", "데이트", true, 1),
                StoreTag.of("QUIET", "조용한", true, 2)));

        // when & then
        assertThat(catalogService.findUnknownCodes(CatalogKind.STORE_TAG, List.of("DATE", "QUIET"))).isEmpty();
    }

    @Test
    @DisplayName("findUnknownCodes는 빈 입력에 저장소 조회 없이 빈 목록을 반환한다")
    void findUnknownCodes_emptyInput_returnsEmpty() {
        // when & then
        assertThat(catalogService.findUnknownCodes(CatalogKind.STORE_TAG, List.of())).isEmpty();
    }

    @Test
    @DisplayName("findUnknownCodes는 null 원소를 잘못된 입력으로 거부한다")
    void findUnknownCodes_nullElement_throws() {
        // given
        java.util.List<String> withNull = java.util.Arrays.asList("RICE", null);

        // when & then
        assertThatThrownBy(() -> catalogService.findUnknownCodes(CatalogKind.MENU_CATEGORY, withNull))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
