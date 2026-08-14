package com.miriyum.domain.platformoperator.session;

import java.time.Instant;

public interface PlatformOperatorSessionStore {
    PlatformOperatorSessionResult replaceActiveSession(PlatformOperatorSessionState state);

    PlatformOperatorSessionResult validateAndTouch(
            PlatformOperatorSessionProof proof, Instant now, Instant nextIdleExpiresAt);

    PlatformOperatorSessionResult rotate(
            PlatformOperatorSessionProof proof,
            String nextRefreshTokenId,
            String nextRefreshTokenHash,
            Instant now,
            Instant nextIdleExpiresAt);

    void revoke(Long accountId, String sessionHash);

    void revokeAll(Long accountId);
}
