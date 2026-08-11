package com.miriyum.domain.auth.jwt;

/**
 * 계정 유형별 JWT namespace다. 일반 사용자와 매장 운영자는 principal·토큰을 공유하지 않는다.
 * Refresh·CSRF 쿠키 이름과 Path도 namespace별로 분리해 같은 브라우저 안에서 두 shell의
 * 쿠키가 혼용되지 않게 한다 ({@code docs/specs/auth-account/spec.md} 토큰과 브라우저 계약).
 */
public enum TokenNamespace {

    CONSUMER(
            "consumer",
            "MIRIYUM_CONSUMER_REFRESH",
            "MIRIYUM_CONSUMER_XSRF_TOKEN",
            "/api/v1/consumers/auth"
    ),
    STORE_OPERATOR(
            "store-operator",
            "MIRIYUM_STORE_OPERATOR_REFRESH",
            "MIRIYUM_STORE_OPERATOR_XSRF_TOKEN",
            "/api/v1/store-operators/auth"
    );

    private final String value;
    private final String refreshCookieName;
    private final String csrfCookieName;
    private final String cookiePath;

    TokenNamespace(String value, String refreshCookieName, String csrfCookieName, String cookiePath) {
        this.value = value;
        this.refreshCookieName = refreshCookieName;
        this.csrfCookieName = csrfCookieName;
        this.cookiePath = cookiePath;
    }

    public String value() {
        return value;
    }

    public String refreshCookieName() {
        return refreshCookieName;
    }

    public String csrfCookieName() {
        return csrfCookieName;
    }

    public String cookiePath() {
        return cookiePath;
    }

    public static TokenNamespace fromValue(String value) {
        for (TokenNamespace namespace : values()) {
            if (namespace.value.equals(value)) {
                return namespace;
            }
        }
        throw new IllegalArgumentException("Unknown token namespace: " + value);
    }
}
