package com.miriyum.domain.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenNamespaceTest {

    @Test
    void consumerUsesConsumerAuthRootForCookiePath() {
        assertThat(TokenNamespace.CONSUMER.cookiePath())
                .isEqualTo("/api/v1/consumers/auth");
    }

    @Test
    void storeOperatorUsesStoreOperatorAuthRootForCookiePath() {
        assertThat(TokenNamespace.STORE_OPERATOR.cookiePath())
                .isEqualTo("/api/v1/store-operators/auth");
    }

    @Test
    void platformOperatorUsesAnIndependentNamespaceAndCookieBoundary() {
        assertThat(TokenNamespace.PLATFORM_OPERATOR.value()).isEqualTo("platform-operator");
        assertThat(TokenNamespace.PLATFORM_OPERATOR.refreshCookieName())
                .isEqualTo("MIRIYUM_PLATFORM_OPERATOR_REFRESH");
        assertThat(TokenNamespace.PLATFORM_OPERATOR.csrfCookieName())
                .isEqualTo("MIRIYUM_PLATFORM_OPERATOR_XSRF_TOKEN");
        assertThat(TokenNamespace.PLATFORM_OPERATOR.cookiePath())
                .isEqualTo("/api/v1/platform-operators/auth");
    }
}
