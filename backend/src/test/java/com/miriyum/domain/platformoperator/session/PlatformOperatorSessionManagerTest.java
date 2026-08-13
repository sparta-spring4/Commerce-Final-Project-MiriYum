package com.miriyum.domain.platformoperator.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PlatformOperatorSessionManagerTest {
    private final Instant now = Instant.parse("2026-08-13T00:00:00Z");
    private final PlatformOperatorSessionStore store = mock(PlatformOperatorSessionStore.class);
    private final JwtTokenProvider tokens = new JwtTokenProvider(
            "0123456789012345678901234567890123456789012345678901234567890123", "test",
            Clock.fixed(now, ZoneOffset.UTC));
    private final PlatformOperatorSessionManager manager = new PlatformOperatorSessionManager(
            tokens, store, new PlatformOperatorSessionPolicy(), Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void issuesSessionBoundTokensWithExactExpirations() {
        when(store.replaceActiveSession(any())).thenAnswer(invocation -> new PlatformOperatorSessionResult(
                PlatformOperatorSessionResult.Status.CREATED, invocation.getArgument(0)));

        var result = manager.issue(7L, 3L, 5L, true);
        var parsed = tokens.parseAccessToken(result.accessToken());

        assertThat(parsed.namespace()).isEqualTo(TokenNamespace.PLATFORM_OPERATOR);
        assertThat(parsed.sessionClaims().passwordChangeRequired()).isTrue();
        assertThat(result.expiresIn()).isEqualTo(900L);
        assertThat(result.idleExpiresAt()).isEqualTo(now.plusSeconds(1800));
        assertThat(result.absoluteExpiresAt()).isEqualTo(now.plusSeconds(28800));
    }
}
