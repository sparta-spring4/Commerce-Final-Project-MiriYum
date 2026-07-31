package com.miriyum.domain.auth.logindelay;

import java.time.LocalDateTime;

/**
 * 계정 하나의 연속 로그인 실패 상태다({@code login_failure_delays} 한 행).
 *
 * @param consecutiveFailures 마지막 지연이 끝난 뒤 누적된 연속 실패 횟수
 * @param delayStage 0 = 지연 없음, 1 = 1분, 2 = 5분, 3 = 15분(이후 추가 실패에도 3을 유지)
 * @param nextAttemptAllowedAt 이 시각 전까지는 비밀번호를 검사하지 않고 거절한다(지연이 없으면 {@code null})
 */
public record LoginFailureDelay(
        int consecutiveFailures,
        int delayStage,
        LocalDateTime nextAttemptAllowedAt
) {

    /** 아직 실패 기록이 없는 계정의 초기 상태다. */
    public static LoginFailureDelay none() {
        return new LoginFailureDelay(0, 0, null);
    }

    /**
     * 지금 이 계정이 지연 중인지 판단한다. 경계 시각(다음 시도 가능 시각과 정확히 같은 순간)은
     * 이미 지연이 끝난 것으로 취급해, 안내한 시간만큼 기다린 사용자가 다시 막히지 않게 한다.
     */
    public boolean isDelayedAt(LocalDateTime now) {
        return nextAttemptAllowedAt != null && now.isBefore(nextAttemptAllowedAt);
    }
}
