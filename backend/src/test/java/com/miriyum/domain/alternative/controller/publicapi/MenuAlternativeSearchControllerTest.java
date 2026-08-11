package com.miriyum.domain.alternative.controller.publicapi;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.alternative.model.MenuAlternativeMode;
import com.miriyum.domain.alternative.model.MenuAlternativeResult;
import com.miriyum.domain.alternative.service.MenuAlternativeSearchService;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.ratelimit.RateLimitCategory;
import com.miriyum.domain.auth.ratelimit.RateLimiter;
import com.miriyum.domain.search.config.StoreSearchSecurityConfig;
import com.miriyum.global.exception.GlobalExceptionHandler;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MenuAlternativeSearchController.class)
@Import({StoreSearchSecurityConfig.class, GlobalExceptionHandler.class})
class MenuAlternativeSearchControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean MenuAlternativeSearchService service;
    @MockitoBean RateLimiter rateLimiter;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void allowRateLimit() {
        given(rateLimiter.tryConsume(any(), any())).willReturn(RateLimiter.RateLimitResult.allow());
    }

    @Test
    void anonymousPostUsesJsonBodyAndPublicStoreReadLimit() throws Exception {
        given(service.search(anyLong(), anyLong(), any())).willReturn(
                new MenuAlternativeResult(7L, 9L, 2,
                        OffsetDateTime.parse("2026-08-15T18:30+09:00"),
                        OffsetDateTime.parse("2026-08-15T20:00+09:00"), "Asia/Seoul",
                        MenuAlternativeMode.NO_ALTERNATIVE, List.of()));

        mockMvc.perform(post("/api/v1/stores/7/menus/9/alternatives/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2,"serviceDate":"2026-08-15","startTime":"18:30",
                                 "startOffset":"+09:00","partySize":2,
                                 "excludedAllergenCodes":["MILK"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceStoreId").value("7"))
                .andExpect(jsonPath("$.data.mode").value("NO_ALTERNATIVE"))
                .andExpect(jsonPath("$.data.items").isEmpty());

        then(rateLimiter).should().tryConsume(RateLimitCategory.PUBLIC_STORE_READ, "127.0.0.1");
    }

    @Test
    void rejectsSecondPrecisionAndUnknownAllergenBeforeService() throws Exception {
        mockMvc.perform(post("/api/v1/stores/7/menus/9/alternatives/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2,"serviceDate":"2026-08-15","startTime":"18:30:01",
                                 "partySize":2,"excludedAllergenCodes":["UNKNOWN"]}
                                """))
                .andExpect(status().isBadRequest());
        then(service).should(never()).search(anyLong(), anyLong(), any());
    }
}
