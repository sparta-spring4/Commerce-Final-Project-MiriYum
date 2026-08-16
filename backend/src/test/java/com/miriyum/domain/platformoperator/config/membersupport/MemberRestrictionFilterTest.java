package com.miriyum.domain.platformoperator.config.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.RestrictedFeature;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

class MemberRestrictionFilterTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void readsRemainAllowedButRestrictedReservationWriteIsForbidden() throws Exception {
        MemberSanctionRepository sanctions = mock(MemberSanctionRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
        MemberRestrictionFilter filter = new MemberRestrictionFilter(
                sanctions, new MemberRestrictionPolicy(), new ObjectMapper(), clock);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(TokenNamespace.CONSUMER, 41L), null));
        when(sanctions.hasActiveFeatureRestriction(
                MemberAccountType.CONSUMER, 41, RestrictedFeature.RESERVATION.name(), clock.instant()))
                .thenReturn(true);

        var get = new MockHttpServletRequest("GET", "/api/v1/consumers/reservations/1");
        var getResponse = new MockHttpServletResponse();
        var getChain = mock(jakarta.servlet.FilterChain.class);
        filter.doFilter(get, getResponse, getChain);
        verify(getChain).doFilter(get, getResponse);

        var post = new MockHttpServletRequest("POST", "/api/v1/consumers/reservations");
        var postResponse = new MockHttpServletResponse();
        var postChain = mock(jakarta.servlet.FilterChain.class);
        filter.doFilter(post, postResponse, postChain);

        assertThat(postResponse.getStatus()).isEqualTo(403);
        assertThat(postResponse.getContentAsString()).contains("AUTH_011");
        verify(postChain, never()).doFilter(post, postResponse);
    }
}
