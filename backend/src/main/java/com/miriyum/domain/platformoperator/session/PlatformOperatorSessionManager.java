package com.miriyum.domain.platformoperator.session;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.SessionTokenClaims;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.platformoperator.dto.auth.PlatformOperatorTokenResult;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorSessionManager {
    private final JwtTokenProvider tokens;
    private final PlatformOperatorSessionStore store;
    private final PlatformOperatorSessionPolicy policy;
    private final Clock clock;

    public PlatformOperatorSessionManager(
            JwtTokenProvider tokens,
            PlatformOperatorSessionStore store,
            PlatformOperatorSessionPolicy policy,
            Clock clock) {
        this.tokens = tokens;
        this.store = store;
        this.policy = policy;
        this.clock = clock;
    }

    public PlatformOperatorTokenResult issue(
            Long accountId, long authorityVersion, long sessionVersion, boolean passwordChangeRequired) {
        Instant now = clock.instant();
        Instant idle = policy.idleExpiresAt(now);
        Instant absolute = policy.absoluteExpiresAt(now);
        String sessionId = UUID.randomUUID().toString();
        String tokenId = UUID.randomUUID().toString();
        SessionTokenClaims claims = new SessionTokenClaims(
                sessionId, authorityVersion, sessionVersion, passwordChangeRequired);
        String refresh = tokens.generateRefreshToken(
                TokenNamespace.PLATFORM_OPERATOR, accountId, sessionId, tokenId, claims);
        PlatformOperatorSessionState state = new PlatformOperatorSessionState(
                accountId, sha256(sessionId), tokenId, sha256(refresh), now, now, idle, absolute,
                authorityVersion, sessionVersion, passwordChangeRequired);
        PlatformOperatorSessionResult created = store.replaceActiveSession(state);
        if (created.status() != PlatformOperatorSessionResult.Status.CREATED) throw invalidSession();
        String access = tokens.generateAccessToken(TokenNamespace.PLATFORM_OPERATOR, accountId, claims);
        return result(access, refresh, passwordChangeRequired, idle, absolute);
    }

    public ParsedToken parseAccess(String accessToken) {
        ParsedToken parsed = tokens.parseAccessToken(accessToken);
        requirePlatform(parsed, AuthErrorCode.ACCESS_TOKEN_INVALID);
        return parsed;
    }

    public ParsedToken parseRefresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ServiceException(AuthErrorCode.REFRESH_TOKEN_REQUIRED);
        }
        ParsedToken parsed = tokens.parseRefreshToken(refreshToken);
        requirePlatform(parsed, AuthErrorCode.REFRESH_TOKEN_INVALID);
        return parsed;
    }

    public void validateAndTouch(ParsedToken parsed) {
        Instant now = clock.instant();
        SessionTokenClaims claims = parsed.sessionClaims();
        PlatformOperatorSessionResult checked = store.validateAndTouch(
                proof(parsed, null), now, policy.idleExpiresAt(now));
        if (checked.status() != PlatformOperatorSessionResult.Status.VALID) throw invalidSession();
    }

    public PlatformOperatorTokenResult rotate(ParsedToken parsed, String refreshToken) {
        Instant now = clock.instant();
        SessionTokenClaims claims = parsed.sessionClaims();
        String nextId = UUID.randomUUID().toString();
        String nextRefresh = tokens.generateRefreshToken(
                TokenNamespace.PLATFORM_OPERATOR, parsed.accountId(), parsed.familyId(), nextId, claims);
        Instant nextIdle = policy.idleExpiresAt(now);
        PlatformOperatorSessionResult rotated = store.rotate(
                proof(parsed, refreshToken), nextId, sha256(nextRefresh), now, nextIdle);
        if (rotated.status() != PlatformOperatorSessionResult.Status.ROTATED) {
            throw new ServiceException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        String access = tokens.generateAccessToken(TokenNamespace.PLATFORM_OPERATOR, parsed.accountId(), claims);
        Instant absolute = rotated.state() == null
                ? nextIdle
                : rotated.state().absoluteExpiresAt();
        nextIdle = rotated.state() == null ? nextIdle : rotated.state().idleExpiresAt();
        return result(access, nextRefresh, claims.passwordChangeRequired(), nextIdle, absolute);
    }

    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return;
        ParsedToken parsed = tokens.parseRefreshTokenForLogout(refreshToken);
        if (parsed == null || parsed.namespace() != TokenNamespace.PLATFORM_OPERATOR) return;
        store.revoke(parsed.accountId(), sha256(parsed.sessionClaims().sessionId()));
    }

    public void revokeAll(Long accountId) {
        store.revokeAll(accountId);
    }

    public void revoke(ParsedToken parsed) {
        store.revoke(parsed.accountId(), sha256(parsed.sessionClaims().sessionId()));
    }

    private PlatformOperatorSessionProof proof(ParsedToken parsed, String rawRefresh) {
        SessionTokenClaims claims = parsed.sessionClaims();
        return new PlatformOperatorSessionProof(parsed.accountId(), sha256(claims.sessionId()), parsed.tokenId(),
                rawRefresh == null ? null : sha256(rawRefresh), claims.authorityVersion(), claims.sessionVersion());
    }

    private PlatformOperatorTokenResult result(
            String access, String refresh, boolean required, Instant idle, Instant absolute) {
        return new PlatformOperatorTokenResult(access, refresh, "Bearer", tokens.getAccessTokenValiditySeconds(),
                required, idle, absolute);
    }

    private void requirePlatform(ParsedToken parsed, AuthErrorCode code) {
        if (parsed.namespace() != TokenNamespace.PLATFORM_OPERATOR || parsed.sessionClaims() == null) {
            throw new ServiceException(code);
        }
    }

    private ServiceException invalidSession() {
        return new ServiceException(AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
