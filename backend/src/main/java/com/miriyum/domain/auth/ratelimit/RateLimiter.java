package com.miriyum.domain.auth.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 키(IP+경로)별 고정 윈도우 요청 횟수를 제한한다.
 *
 * <p>서버 1대 기준 메모리 카운터다. 서버를 여러 대로 늘리면 인스턴스마다 따로 세어 실제로는
 * 의도한 한도보다 더 많은 요청이 허용될 수 있다({@code docs/specs/auth-account/spec.md}
 * "요청 제한" 절 참고). 고도화에서 Valkey 같은 분산 저장소 기반 카운터로 교체해야 한다.
 * 정확한 한도값(기본 60초당 10회)은 팀이 아직 확정한 보안 정책 수치가 아니라 개발 중 정한
 * 임시 기본값이며, {@code application.yml}의 프로퍼티로 조정할 수 있다.</p>
 *
 * <p>키가 만료된 뒤에도 항목을 맵에서 지우지 않으므로, 서버를 오래 띄워두면 서로 다른 IP 수만큼
 * 메모리가 계속 늘어난다. 학생 프로젝트 개발 단계에서는 재시작 주기 안에서 무시할 만한 수준이라
 * 별도 정리(eviction) 로직은 넣지 않았다 — Valkey로 교체하면 TTL로 자연히 해결된다.</p>
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
            if (existing == null || existing.expiresAt().isBefore(now)) {
                return new Window(now.plus(window), 1);
            }
            return new Window(existing.expiresAt(), existing.count() + 1);
        });
        return updated.count() <= maxRequests;
    }

    /**
     * 현재 윈도우가 끝나 다시 요청할 수 있게 되기까지 남은 초(최소 1초)를 반환한다.
     */
    public long retryAfterSeconds(String key) {
        Window current = windows.get(key);
        if (current == null) {
            return window.toSeconds();
        }
        Duration remaining = Duration.between(clock.instant(), current.expiresAt());
        return Math.max(1, remaining.toSeconds());
    }

    private record Window(Instant expiresAt, int count) {
    }
}
