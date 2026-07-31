package com.miriyum.domain.auth.logindelay;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AUTH-006의 "연속 5회 실패 → 1분 → 5분 → 15분, 이후 15분 반복, 영구 잠금 없음" 단계를 고정한다.
 */
class LoginDelayPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 31, 0, 0);

    private final LoginDelayPolicy loginDelayPolicy = new LoginDelayPolicy();

    @Test
    @DisplayName("4회까지는 실패 횟수만 올리고 지연하지 않는다")
    void countsFailuresWithoutDelayBeforeThreshold() {
        // given & when: 처음부터 4번 연속 실패
        LoginFailureDelay delay = applyFailures(LoginFailureDelay.none(), 4);

        // then
        assertThat(delay.consecutiveFailures()).isEqualTo(4);
        assertThat(delay.delayStage()).isZero();
        assertThat(delay.nextAttemptAllowedAt()).isNull();
        assertThat(delay.isDelayedAt(NOW)).isFalse();
    }

    @Test
    @DisplayName("연속 5회 실패하면 1분 지연을 건다")
    void appliesOneMinuteDelayOnFifthFailure() {
        // given & when
        LoginFailureDelay delay = applyFailures(LoginFailureDelay.none(), 5);

        // then
        assertThat(delay.delayStage()).isEqualTo(1);
        assertThat(delay.nextAttemptAllowedAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(delay.isDelayedAt(NOW)).isTrue();
    }

    @Test
    @DisplayName("지연이 끝난 뒤 다시 실패하면 5분으로 늘린다")
    void escalatesToFiveMinutesAfterFailureFollowingFirstDelay() {
        // given: 1분 지연이 걸렸다가 끝난 상태
        LoginFailureDelay afterFirstDelay = applyFailures(LoginFailureDelay.none(), 5);
        LocalDateTime afterDelayEnded = afterFirstDelay.nextAttemptAllowedAt();

        // when
        LoginFailureDelay delay = loginDelayPolicy.applyFailure(afterFirstDelay, afterDelayEnded);

        // then
        assertThat(delay.delayStage()).isEqualTo(2);
        assertThat(delay.nextAttemptAllowedAt()).isEqualTo(afterDelayEnded.plusMinutes(5));
    }

    @Test
    @DisplayName("그다음 실패는 15분으로 늘린다")
    void escalatesToFifteenMinutesOnThirdStage() {
        // given: 이미 5분 단계까지 올라간 상태
        LoginFailureDelay atSecondStage = new LoginFailureDelay(6, 2, NOW);

        // when
        LoginFailureDelay delay = loginDelayPolicy.applyFailure(atSecondStage, NOW);

        // then
        assertThat(delay.delayStage()).isEqualTo(3);
        assertThat(delay.nextAttemptAllowedAt()).isEqualTo(NOW.plusMinutes(15));
    }

    @Test
    @DisplayName("15분 단계 이후의 추가 실패는 영구 잠금 없이 15분을 반복한다")
    void repeatsFifteenMinutesWithoutPermanentLock() {
        // given: 최대 단계에 도달한 상태
        LoginFailureDelay delay = new LoginFailureDelay(7, 3, NOW);

        // when: 세 번 더 실패해도
        LocalDateTime attemptedAt = NOW;
        for (int attempt = 0; attempt < 3; attempt++) {
            delay = loginDelayPolicy.applyFailure(delay, attemptedAt);
            // then: 단계는 3에 머무르고 지연 시간도 15분으로 일정하다
            assertThat(delay.delayStage()).isEqualTo(3);
            assertThat(delay.nextAttemptAllowedAt()).isEqualTo(attemptedAt.plusMinutes(15));
            attemptedAt = delay.nextAttemptAllowedAt();
        }
    }

    @Test
    @DisplayName("다음 시도 가능 시각과 정확히 같은 순간은 지연이 끝난 것으로 본다")
    void treatsExactBoundaryAsDelayEnded() {
        // given: 1분 지연이 걸린 상태
        LoginFailureDelay delay = applyFailures(LoginFailureDelay.none(), 5);

        // when & then: 안내한 시각에 도달하면 더 막지 않는다
        assertThat(delay.isDelayedAt(delay.nextAttemptAllowedAt().minusNanos(1))).isTrue();
        assertThat(delay.isDelayedAt(delay.nextAttemptAllowedAt())).isFalse();
    }

    private LoginFailureDelay applyFailures(LoginFailureDelay start, int times) {
        LoginFailureDelay delay = start;
        for (int attempt = 0; attempt < times; attempt++) {
            delay = loginDelayPolicy.applyFailure(delay, NOW);
        }
        return delay;
    }
}
