package com.miriyum.domain.auth.refreshtoken;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.jwt.TokenPair;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** JWT 발급과 Valkey 상태 회전의 순서를 한 곳에서 보장한다. */
@Component
public class RefreshTokenManager {

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

    public TokenPair issue(TokenNamespace namespace, Long accountId) {
        Instant now = clock.instant();
        RefreshTokenIdentity identity = identityGenerator.generate();
        String accessToken = jwtTokenProvider.generateAccessToken(namespace, accountId);
        String refreshToken = jwtTokenProvider.generateRefreshToken(
                namespace, accountId, identity.familyId(), identity.tokenId());
        refreshTokenStore.create(new RefreshTokenState(
                namespace,
                accountId,
                identity.familyId(),
                identity.tokenId(),
                RefreshTokenHash.sha256(refreshToken),
                now.plusSeconds(jwtTokenProvider.getRefreshTokenValiditySeconds()),
                now,
                RefreshTokenState.Status.ACTIVE));
        return new TokenPair(accessToken, refreshToken);
    }

    public TokenPair rotate(TokenNamespace namespace, ParsedToken parsedToken, String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshTokenIdentity nextIdentity = identityGenerator.generate();
        String nextAccessToken = jwtTokenProvider.generateAccessToken(namespace, parsedToken.accountId());
        String nextRefreshToken = jwtTokenProvider.generateRefreshToken(
                namespace, parsedToken.accountId(), parsedToken.familyId(), nextIdentity.tokenId());
        Instant nextFamilyExpiresAt = now.plusSeconds(jwtTokenProvider.getRefreshTokenValiditySeconds());
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
        if (!result.rotated()) {
            throw new ServiceException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        return new TokenPair(nextAccessToken, nextRefreshToken);
    }

    public void revoke(TokenNamespace namespace, ParsedToken parsedToken) {
        refreshTokenStore.revoke(namespace, parsedToken.familyId(), parsedToken.accountId(), clock.instant());
    }
}
