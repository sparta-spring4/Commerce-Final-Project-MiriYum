package com.miriyum.domain.auth.jwt;

/**
 * 서명·만료·namespace·용도 검증을 마친 토큰에서 추출한 principal 정보다.
 */
public record ParsedToken(
        TokenNamespace namespace,
        Long accountId,
        String familyId,
        String tokenId,
        SessionTokenClaims sessionClaims
) {

    public ParsedToken(TokenNamespace namespace, Long accountId) {
        this(namespace, accountId, null, null, null);
    }

    public ParsedToken(TokenNamespace namespace, Long accountId, String familyId, String tokenId) {
        this(namespace, accountId, familyId, tokenId, null);
    }
}
