package com.miriyum.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class SecurityConfigPasswordEncoderTest {

    private static final String RAW_PASSWORD = "Password123!";

    private final PasswordEncoder passwordEncoder = new SecurityConfig().passwordEncoder();

    @Test
    @DisplayName("현재 비밀번호 해시 형식을 정상적으로 검증한다")
    void matchesCurrentPrefixedPasswordHash() {
        String encodedPassword = passwordEncoder.encode(RAW_PASSWORD);

        assertThat(encodedPassword).startsWith("{sha256-bcrypt}");
        assertThat(passwordEncoder.matches(RAW_PASSWORD, encodedPassword)).isTrue();
    }

    @Test
    @DisplayName("접두사가 없는 레거시 BCrypt 해시를 거부한다")
    void rejectsPrefixlessLegacyBcryptHash() {
        String legacyHash = new BCryptPasswordEncoder().encode(RAW_PASSWORD);

        assertThatThrownBy(() -> passwordEncoder.matches(RAW_PASSWORD, legacyHash))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("bcrypt 식별자가 붙은 레거시 해시를 거부한다")
    void rejectsExplicitLegacyBcryptHash() {
        String legacyHash = "{bcrypt}" + new BCryptPasswordEncoder().encode(RAW_PASSWORD);

        assertThatThrownBy(() -> passwordEncoder.matches(RAW_PASSWORD, legacyHash))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
