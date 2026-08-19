package com.miriyum.domain.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import jakarta.servlet.FilterChain;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedPrincipalCarriesVerifiedAccessTokenExpiry() throws Exception {
        JwtTokenProvider tokens = mock(JwtTokenProvider.class);
        Instant expiresAt = Instant.parse("2026-08-19T01:15:00Z");
        given(tokens.parseAccessToken("access-token"))
                .willReturn(new ParsedToken(
                        TokenNamespace.CONSUMER, 41L, null, null, null, null, expiresAt));
        JwtAuthenticationFilter filter =
                new JwtAuthenticationFilter(tokens, TokenNamespace.CONSUMER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer access-token");

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        AuthenticatedPrincipal principal = (AuthenticatedPrincipal) SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        assertThat(principal.namespace()).isEqualTo(TokenNamespace.CONSUMER);
        assertThat(principal.accountId()).isEqualTo(41L);
        assertThat(principal.accessTokenExpiresAt()).isEqualTo(expiresAt);
    }
}
