package com.miriyum.domain.search.controller.publicapi;

import static org.hamcrest.Matchers.nullValue;
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
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.search.config.StoreSearchSecurityConfig;
import com.miriyum.domain.search.dto.publicapi.IntegratedStoreSearchData;
import com.miriyum.domain.search.dto.publicapi.IntegratedStoreSearchItem;
import com.miriyum.domain.search.dto.publicapi.NormalizedSearchCondition;
import com.miriyum.domain.search.dto.publicapi.PublicMenu;
import com.miriyum.domain.search.dto.publicapi.PublicStoreCoordinates;
import com.miriyum.domain.search.dto.publicapi.PublicStoreModes;
import com.miriyum.domain.search.dto.publicapi.PublicStoreSummary;
import com.miriyum.domain.search.dto.publicapi.ReservationAvailability;
import com.miriyum.domain.search.service.IntegratedStoreSearchService;
import com.miriyum.domain.search.service.StorePublicQueryService;
import com.miriyum.domain.search.service.StoreSearchCoreService;
import com.miriyum.domain.recommendation.ranking.RecommendationReason;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import java.math.BigDecimal;
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
    @MockitoBean IntegratedStoreSearchService integratedSearchService;
    @MockitoBean StorePublicQueryService publicQueryService;
    @MockitoBean RateLimiter rateLimiter;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void allowPublicStoreRequestsInControllerSlice() {
        given(rateLimiter.tryConsume(any(), any())).willReturn(RateLimiter.RateLimitResult.allow());
    }

    @Test
    void anonymousSearchReturnsOpenApiPageEnvelopeAndPassesInfantFlag() throws Exception {
        given(searchService.search(any(), eq(true))).willReturn(new PageImpl<>(
                List.of(
                        new PublicStoreSummary(
                                "7", "미리윰", Region.SEOUL, "서울 중구", "CAFE_BAKERY",
                                OperationStatus.OPEN,
                                new PublicStoreModes(true, true, true),
                                ReservationAvailability.AVAILABLE,
                                new PublicStoreCoordinates(
                                        new BigDecimal("37.5665"),
                                        new BigDecimal("126.9780"))),
                        new PublicStoreSummary(
                                "8", "좌표 미확인", Region.SEOUL, "서울 종로구", "KOREAN",
                                OperationStatus.OPEN,
                                new PublicStoreModes(true, false, false),
                                ReservationAvailability.AVAILABLE)),
                PageRequest.of(0, 20), 2));

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
                .andExpect(jsonPath("$.data.items[0].coordinates.latitude")
                        .value(37.5665))
                .andExpect(jsonPath("$.data.items[0].coordinates.longitude")
                        .value(126.9780))
                .andExpect(jsonPath("$.data.items[1].coordinates").value(nullValue()))
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(2));
    }

    @Test
    void integratedSearchReturnsInterpretationCursorAndVerifiedCoordinates() throws Exception {
        given(integratedSearchService.search(
                null, "서울 라멘", false, false, null, null, 20))
                .willReturn(new IntegratedStoreSearchData(
                        List.of(new IntegratedStoreSearchItem(
                                "7", "라멘집", Region.SEOUL, "서울 중구", "KOREAN",
                                OperationStatus.OPEN,
                                new PublicStoreModes(true, true, false),
                                ReservationAvailability.NOT_REQUESTED,
                                new PublicStoreCoordinates(
                                        new BigDecimal("37.5665"),
                                        new BigDecimal("126.9780")),
                                RecommendationReason.KEYWORD)),
                        new NormalizedSearchCondition(
                                List.of("SEOUL"), List.of(), List.of(), List.of(),
                                null, null, null, null, null, "라멘"),
                        List.of(), "rule-v1", "catalog-v1", "history-v1", "next-cursor"));

        mockMvc.perform(get("/api/v1/stores")
                        .queryParam("searchInput", "서울 라멘")
                        .queryParam("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].storeId").value("7"))
                .andExpect(jsonPath("$.data.items[0].coordinates.latitude")
                        .value(37.5665))
                .andExpect(jsonPath("$.data.normalizedCondition.regionCodes[0]")
                        .value("SEOUL"))
                .andExpect(jsonPath("$.data.ruleVersion").value("rule-v1"))
                .andExpect(jsonPath("$.data.rankingRuleVersion").value("history-v1"))
                .andExpect(jsonPath("$.data.items[0].recommendationReason.code")
                        .value("KEYWORD_MATCH"))
                .andExpect(jsonPath("$.data.nextCursor").value("next-cursor"));
        then(searchService).shouldHaveNoInteractions();
    }

    @Test
    void validConsumerBearerPassesAccountIdToIntegratedSearch() throws Exception {
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 41L));
        given(integratedSearchService.search(
                41L, "라멘", false, false, "recommendation,desc", null, 20))
                .willReturn(new IntegratedStoreSearchData(
                        List.of(),
                        new NormalizedSearchCondition(
                                List.of(), List.of(), List.of(), List.of(),
                                null, null, null, null, null, "라멘"),
                        List.of(), "rule-v1", "catalog-v1", "history-v1", null));

        mockMvc.perform(get("/api/v1/stores")
                        .header("Authorization", "Bearer consumer-token")
                        .queryParam("searchInput", "라멘")
                        .queryParam("sort", "recommendation,desc"))
                .andExpect(status().isOk());

        then(integratedSearchService).should().search(
                41L, "라멘", false, false, "recommendation,desc", null, 20);
    }

    @Test
    void malformedOrWrongNamespaceBearerIsRejectedInsteadOfFallingBackToAnonymous()
            throws Exception {
        given(jwtTokenProvider.parseAccessToken("operator-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 9L));

        mockMvc.perform(get("/api/v1/stores")
                        .header("Authorization", "Basic invalid")
                        .queryParam("searchInput", "라멘"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
        mockMvc.perform(get("/api/v1/stores")
                        .header("Authorization", "Bearer operator-token")
                        .queryParam("searchInput", "라멘"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));

        then(integratedSearchService).shouldHaveNoInteractions();
    }

    @Test
    void integratedSearchRejectsLegacyParametersAndInvalidRawInput() throws Exception {
        mockMvc.perform(get("/api/v1/stores")
                        .queryParam("searchInput", "서울 라멘")
                        .queryParam("keyword", "라멘"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        mockMvc.perform(get("/api/v1/stores")
                        .queryParam("searchInput", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        mockMvc.perform(get("/api/v1/stores")
                        .queryParam("searchInput", "가".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        then(integratedSearchService).shouldHaveNoInteractions();
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
                com.miriyum.domain.menu.enums.MenuSellingStatus.SELLING)));

        mockMvc.perform(get("/api/v1/stores/7/menus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].menuId").value("11"))
                .andExpect(jsonPath("$.data.items[0].imageUrl").isEmpty())
                .andExpect(jsonPath("$.data.items[0].saleStatus").value("SELLING"));
    }

    @Test
    void invalidBearerDoesNotChangePublicMenuContract() throws Exception {
        given(jwtTokenProvider.parseAccessToken("invalid-token"))
                .willThrow(new ServiceException(AuthErrorCode.ACCESS_TOKEN_INVALID));
        given(publicQueryService.getMenus(7L)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/stores/7/menus")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
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
