package com.miriyum.domain.auth.password;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * AUTH-006의 64 code point 계약이 BCrypt의 72 UTF-8 byte 한도 때문에 깨지지 않는지 검증한다.
 */
class Sha256BCryptPasswordEncoderTest {

    private final BCryptPasswordEncoder bcryptPasswordEncoder = new BCryptPasswordEncoder();
    private final Sha256BCryptPasswordEncoder passwordEncoder =
            new Sha256BCryptPasswordEncoder(bcryptPasswordEncoder);

    @Test
    @DisplayName("UTF-8로 72바이트를 넘는 비밀번호도 해싱하고 다시 검증할 수 있다")
    void encodesAndMatchesPasswordBeyondBcryptByteLimit() {
        // given: 한글 25자 + 영숫자 3자 = 28 code point, 78 UTF-8 byte(BCrypt 한도 초과)
        String rawPassword = "가".repeat(25) + "Aa1";
        assertThat(rawPassword.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);

        // when
        String encoded = passwordEncoder.encode(rawPassword);

        // then
        assertThat(passwordEncoder.matches(rawPassword, encoded)).isTrue();
    }

    @Test
    @DisplayName("72바이트를 넘고 앞 72바이트가 같은 두 비밀번호를 서로 다른 것으로 구분한다")
    void distinguishesLongPasswordsSharingTheFirstSeventyTwoBytes() {
        // given: 한글 24자가 정확히 72바이트라, 뒤에 붙는 "Aa1"/"Aa2"는 BCrypt 원문 비교에서
        // 무시되는 구간이다(BCrypt.checkpw는 길이 초과를 거부하지 않고 앞 72바이트만 반영한다).
        String rawPassword = "가".repeat(24) + "Aa1";
        String otherPassword = "가".repeat(24) + "Aa2";

        // when
        String encoded = passwordEncoder.encode(rawPassword);

        // then: 전처리가 원문 전체를 해시에 반영하므로 뒤쪽 한 글자 차이도 구분된다
        assertThat(passwordEncoder.matches(otherPassword, encoded)).isFalse();
    }

    @Test
    @DisplayName("64 code point를 꽉 채운 이모지 비밀번호도 해싱하고 검증할 수 있다")
    void encodesAndMatchesSupplementaryCharacterPassword() {
        // given: surrogate pair 이모지를 포함한 64 code point 비밀번호
        String rawPassword = "Aa1" + "b".repeat(60) + "😀";
        assertThat(rawPassword.codePointCount(0, rawPassword.length())).isEqualTo(64);

        // when
        String encoded = passwordEncoder.encode(rawPassword);

        // then
        assertThat(passwordEncoder.matches(rawPassword, encoded)).isTrue();
    }
}
