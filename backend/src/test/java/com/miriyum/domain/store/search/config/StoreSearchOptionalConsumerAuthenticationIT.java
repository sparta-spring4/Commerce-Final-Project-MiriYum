package com.miriyum.domain.store.search.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.ratelimit.RateLimiter;
import com.miriyum.domain.store.search.controller.StoreSearchController;
import com.miriyum.domain.store.search.dto.IntegratedStoreSearchData;
import com.miriyum.domain.store.search.dto.NormalizedSearchCondition;
import com.miriyum.domain.store.search.service.IntegratedStoreSearchService;
import com.miriyum.domain.store.search.service.StorePublicQueryService;
import com.miriyum.domain.store.search.service.StoreSearchCoreService;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StoreSearchController.class)
@Import({StoreSearchSecurityConfig.class, GlobalExceptionHandler.class})
@Tag("integration")
@Tag("integration-shard-b")
class StoreSearchOptionalConsumerAuthenticationIT {

    @Autowired MockMvc mockMvc;
    @MockitoBean StoreSearchCoreService searchService;
    @MockitoBean IntegratedStoreSearchService integratedSearchService;
    @MockitoBean StorePublicQueryService publicQueryService;
    @MockitoBean RateLimiter rateLimiter;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        given(rateLimiter.tryConsume(any(), any()))
                .willReturn(RateLimiter.RateLimitResult.allow());
    }

    @Test
    void missingHeaderUsesAnonymousRecommendation() throws Exception {
        given(integratedSearchService.search(
                null, "라멘", false, false, "recommendation,desc", null, 20))
                .willReturn(emptyResult());

        mockMvc.perform(get("/api/v1/stores")
                        .queryParam("searchInput", "라멘")
                        .queryParam("sort", "recommendation,desc"))
                .andExpect(status().isOk());

        then(integratedSearchService).should().search(
                null, "라멘", false, false, "recommendation,desc", null, 20);
    }

    @Test
    void validConsumerHeaderUsesAuthenticatedHistory() throws Exception {
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 41L));
        given(integratedSearchService.search(
                41L, "라멘", false, false, "recommendation,desc", null, 20))
                .willReturn(emptyResult());

        mockMvc.perform(get("/api/v1/stores")
                        .header("Authorization", "Bearer consumer-token")
                        .queryParam("searchInput", "라멘")
                        .queryParam("sort", "recommendation,desc"))
                .andExpect(status().isOk());
    }

    @Test
    void expiredAndRefreshBearerHeadersReturnTheirAuthErrors() throws Exception {
        given(jwtTokenProvider.parseAccessToken("expired-token"))
                .willThrow(new ServiceException(AuthErrorCode.ACCESS_TOKEN_EXPIRED));
        given(jwtTokenProvider.parseAccessToken("refresh-token"))
                .willThrow(new ServiceException(AuthErrorCode.ACCESS_TOKEN_INVALID));

        mockMvc.perform(get("/api/v1/stores")
                        .header("Authorization", "Bearer expired-token")
                        .queryParam("searchInput", "라멘"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_002"));
        mockMvc.perform(get("/api/v1/stores")
                        .header("Authorization", "Bearer refresh-token")
                        .queryParam("searchInput", "라멘"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_003"));

        then(integratedSearchService).shouldHaveNoInteractions();
    }

    @Test
    void tamperedAndOperatorBearerHeadersNeverFallBackToAnonymous() throws Exception {
        given(jwtTokenProvider.parseAccessToken("tampered-token"))
                .willThrow(new ServiceException(AuthErrorCode.ACCESS_TOKEN_INVALID));
        given(jwtTokenProvider.parseAccessToken("operator-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 9L));

        mockMvc.perform(get("/api/v1/stores")
                        .header("Authorization", "Bearer tampered-token")
                        .queryParam("searchInput", "라멘"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_003"));
        mockMvc.perform(get("/api/v1/stores")
                        .header("Authorization", "Bearer operator-token")
                        .queryParam("searchInput", "라멘"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));

        then(integratedSearchService).shouldHaveNoInteractions();
    }

    private static IntegratedStoreSearchData emptyResult() {
        return new IntegratedStoreSearchData(
                List.of(),
                new NormalizedSearchCondition(
                        List.of(), List.of(), List.of(), List.of(),
                        null, null, null, null, null, "라멘"),
                List.of(),
                "rule-v1",
                "catalog-v1",
                "history-v1",
                null);
    }
}
