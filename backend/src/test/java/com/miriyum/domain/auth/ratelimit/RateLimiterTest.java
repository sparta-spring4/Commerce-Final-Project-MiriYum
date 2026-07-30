package com.miriyum.domain.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link RateLimiter}가 등급별 한도를 올바르게 고르고 저장소 결과를 올바르게 해석하는지만
 * 검증하는 순수 단위 테스트다. 실제 MySQL 원자적 upsert의 만료·경합 동작은
 * {@code RateLimiterTestcontainersTest}에서 진짜 MySQL로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    private static final Instant BASE_TIME = Instant.parse("2026-07-29T00:00:00Z");

    @Mock
    private RateLimitWindowRepository rateLimitWindowRepository;

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = newRateLimiter(Clock.fixed(BASE_TIME, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("저장소가 반환한 카운트가 한도 이하면 허용한다")
    void allowsWhenStoredCountWithinLimit() {
        // given: LOGIN 한도는 3인데 저장소가 2를 반환
        given(rateLimitWindowRepository.findById(anyString()))
                .willReturn(Optional.of(windowWithCount(2)));

        // when
        boolean allowed = rateLimiter.tryConsume(RateLimitCategory.LOGIN, "key");

        // then
        assertThat(allowed).isTrue();
        verify(rateLimitWindowRepository).upsertWindow(eq("LOGIN:key"), any(), any());
    }

    @Test
    @DisplayName("저장소가 반환한 카운트가 한도를 넘으면 거부한다")
    void rejectsWhenStoredCountExceedsLimit() {
        // given: LOGIN 한도는 3인데 저장소가 4를 반환
        given(rateLimitWindowRepository.findById(anyString()))
                .willReturn(Optional.of(windowWithCount(4)));

        // when
        boolean allowed = rateLimiter.tryConsume(RateLimitCategory.LOGIN, "key");

        // then
        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("등급마다 다른 한도를 독립적으로 적용한다")
    void appliesDifferentLimitPerCategory() {
        // given: TOKEN_REFRESH 한도(30)보다는 작지만 LOGIN 한도(3)보다는 큰 카운트
        given(rateLimitWindowRepository.findById(anyString()))
                .willReturn(Optional.of(windowWithCount(10)));

        // when & then
        assertThat(rateLimiter.tryConsume(RateLimitCategory.TOKEN_REFRESH, "key")).isTrue();
        assertThat(rateLimiter.tryConsume(RateLimitCategory.LOGIN, "key")).isFalse();
    }

    @Test
    @DisplayName("저장된 윈도우가 없으면 등급의 기본 윈도우 길이를 재시도 시간으로 반환한다")
    void returnsDefaultWindowLengthWhenNoRecordExists() {
        // given
        given(rateLimitWindowRepository.findById(anyString())).willReturn(Optional.empty());

        // when
        long retryAfter = rateLimiter.retryAfterSeconds(RateLimitCategory.LOGIN, "key");

        // then: LOGIN 윈도우 길이(600초)
        assertThat(retryAfter).isEqualTo(600);
    }

    @Test
    @DisplayName("남은 시간이 소수 초면 올림해서 Retry-After를 반환한다")
    void roundsUpFractionalRemainingSecondsForRetryAfter() {
        // given: 0.5초 남은 윈도우
        LocalDateTime expiresAt = LocalDateTime.ofInstant(BASE_TIME, ZoneOffset.UTC).plus(Duration.ofMillis(500));
        given(rateLimitWindowRepository.findById(anyString()))
                .willReturn(Optional.of(windowExpiringAt(expiresAt)));

        // when
        long retryAfter = rateLimiter.retryAfterSeconds(RateLimitCategory.LOGIN, "key");

        // then: 내림(0초)이 아니라 올림(1초)
        assertThat(retryAfter).isEqualTo(1);
    }

    private RateLimiter newRateLimiter(Clock clock) {
        return new RateLimiter(
                5, 600,
                3, 600,
                30, 60,
                60, 60,
                clock,
                rateLimitWindowRepository);
    }

    private RateLimitWindow windowWithCount(int count) {
        return new RateLimitWindow("key", LocalDateTime.ofInstant(BASE_TIME, ZoneOffset.UTC).plusMinutes(10), count);
    }

    private RateLimitWindow windowExpiringAt(LocalDateTime expiresAt) {
        return new RateLimitWindow("key", expiresAt, 1);
    }
}
