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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link RateLimiter}가 등급별 한도를 올바르게 고르고 저장소 결과를 올바르게 해석하는지만
 * 검증하는 순수 단위 테스트다. 실제 MySQL 원자적 upsert의 만료·경합 동작은
 * {@code RateLimiterTestcontainersTest}에서 진짜 MySQL로 검증한다.
 *
 * <p>{@link #newRateLimiter(Clock)}는 {@code docs/service-policies/18-scale-reliability.md}
 * SCALE-005의 2026-07-30 결정 수치(회원가입·로그인 5/600, 재발급 30/60, CSRF 준비 60/60)를
 * 그대로 쓴다. 실제 기본값과 다른 수치를 쓰면 경계값 테스트가 운영 설정과 어긋난 걸 검증하게 된다.</p>
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
        // given: LOGIN 한도는 5인데 저장소가 4를 반환
        given(rateLimitWindowRepository.findById(anyString()))
                .willReturn(Optional.of(windowWithCount(4)));

        // when
        RateLimiter.RateLimitResult result = rateLimiter.tryConsume(RateLimitCategory.LOGIN, "key");

        // then
        assertThat(result.allowed()).isTrue();
        verify(rateLimitWindowRepository).upsertWindow(eq("LOGIN:key"), any(), any());
    }

    @Test
    @DisplayName("저장소가 반환한 카운트가 한도를 넘으면 거부하고 Retry-After를 함께 담는다")
    void rejectsWhenStoredCountExceedsLimit() {
        // given: LOGIN 한도는 5인데 저장소가 6을 반환, 윈도우는 10분 뒤 만료
        given(rateLimitWindowRepository.findById(anyString()))
                .willReturn(Optional.of(windowWithCount(6)));

        // when
        RateLimiter.RateLimitResult result = rateLimiter.tryConsume(RateLimitCategory.LOGIN, "key");

        // then
        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfterSeconds()).isEqualTo(Duration.ofMinutes(10).toSeconds());
    }

    @Test
    @DisplayName("등급마다 다른 한도를 독립적으로 적용한다")
    void appliesDifferentLimitPerCategory() {
        // given: TOKEN_REFRESH 한도(30)보다는 작지만 LOGIN 한도(5)보다는 큰 카운트
        given(rateLimitWindowRepository.findById(anyString()))
                .willReturn(Optional.of(windowWithCount(10)));

        // when & then
        assertThat(rateLimiter.tryConsume(RateLimitCategory.TOKEN_REFRESH, "key").allowed()).isTrue();
        assertThat(rateLimiter.tryConsume(RateLimitCategory.LOGIN, "key").allowed()).isFalse();
    }

    @ParameterizedTest(name = "{0} 등급은 한도({1})까지 허용하고 그 다음 요청은 거부한다")
    @MethodSource("categoryLimits")
    @DisplayName("네 등급 모두 설정된 한도 경계에서 정확히 허용/거부를 나눈다")
    void enforcesConfiguredLimitBoundaryPerCategory(RateLimitCategory category, int maxRequests) {
        // given: 한도와 정확히 같은 카운트는 허용, 한도보다 하나 많은 카운트는 거부
        given(rateLimitWindowRepository.findById(anyString()))
                .willReturn(Optional.of(windowWithCount(maxRequests)));
        assertThat(rateLimiter.tryConsume(category, "key").allowed())
                .as("%s 한도(%d)와 같은 카운트는 허용해야 한다", category, maxRequests)
                .isTrue();

        given(rateLimitWindowRepository.findById(anyString()))
                .willReturn(Optional.of(windowWithCount(maxRequests + 1)));
        assertThat(rateLimiter.tryConsume(category, "key").allowed())
                .as("%s 한도(%d)보다 하나 많은 카운트는 거부해야 한다", category, maxRequests)
                .isFalse();
    }

    private static Stream<Arguments> categoryLimits() {
        return Stream.of(
                Arguments.of(RateLimitCategory.SIGN_UP, 5),
                Arguments.of(RateLimitCategory.LOGIN, 5),
                Arguments.of(RateLimitCategory.TOKEN_REFRESH, 30),
                Arguments.of(RateLimitCategory.CSRF_PREPARATION, 60)
        );
    }

    @Test
    @DisplayName("남은 시간이 소수 초면 올림해서 Retry-After를 반환한다")
    void roundsUpFractionalRemainingSecondsForRetryAfter() {
        // given: 한도를 넘겼고 윈도우가 0.5초 뒤 만료
        LocalDateTime expiresAt = LocalDateTime.ofInstant(BASE_TIME, ZoneOffset.UTC).plus(Duration.ofMillis(500));
        given(rateLimitWindowRepository.findById(anyString()))
                .willReturn(Optional.of(windowExceedingLimitExpiringAt(expiresAt)));

        // when
        RateLimiter.RateLimitResult result = rateLimiter.tryConsume(RateLimitCategory.LOGIN, "key");

        // then: 내림(0초)이 아니라 올림(1초)
        assertThat(result.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("정수 초를 나노초만큼만 넘겨도 다음 초로 올림한다")
    void roundsUpWhenRemainingExceedsWholeSecondByOnlyNanos() {
        // given: 한도를 넘겼고 윈도우가 1초 + 1나노초 뒤 만료. Duration.toMillis()로 먼저
        // 내림하면 나노초가 반올림 전에 사라져 1초로 잘못 계산될 수 있는 경계다.
        LocalDateTime expiresAt = LocalDateTime.ofInstant(BASE_TIME, ZoneOffset.UTC).plusSeconds(1).plusNanos(1);
        given(rateLimitWindowRepository.findById(anyString()))
                .willReturn(Optional.of(windowExceedingLimitExpiringAt(expiresAt)));

        // when
        RateLimiter.RateLimitResult result = rateLimiter.tryConsume(RateLimitCategory.LOGIN, "key");

        // then: 1초를 조금이라도 넘으면 2초로 올림해야 한다
        assertThat(result.retryAfterSeconds()).isEqualTo(2);
    }

    private RateLimiter newRateLimiter(Clock clock) {
        return new RateLimiter(
                5, 600,
                5, 600,
                30, 60,
                60, 60,
                clock,
                rateLimitWindowRepository);
    }

    private RateLimitWindow windowWithCount(int count) {
        return new RateLimitWindow("key", LocalDateTime.ofInstant(BASE_TIME, ZoneOffset.UTC).plusMinutes(10), count);
    }

    private RateLimitWindow windowExceedingLimitExpiringAt(LocalDateTime expiresAt) {
        return new RateLimitWindow("key", expiresAt, 999);
    }
}
