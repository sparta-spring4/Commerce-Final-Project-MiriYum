package com.miriyum.domain.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenNamespaceTest {

    @Test
    void consumerUsesConsumerAuthRootForCookiePath() {
        assertThat(TokenNamespace.CONSUMER.cookiePath())
                .isEqualTo("/api/v1/consumers/auth");
    }
}
