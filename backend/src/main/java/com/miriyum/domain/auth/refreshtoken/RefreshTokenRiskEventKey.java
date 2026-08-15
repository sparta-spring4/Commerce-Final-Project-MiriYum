package com.miriyum.domain.auth.refreshtoken;

import com.miriyum.domain.auth.jwt.TokenNamespace;

/** Refresh Token 재사용 위험 사건 marker의 Valkey 키 규칙을 관리한다. */
public final class RefreshTokenRiskEventKey {

    private static final String PREFIX = "auth:risk:pending:";
    private static final String OCCURRENCE_COUNTER_PREFIX = "auth:risk:occurrence:";
    private static final String PENDING_INDEX_KEY = "auth:risk:pending-index";

    private RefreshTokenRiskEventKey() {
    }

    public static String forReuse(TokenNamespace namespace, String familyId, String tokenHash) {
        validateReuseIdentity(namespace, familyId, tokenHash);
        return PREFIX + namespace.value() + ":" + familyId + ":" + tokenHash;
    }

    public static String pendingPattern() {
        // deploy.sh의 전진 배포 backfill도 같은 marker 패턴을 사용한다.
        return PREFIX + "*";
    }

    /** Marker가 전달 후 삭제되어도 같은 Refresh Token 재사용 횟수를 family 만료까지 단조 증가시킨다. */
    public static String occurrenceCounter(TokenNamespace namespace, String familyId, String tokenHash) {
        validateReuseIdentity(namespace, familyId, tokenHash);
        return OCCURRENCE_COUNTER_PREFIX + namespace.value() + ":" + familyId + ":" + tokenHash;
    }

    public static String pendingIndex() {
        return PENDING_INDEX_KEY;
    }

    private static void validateReuseIdentity(TokenNamespace namespace, String familyId, String tokenHash) {
        if (namespace == null || familyId == null || familyId.isBlank()
                || tokenHash == null || tokenHash.length() != 64) {
            throw new IllegalArgumentException("namespace, familyId and tokenHash are required");
        }
    }
}
