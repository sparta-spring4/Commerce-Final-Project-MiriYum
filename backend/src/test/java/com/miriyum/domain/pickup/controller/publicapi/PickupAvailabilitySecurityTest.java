package com.miriyum.domain.pickup.controller.publicapi;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.ratelimit.RateLimiter;
import com.miriyum.domain.pickup.config.PickupSecurityConfig;
import com.miriyum.domain.pickup.dto.response.PickupAvailability;
import com.miriyum.domain.pickup.service.PickupAvailabilityService;
import com.miriyum.domain.search.config.StoreSearchSecurityConfig;
import com.miriyum.global.exception.GlobalExceptionHandler;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PickupAvailabilityController.class)
@Import({
        PickupSecurityConfig.class,
        StoreSearchSecurityConfig.class,
        GlobalExceptionHandler.class
})
class PickupAvailabilitySecurityTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean PickupAvailabilityService availabilityService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean RateLimiter rateLimiter;

    @BeforeEach
    void allowStoreRateLimit() {
        given(rateLimiter.tryConsume(any(), any()))
                .willReturn(RateLimiter.RateLimitResult.allow());
    }

    @Test
    void anonymousClientCanReadPickupAvailabilityDespiteStoreSearchChain() throws Exception {
        LocalDate pickupDate = LocalDate.of(2026, 8, 10);
        given(availabilityService.getAvailability(7L, pickupDate))
                .willReturn(new PickupAvailability("7", pickupDate, List.of()));

        mockMvc.perform(get("/api/v1/stores/7/pickup-availability")
                        .queryParam("pickupDate", "2026-08-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.storeId").value("7"));
    }
}
