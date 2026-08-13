package com.miriyum.domain.auth.refreshtoken;

/**
 * 한 로그인 흐름과 그 안의 현재 Refresh Token을 구분하는 불투명 식별자 쌍이다.
 */
public record RefreshTokenIdentity(String familyId, String tokenId) {

    public RefreshTokenIdentity {
        if (familyId == null || familyId.isBlank()) {
            throw new IllegalArgumentException("familyId must not be blank");
        }
        if (tokenId == null || tokenId.isBlank()) {
            throw new IllegalArgumentException("tokenId must not be blank");
        }
    }
}
