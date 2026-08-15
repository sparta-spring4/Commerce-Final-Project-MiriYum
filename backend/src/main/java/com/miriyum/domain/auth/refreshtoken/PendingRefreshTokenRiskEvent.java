package com.miriyum.domain.auth.refreshtoken;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Instant;

/** Valkey에 남아 MySQL 전달을 기다리는 원문 없는 Refresh Token 재사용 위험 사건이다. */
public record PendingRefreshTokenRiskEvent(
        String eventKey,
        TokenNamespace namespace,
        Long accountId,
        String familyId,
        String tokenHash,
        String sourceEvent,
        String originEvent,
        String policyVersion,
        Instant occurredAt,
        long occurrenceCount,
        Instant lastOccurredAt,
        String generation
) {
}
