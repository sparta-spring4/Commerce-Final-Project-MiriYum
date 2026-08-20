package com.miriyum.domain.auth.refreshtoken;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.jwt.TokenPair;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** JWT 발급과 Valkey 상태 회전의 순서를 한 곳에서 보장한다. */
@Component
public class RefreshTokenManager {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenManager.class);
    private static final Duration MAX_FAMILY_LIFETIME = Duration.ofDays(30);

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final RefreshTokenIdentityGenerator identityGenerator;
    private final Clock clock;

    public RefreshTokenManager(
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenStore refreshTokenStore,
            RefreshTokenIdentityGenerator identityGenerator,
            Clock clock
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.identityGenerator = identityGenerator;
        this.clock = clock;
    }

    public long captureSessionEpoch(TokenNamespace namespace, Long accountId) {
        return refreshTokenStore.currentSessionEpoch(namespace, accountId);
    }

    public TokenPair issue(TokenNamespace namespace, Long accountId) {
        return issue(namespace, accountId, captureSessionEpoch(namespace, accountId));
    }

    public TokenPair issue(TokenNamespace namespace, Long accountId, long expectedSessionEpoch) {
        Instant now = clock.instant();
        RefreshTokenIdentity identity = identityGenerator.generate();
        String accessToken = jwtTokenProvider.generateAccessToken(namespace, accountId);
        String refreshToken = jwtTokenProvider.generateRefreshToken(
                namespace, accountId, identity.familyId(), identity.tokenId());
        RefreshTokenCreationResult result = refreshTokenStore.create(new RefreshTokenState(
                namespace,
                accountId,
                identity.familyId(),
                identity.tokenId(),
                RefreshTokenHash.sha256(refreshToken),
                now,
                now.plusSeconds(jwtTokenProvider.getRefreshTokenValiditySeconds()),
                now,
                RefreshTokenState.Status.ACTIVE), expectedSessionEpoch);
        if (result.status() != RefreshTokenCreationResult.Status.CREATED) {
            throw new ServiceException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        return new TokenPair(accessToken, refreshToken);
    }

    public TokenPair rotate(TokenNamespace namespace, ParsedToken parsedToken, String rawRefreshToken) {
        RefreshTokenRotationAttempt attempt = attemptRotate(namespace, parsedToken, rawRefreshToken);
        if (!attempt.rotated()) {
            throw new ServiceException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        return attempt.tokenPair();
    }

    public RefreshTokenRotationAttempt attemptRotate(
            TokenNamespace namespace,
            ParsedToken parsedToken,
            String rawRefreshToken
    ) {
        Instant now = clock.instant();
        Instant nextFamilyExpiresAt = nextFamilyExpiresAt(now, parsedToken.familyCreatedAt());
        if (!nextFamilyExpiresAt.isAfter(now)) {
            return new RefreshTokenRotationAttempt(RefreshTokenRotationResult.Status.NOT_FOUND, null);
        }
        RefreshTokenIdentity nextIdentity = identityGenerator.generate();
        String nextAccessToken = jwtTokenProvider.generateAccessToken(namespace, parsedToken.accountId());
        String nextRefreshToken = parsedToken.familyCreatedAt() == null
                ? jwtTokenProvider.generateRefreshToken(
                        namespace, parsedToken.accountId(), parsedToken.familyId(), nextIdentity.tokenId())
                : jwtTokenProvider.generateRefreshToken(
                        namespace,
                        parsedToken.accountId(),
                        parsedToken.familyId(),
                        nextIdentity.tokenId(),
                        parsedToken.familyCreatedAt(),
                        Duration.between(now, nextFamilyExpiresAt));
        RefreshTokenRotationResult result = refreshTokenStore.rotate(
                namespace,
                parsedToken.familyId(),
                parsedToken.accountId(),
                parsedToken.tokenId(),
                RefreshTokenHash.sha256(rawRefreshToken),
                nextIdentity.tokenId(),
                RefreshTokenHash.sha256(nextRefreshToken),
                now,
                nextFamilyExpiresAt);
        TokenPair tokenPair = result.rotated() ? new TokenPair(nextAccessToken, nextRefreshToken) : null;
        if (result.rotated() && isAbsoluteLifetimeCapApplied(now, parsedToken.familyCreatedAt(), nextFamilyExpiresAt)) {
            log.info("event=refresh_token_absolute_lifetime_cap_applied");
        }
        return new RefreshTokenRotationAttempt(result.status(), tokenPair);
    }

    private Instant nextFamilyExpiresAt(Instant now, Instant familyCreatedAt) {
        Instant slidingExpiresAt = now.plusSeconds(jwtTokenProvider.getRefreshTokenValiditySeconds());
        if (familyCreatedAt == null) {
            return slidingExpiresAt;
        }
        Instant absoluteExpiresAt = familyCreatedAt.plus(MAX_FAMILY_LIFETIME);
        return slidingExpiresAt.isBefore(absoluteExpiresAt) ? slidingExpiresAt : absoluteExpiresAt;
    }

    private boolean isAbsoluteLifetimeCapApplied(
            Instant now,
            Instant familyCreatedAt,
            Instant nextFamilyExpiresAt
    ) {
        return familyCreatedAt != null
                && nextFamilyExpiresAt.equals(familyCreatedAt.plus(MAX_FAMILY_LIFETIME))
                && nextFamilyExpiresAt.isBefore(now.plusSeconds(jwtTokenProvider.getRefreshTokenValiditySeconds()));
    }

    public void revoke(TokenNamespace namespace, ParsedToken parsedToken) {
        refreshTokenStore.revoke(namespace, parsedToken.familyId(), parsedToken.accountId(), clock.instant());
    }

    /** 계정 정지·권한 회수·전체 로그인 종료 흐름에서 모든 family를 폐기한다. */
    public void revokeAll(TokenNamespace namespace, Long accountId) {
        Instant now = clock.instant();
        refreshTokenStore.revokeAll(
                namespace,
                accountId,
                now,
                now.plusSeconds(jwtTokenProvider.getRefreshTokenValiditySeconds()));
    }
}
