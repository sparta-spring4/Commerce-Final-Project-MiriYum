package com.miriyum.domain.auth.cookie;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OriginValidatorTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    private final OriginValidator originValidator = new OriginValidator(ALLOWED_ORIGIN);

    @Test
    @DisplayName("Origin 헤더가 허용된 값과 정확히 같으면 통과한다")
    void acceptsExactOriginMatch() {
        // given & when
        boolean result = originValidator.isSameOrigin(ALLOWED_ORIGIN, null);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Origin 헤더가 허용된 값과 다르면 거부한다")
    void rejectsDifferentOrigin() {
        // given & when
        boolean result = originValidator.isSameOrigin("http://evil.example", null);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Origin이 없을 때 Referer가 scheme·host·port까지 같으면 통과한다")
    void acceptsRefererFallbackWithMatchingAuthority() {
        // given & when
        boolean result = originValidator.isSameOrigin(null, "http://localhost:5173/some/path");

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("허용된 Origin을 접두사로 갖는 다른 호스트의 Referer는 거부한다")
    void rejectsRefererWithAllowedOriginAsPrefix() {
        // given & when
        boolean result = originValidator.isSameOrigin(null, "http://localhost:5173.evil.example/path");

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("호스트는 같지만 포트가 다른 Referer는 거부한다")
    void rejectsRefererWithDifferentPort() {
        // given & when
        boolean result = originValidator.isSameOrigin(null, "http://localhost:4173/path");

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("scheme이 다른 Referer는 거부한다")
    void rejectsRefererWithDifferentScheme() {
        // given & when
        boolean result = originValidator.isSameOrigin(null, "https://localhost:5173/path");

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("파싱할 수 없는 Referer는 fail-closed로 거부한다")
    void rejectsUnparsableReferer() {
        // given & when
        boolean result = originValidator.isSameOrigin(null, "not a uri");

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Origin과 Referer가 모두 없으면 거부한다")
    void rejectsWhenNeitherHeaderPresent() {
        // given & when
        boolean result = originValidator.isSameOrigin(null, null);

        // then
        assertThat(result).isFalse();
    }
}
