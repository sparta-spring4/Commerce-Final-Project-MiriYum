package com.miriyum.domain.auth.refreshtoken;

import com.miriyum.domain.auth.jwt.TokenNamespace;

/**
 * Refresh Token family의 Valkey 키 형식을 한 곳에서 관리한다.
 */
public final class RefreshTokenKey {

    private RefreshTokenKey() {
    }

    public static String forFamily(TokenNamespace namespace, String familyId) {
        if (namespace == null || familyId == null || familyId.isBlank()) {
            throw new IllegalArgumentException("namespace and familyId are required");
        }
        return "auth:refresh:" + namespace.value() + ":" + familyId;
    }
}
