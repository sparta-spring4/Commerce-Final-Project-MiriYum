package com.miriyum.domain.platformoperator.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuthEventRecorder;
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
            tokens, store, new PlatformOperatorSessionPolicy(), Clock.fixed(now, ZoneOffset.UTC),
            mock(PlatformOperatorAuthEventRecorder.class));

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

    @Test
    void refusesRotationWhenAtomicStoreResultHasNoExpiryContract() {
        String refresh = tokens.generateRefreshToken(TokenNamespace.PLATFORM_OPERATOR, 7L, "session-id", "token-id",
                new com.miriyum.domain.auth.jwt.SessionTokenClaims("session-id", 3L, 5L, true));
        var parsed = tokens.parseRefreshToken(refresh);
        when(store.rotate(any(), any(), any(), any(), any()))
                .thenReturn(PlatformOperatorSessionResult.of(PlatformOperatorSessionResult.Status.ROTATED));

        assertThatThrownBy(() -> manager.rotate(parsed, refresh))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void refusesStaleSessionIssueThatWouldDowngradeAnActiveSession() {
        when(store.replaceActiveSession(any()))
                .thenReturn(PlatformOperatorSessionResult.of(PlatformOperatorSessionResult.Status.STALE));

        assertThatThrownBy(() -> manager.issue(7L, 3L, 5L, true))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID);
    }
}
