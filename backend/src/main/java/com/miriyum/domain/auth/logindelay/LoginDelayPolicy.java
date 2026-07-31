package com.miriyum.domain.auth.logindelay;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 로그인 실패 누적에 따른 계정 단위 지연 단계를 계산한다.
 * {@code docs/service-policies/01-member-auth.md} AUTH-006을 따른다.
 *
 * <p>정책 원문: "같은 계정에서 연속 5회 실패하면 1분 동안 지연하고, 지연 종료 후 다시 실패하면
 * 5분, 이후 다시 실패하면 최대 15분까지 단계적으로 지연한다. 15분 단계 이후의 추가 실패에는
 * 15분 지연을 반복 적용하며 영구 잠금으로 전환하지 않는다."</p>
 *
 * <p>이 클래스는 시각과 현재 상태만 받아 다음 상태를 계산하는 순수 로직이다. 저장·잠금은
 * {@link LoginFailureDelayRepository}가, 트랜잭션 경계는 호출하는 도메인 Service가 담당한다.</p>
 */
@Component
public class LoginDelayPolicy {

    /** 첫 지연이 걸리기까지 필요한 연속 실패 횟수다. */
    private static final int FAILURES_BEFORE_FIRST_DELAY = 5;

    /** 단계별 지연 시간. 인덱스 0이 1단계이며, 마지막 단계는 이후 실패에도 반복 적용된다. */
    private static final List<Duration> STAGE_DURATIONS = List.of(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15));

    private static final int MAX_STAGE = STAGE_DURATIONS.size();

    /**
     * 실패 한 번을 반영한 다음 상태를 계산한다.
     *
     * <p>아직 지연 단계에 오르지 않았으면 실패 횟수만 올리고, 5회에 도달하는 순간 1단계로 올린다.
     * 이미 한 번이라도 지연됐던 계정(단계 1 이상)은 지연이 끝난 뒤의 실패이므로 곧바로 다음
     * 단계로 올린다. 마지막 단계에서는 단계를 유지한 채 지연 시각만 다시 민다.</p>
     */
    public LoginFailureDelay applyFailure(LoginFailureDelay current, LocalDateTime now) {
        if (current.delayStage() == 0) {
            int failures = current.consecutiveFailures() + 1;
            if (failures < FAILURES_BEFORE_FIRST_DELAY) {
                return new LoginFailureDelay(failures, 0, null);
            }
            return new LoginFailureDelay(failures, 1, now.plus(durationOfStage(1)));
        }

        int nextStage = Math.min(current.delayStage() + 1, MAX_STAGE);
        return new LoginFailureDelay(
                current.consecutiveFailures() + 1, nextStage, now.plus(durationOfStage(nextStage)));
    }

    private Duration durationOfStage(int stage) {
        return STAGE_DURATIONS.get(stage - 1);
    }
}
