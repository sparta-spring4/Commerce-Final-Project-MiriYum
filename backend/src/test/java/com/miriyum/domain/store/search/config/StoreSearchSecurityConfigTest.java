package com.miriyum.domain.store.search.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.auth.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class StoreSearchSecurityConfigTest {

    @Test
    void menuHoldAvailabilityGetUsesThePublicStoreReadLimiter() {
        var filter = new StoreSearchRateLimitFilter(
                mock(RateLimiter.class), mock(ObjectMapper.class));
        var request = new MockHttpServletRequest("GET",
                "/api/v1/stores/7/menu-hold-availability");

        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void menuHoldAvailabilityPostIsNotTreatedAsPublicRead() {
        var filter = new StoreSearchRateLimitFilter(
                mock(RateLimiter.class), mock(ObjectMapper.class));
        var request = new MockHttpServletRequest("POST",
                "/api/v1/stores/7/menu-hold-availability");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }
}
