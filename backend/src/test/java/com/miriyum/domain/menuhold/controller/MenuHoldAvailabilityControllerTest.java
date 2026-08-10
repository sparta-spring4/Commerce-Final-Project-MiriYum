package com.miriyum.domain.menuhold.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.ratelimit.RateLimiter;
import com.miriyum.domain.menuhold.controller.dto.MenuHoldAvailabilityResponse;
import com.miriyum.domain.menuhold.service.MenuHoldAvailabilityQueryService;
import com.miriyum.domain.search.config.StoreSearchSecurityConfig;
import com.miriyum.global.exception.GlobalExceptionHandler;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MenuHoldAvailabilityController.class)
@Import({StoreSearchSecurityConfig.class, GlobalExceptionHandler.class})
class MenuHoldAvailabilityControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean MenuHoldAvailabilityQueryService queryService;
    @MockitoBean RateLimiter rateLimiter;

    @BeforeEach
    void allowRateLimit() {
        given(rateLimiter.tryConsume(any(), any())).willReturn(RateLimiter.RateLimitResult.allow());
    }

    @Test
    void anonymousRequestReturnsServerResolvedInterval() throws Exception {
        given(queryService.findAvailability(7L, LocalDate.of(2026, 8, 10),
                java.time.LocalTime.of(18, 0), java.time.ZoneOffset.ofHours(9)))
                .willReturn(new MenuHoldAvailabilityResponse(LocalDate.of(2026, 8, 10),
                        OffsetDateTime.parse("2026-08-10T18:00+09:00"),
                        OffsetDateTime.parse("2026-08-10T19:30+09:00"), "Asia/Seoul", List.of()));

        mockMvc.perform(get("/api/v1/stores/7/menu-hold-availability")
                        .queryParam("serviceDate", "2026-08-10")
                        .queryParam("startTime", "18:00")
                        .queryParam("startOffset", "+09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serviceDate").value("2026-08-10"))
                .andExpect(jsonPath("$.data.startAt").value("2026-08-10T18:00:00+09:00"))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void missingRequiredTimeIsRejectedBeforeService() throws Exception {
        mockMvc.perform(get("/api/v1/stores/7/menu-hold-availability")
                        .queryParam("serviceDate", "2026-08-10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Z", "+09", "+0900"})
    void nonCanonicalStartOffsetIsRejectedBeforeService(String startOffset) throws Exception {
        mockMvc.perform(get("/api/v1/stores/7/menu-hold-availability")
                        .queryParam("serviceDate", "2026-08-10")
                        .queryParam("startTime", "18:00")
                        .queryParam("startOffset", startOffset))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        verifyNoInteractions(queryService);
    }
}
