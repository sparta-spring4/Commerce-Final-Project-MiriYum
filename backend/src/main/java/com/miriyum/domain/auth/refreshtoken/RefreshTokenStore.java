package com.miriyum.domain.auth.refreshtoken;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Instant;

/** Refresh Token 상태를 저장소에 원자적으로 생성·회전·폐기하는 포트다. */
public interface RefreshTokenStore {

    void create(RefreshTokenState state);

    RefreshTokenRotationResult rotate(
            TokenNamespace namespace,
            String familyId,
            Long accountId,
            String expectedTokenId,
            String expectedTokenHash,
            String nextTokenId,
            String nextTokenHash,
            Instant now,
            Instant nextFamilyExpiresAt
    );

    void revoke(TokenNamespace namespace, String familyId, Long accountId, Instant now);

    void revokeAll(TokenNamespace namespace, Long accountId, Instant now);
}
