package com.miriyum.domain.auth.jwt;

/**
 * 인증 성공 뒤 SecurityContext에 담기는 principal이다. 계정 유형과 계정 ID만 보관한다.
 */
public record AuthenticatedPrincipal(TokenNamespace namespace, Long accountId) {
}
