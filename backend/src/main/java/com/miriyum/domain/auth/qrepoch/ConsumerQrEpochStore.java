package com.miriyum.domain.auth.qrepoch;

import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Instant;

interface ConsumerQrEpochStore {

    ConsumerQrEpochSnapshot captureCurrent(Long accountId);

    boolean isCurrent(Long accountId, String opaqueVersion);

    ConsumerQrEpochAdvanceResult advanceForLogout(
            TokenNamespace namespace,
            ParsedToken refreshToken,
            String rawRefreshToken,
            Instant now
    );
}
