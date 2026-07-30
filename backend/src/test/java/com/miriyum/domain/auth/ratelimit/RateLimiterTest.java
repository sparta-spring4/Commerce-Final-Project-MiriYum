package com.miriyum.domain.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static final Instant BASE_TIME = Instant.parse("2026-07-29T00:00:00Z");
    private static final RateLimitCategory CATEGORY = RateLimitCategory.LOGIN;

    @Test
    @DisplayName("한도 이내의 요청은 전부 허용한다")
    void allowsRequestsUpToTheLimit() {
        // given
        RateLimiter rateLimiter = rateLimiterWithLoginLimit(3, 60, Clock.fixed(BASE_TIME, ZoneOffset.UTC));

        // when & then
        assertThat(rateLimiter.tryConsume(CATEGORY, "key")).isTrue();
        assertThat(rateLimiter.tryConsume(CATEGORY, "key")).isTrue();
        assertThat(rateLimiter.tryConsume(CATEGORY, "key")).isTrue();
    }

    @Test
    @DisplayName("같은 윈도우 안에서 한도를 초과하면 거부한다")
    void rejectsRequestsOverTheLimitWithinTheSameWindow() {
        // given
        RateLimiter rateLimiter = rateLimiterWithLoginLimit(2, 60, Clock.fixed(BASE_TIME, ZoneOffset.UTC));

        // when
        rateLimiter.tryConsume(CATEGORY, "key");
        rateLimiter.tryConsume(CATEGORY, "key");
        boolean thirdAttempt = rateLimiter.tryConsume(CATEGORY, "key");

        // then
        assertThat(thirdAttempt).isFalse();
    }

    @Test
    @DisplayName("키가 다르면 서로 독립적으로 카운트한다")
    void tracksDifferentKeysIndependently() {
        // given
        RateLimiter rateLimiter = rateLimiterWithLoginLimit(1, 60, Clock.fixed(BASE_TIME, ZoneOffset.UTC));

        // when
        boolean firstKeyAllowed = rateLimiter.tryConsume(CATEGORY, "key-a");
        boolean secondKeyAllowed = rateLimiter.tryConsume(CATEGORY, "key-b");

        // then
        assertThat(firstKeyAllowed).isTrue();
        assertThat(secondKeyAllowed).isTrue();
    }

    @Test
    @DisplayName("윈도우가 만료되면 다시 요청을 허용한다")
    void allowsRequestsAgainAfterTheWindowExpires() {
        // given
        TestClock clock = new TestClock(BASE_TIME);
        RateLimiter rateLimiter = rateLimiterWithLoginLimit(1, 60, clock);
        rateLimiter.tryConsume(CATEGORY, "key");

        // when
        clock.advanceSeconds(61);
        boolean afterWindow = rateLimiter.tryConsume(CATEGORY, "key");

        // then
        assertThat(afterWindow).isTrue();
    }

    @Test
    @DisplayName("윈도우 만료 시각과 현재 시각이 정확히 같으면 새 윈도우로 취급한다")
    void treatsExactExpiryInstantAsExpired() {
        // given
        TestClock clock = new TestClock(BASE_TIME);
        RateLimiter rateLimiter = rateLimiterWithLoginLimit(1, 60, clock);
        rateLimiter.tryConsume(CATEGORY, "key");

        // when: 정확히 60초 경과(만료 시각과 동일한 순간)
        clock.advance(Duration.ofSeconds(60));
        boolean allowedAtExactExpiry = rateLimiter.tryConsume(CATEGORY, "key");

        // then
        assertThat(allowedAtExactExpiry).isTrue();
    }

    @Test
    @DisplayName("남은 시간이 소수 초면 올림해서 Retry-After를 반환한다")
    void roundsUpFractionalRemainingSecondsForRetryAfter() {
        // given
        TestClock clock = new TestClock(BASE_TIME);
        RateLimiter rateLimiter = rateLimiterWithLoginLimit(1, 60, clock);
        rateLimiter.tryConsume(CATEGORY, "key");

        // when: 59.5초 경과, 실제로는 0.5초가 남음
        clock.advance(Duration.ofMillis(59_500));
        rateLimiter.tryConsume(CATEGORY, "key");

        // then: 내림(0초)이 아니라 올림(1초)해야 안내받은 시간만큼 기다린 뒤 재시도가 통과한다
        assertThat(rateLimiter.retryAfterSeconds(CATEGORY, "key")).isEqualTo(1);
    }

    @Test
    @DisplayName("등급마다 다른 한도를 독립적으로 적용한다")
    void appliesDifferentLimitsPerCategory() {
        // given
        RateLimiter rateLimiter = new RateLimiter(
                1, 60,
                1, 60,
                3, 60,
                5, 60,
                Clock.fixed(BASE_TIME, ZoneOffset.UTC));

        // when & then: TOKEN_REFRESH는 3회까지 허용하고 SIGN_UP은 1회만 허용한다
        assertThat(rateLimiter.tryConsume(RateLimitCategory.TOKEN_REFRESH, "shared-ip")).isTrue();
        assertThat(rateLimiter.tryConsume(RateLimitCategory.TOKEN_REFRESH, "shared-ip")).isTrue();
        assertThat(rateLimiter.tryConsume(RateLimitCategory.TOKEN_REFRESH, "shared-ip")).isTrue();
        assertThat(rateLimiter.tryConsume(RateLimitCategory.SIGN_UP, "shared-ip")).isTrue();
    }

    private RateLimiter rateLimiterWithLoginLimit(int maxRequests, long windowSeconds, Clock clock) {
        return new RateLimiter(
                1, 60,
                maxRequests, windowSeconds,
                1, 60,
                1, 60,
                clock);
    }

    /**
     * {@link Clock#fixed}는 시간을 되돌릴 수 없어 윈도우 만료 테스트에 쓸 수 없으므로,
     * 테스트 안에서만 흐르는 시각을 직접 앞으로 넘기는 용도의 최소 구현이다.
     */
    private static final class TestClock extends Clock {

        private Instant instant;

        private TestClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            advance(Duration.ofSeconds(seconds));
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
