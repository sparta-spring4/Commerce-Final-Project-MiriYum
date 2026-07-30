package com.miriyum.domain.auth.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 키(IP+경로)별 고정 윈도우 요청 횟수를 제한한다. 기본값(10분당 5회)은
 * {@code docs/service-policies/18-scale-reliability.md}의 `SCALE-005`("로그인·인증번호 요청은
 * 10분당 5회")를 따르며, {@code application.yml}의 프로퍼티로 조정할 수 있다.
 *
 * <p><b>알려진 한계(트래킹: 이슈 #63 참고):</b> `SCALE-005`는 1·2차 MVP에서도 속도 제한을
 * 애플리케이션 로컬 메모리만으로 최종 판정하지 않도록 요구하지만, 이 구현은 서버 1대 기준
 * 메모리 카운터다. 서버를 여러 대로 늘리면 인스턴스마다 따로 세어 실제로는 의도한 한도보다
 * 더 많은 요청이 허용될 수 있다. MySQL 기반 저장소로의 전환은 Testcontainers 도입 등 별도
 * 인프라 결정이 필요해 이번 수정 범위에서는 숫자·시간 경계 버그만 고치고 저장 방식은
 * 별도로 트래킹한다.</p>
 *
 * <p>키가 만료된 뒤에도 항목을 맵에서 지우지 않으므로, 서버를 오래 띄워두면 서로 다른 IP 수만큼
 * 메모리가 계속 늘어난다. 학생 프로젝트 개발 단계에서는 재시작 주기 안에서 무시할 만한 수준이라
 * 별도 정리(eviction) 로직은 넣지 않았다.</p>
 */
@Component
public class RateLimiter {

    private final int maxRequests;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(
            @Value("${miriyum.rate-limit.max-requests}") int maxRequests,
            @Value("${miriyum.rate-limit.window-seconds}") long windowSeconds,
            Clock clock
    ) {
        this.maxRequests = maxRequests;
        this.window = Duration.ofSeconds(windowSeconds);
        this.clock = clock;
    }

    /**
     * 이번 요청을 허용할 수 있으면 카운트를 올리고 {@code true}를 반환한다.
     */
    public boolean tryConsume(String key) {
        Instant now = clock.instant();
        Window updated = windows.compute(key, (ignoredKey, existing) -> {
            if (existing == null || !existing.expiresAt().isAfter(now)) {
                return new Window(now.plus(window), 1);
            }
            return new Window(existing.expiresAt(), existing.count() + 1);
        });
        return updated.count() <= maxRequests;
    }

    /**
     * 현재 윈도우가 끝나 다시 요청할 수 있게 되기까지 남은 초(최소 1초, 올림)를 반환한다.
     * 내림으로 계산하면 안내받은 초만큼 기다린 클라이언트가 아직 윈도우 안에서 다시 거부될 수 있다.
     */
    public long retryAfterSeconds(String key) {
        Window current = windows.get(key);
        if (current == null) {
            return window.toSeconds();
        }
        Duration remaining = Duration.between(clock.instant(), current.expiresAt());
        long remainingSeconds = (remaining.toMillis() + 999) / 1000;
        return Math.max(1, remainingSeconds);
    }

    private record Window(Instant expiresAt, int count) {
    }
}
