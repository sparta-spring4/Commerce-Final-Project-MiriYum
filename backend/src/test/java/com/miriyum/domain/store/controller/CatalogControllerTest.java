package com.miriyum.domain.store.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.store.service.CatalogItemView;
import com.miriyum.domain.store.service.CatalogKind;
import com.miriyum.domain.store.service.CatalogService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * catalog 조회 응답의 형태·직렬화를 검증한다.
 *
 * <p>standalone MockMvc는 Security 필터를 포함하지 않으므로 이 테스트는 컨트롤러 매핑과 공통 봉투
 * 직렬화만 검증한다. 실제 익명 접근 허용(permitAll)은 인증 도메인의 `SecurityFilterChain` 통합 검증에서
 * 다룬다.</p>
 */
class CatalogControllerTest {

    private final CatalogService catalogService = mock(CatalogService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CatalogController(catalogService))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("매장 카테고리를 공통 봉투로 응답한다")
    void getStoreCategories_returnsEnvelope() throws Exception {
        // given
        when(catalogService.getItems(CatalogKind.STORE_CATEGORY)).thenReturn(List.of(
                new CatalogItemView("KOREAN", "한식"),
                new CatalogItemView("CAFE_BAKERY", "카페·베이커리")));

        // when & then
        mockMvc.perform(get("/api/v1/store-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].code").value("KOREAN"))
                .andExpect(jsonPath("$.data.items[0].displayName").value("한식"))
                .andExpect(jsonPath("$.data.items[1].code").value("CAFE_BAKERY"))
                .andExpect(jsonPath("$.data.items[0].id").doesNotExist());
    }

    @Test
    @DisplayName("메뉴 카테고리를 공통 봉투로 응답한다")
    void getMenuCategories_returnsEnvelope() throws Exception {
        // given
        when(catalogService.getItems(CatalogKind.MENU_CATEGORY)).thenReturn(List.of(
                new CatalogItemView("RICE", "밥요리")));

        // when & then
        mockMvc.perform(get("/api/v1/menu-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].code").value("RICE"))
                .andExpect(jsonPath("$.data.items[0].displayName").value("밥요리"));
    }

    @Test
    @DisplayName("매장 태그를 공통 봉투로 응답한다")
    void getStoreTags_returnsEnvelope() throws Exception {
        // given
        when(catalogService.getItems(CatalogKind.STORE_TAG)).thenReturn(List.of(
                new CatalogItemView("DATE", "데이트")));

        // when & then
        mockMvc.perform(get("/api/v1/store-tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].code").value("DATE"))
                .andExpect(jsonPath("$.data.items[0].displayName").value("데이트"));
    }
}
