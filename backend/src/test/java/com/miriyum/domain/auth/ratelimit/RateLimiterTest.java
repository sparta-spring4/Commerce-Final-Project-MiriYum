package com.miriyum.domain.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static final Instant BASE_TIME = Instant.parse("2026-07-29T00:00:00Z");

    @Test
    @DisplayName("한도 이내의 요청은 전부 허용한다")
    void allowsRequestsUpToTheLimit() {
        // given
        RateLimiter rateLimiter = new RateLimiter(3, 60, Clock.fixed(BASE_TIME, ZoneOffset.UTC));

        // when & then
        assertThat(rateLimiter.tryConsume("key")).isTrue();
        assertThat(rateLimiter.tryConsume("key")).isTrue();
        assertThat(rateLimiter.tryConsume("key")).isTrue();
    }

    @Test
    @DisplayName("같은 윈도우 안에서 한도를 초과하면 거부한다")
    void rejectsRequestsOverTheLimitWithinTheSameWindow() {
        // given
        RateLimiter rateLimiter = new RateLimiter(2, 60, Clock.fixed(BASE_TIME, ZoneOffset.UTC));

        // when
        rateLimiter.tryConsume("key");
        rateLimiter.tryConsume("key");
        boolean thirdAttempt = rateLimiter.tryConsume("key");

        // then
        assertThat(thirdAttempt).isFalse();
    }

    @Test
    @DisplayName("키가 다르면 서로 독립적으로 카운트한다")
    void tracksDifferentKeysIndependently() {
        // given
        RateLimiter rateLimiter = new RateLimiter(1, 60, Clock.fixed(BASE_TIME, ZoneOffset.UTC));

        // when
        boolean firstKeyAllowed = rateLimiter.tryConsume("key-a");
        boolean secondKeyAllowed = rateLimiter.tryConsume("key-b");

        // then
        assertThat(firstKeyAllowed).isTrue();
        assertThat(secondKeyAllowed).isTrue();
    }

    @Test
    @DisplayName("윈도우가 만료되면 다시 요청을 허용한다")
    void allowsRequestsAgainAfterTheWindowExpires() {
        // given
        TestClock clock = new TestClock(BASE_TIME);
        RateLimiter rateLimiter = new RateLimiter(1, 60, clock);
        rateLimiter.tryConsume("key");

        // when
        clock.advanceSeconds(61);
        boolean afterWindow = rateLimiter.tryConsume("key");

        // then
        assertThat(afterWindow).isTrue();
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
            this.instant = this.instant.plusSeconds(seconds);
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
