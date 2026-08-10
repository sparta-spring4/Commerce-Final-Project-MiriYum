package com.miriyum.domain.store.search.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.auth.ratelimit.RateLimiter;
import com.miriyum.domain.store.search.config.StoreSearchSecurityConfig;
import com.miriyum.domain.store.search.dto.PublicMenu;
import com.miriyum.domain.store.search.dto.PublicStoreModes;
import com.miriyum.domain.store.search.dto.PublicStoreSummary;
import com.miriyum.domain.store.search.dto.ReservationAvailability;
import com.miriyum.domain.store.search.service.StorePublicQueryService;
import com.miriyum.domain.store.search.service.StoreSearchCoreService;
import com.miriyum.global.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StoreSearchController.class)
@Import({StoreSearchSecurityConfig.class, GlobalExceptionHandler.class})
class StoreSearchControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean StoreSearchCoreService searchService;
    @MockitoBean StorePublicQueryService publicQueryService;
    @MockitoBean RateLimiter rateLimiter;

    @BeforeEach
    void allowPublicStoreRequestsInControllerSlice() {
        given(rateLimiter.tryConsume(any(), any())).willReturn(RateLimiter.RateLimitResult.allow());
    }

    @Test
    void anonymousSearchReturnsOpenApiPageEnvelopeAndPassesInfantFlag() throws Exception {
        given(searchService.search(any(), eq(true))).willReturn(new PageImpl<>(
                List.of(new PublicStoreSummary(
                        "7", "미리윰", Region.SEOUL, "서울 중구", "CAFE_BAKERY",
                        OperationStatus.OPEN, new PublicStoreModes(true, true, true),
                        ReservationAvailability.AVAILABLE)),
                PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/stores")
                        .queryParam("serviceDate", "2026-08-03")
                        .queryParam("startTime", "18:00")
                        .queryParam("partySize", "2")
                        .queryParam("includesInfants", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].storeId").value("7"))
                .andExpect(jsonPath("$.data.items[0].reservationAvailability")
                        .value("AVAILABLE"))
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
    }

    @Test
    void detailRejectsPartialReservationConditionBeforeService() throws Exception {
        mockMvc.perform(get("/api/v1/stores/7")
                        .queryParam("serviceDate", "2026-08-03"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        then(publicQueryService).shouldHaveNoInteractions();
    }

    @Test
    void anonymousMenusReturnOnlyItemsEnvelope() throws Exception {
        given(publicQueryService.getMenus(7L)).willReturn(List.of(new PublicMenu(
                "11", "아메리카노", "", 4500, true, "COFFEE", List.of(), List.of(),
                true, true,
                com.miriyum.domain.store.menu.enums.MenuSellingStatus.SELLING)));

        mockMvc.perform(get("/api/v1/stores/7/menus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].menuId").value("11"))
                .andExpect(jsonPath("$.data.items[0].saleStatus").value("SELLING"));
    }

    @Test
    void nonGetStoreEndpointIsDeniedByPublicSearchChain() throws Exception {
        mockMvc.perform(post("/api/v1/stores"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
        then(searchService).shouldHaveNoInteractions();
        then(publicQueryService).shouldHaveNoInteractions();
    }
}
