package com.miriyum.domain.auth.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 등급({@link RateLimitCategory})+키(IP+경로)별 고정 윈도우 요청 횟수를 MySQL(
 * {@link RateLimitWindowRepository})에서 제한한다. 등급별 기본값은
 * {@code docs/service-policies/18-scale-reliability.md} SCALE-005의 2026-07-30 팀 결정을
 * 따르며, {@code application.yml}의 프로퍼티로 조정할 수 있다.
 *
 * <p>SCALE-005는 1·2차 MVP에서도 속도 제한을 애플리케이션 로컬 메모리만으로 최종
 * 판정하지 않도록 요구한다(이슈 #63). MySQL 원자적 upsert로 갱신하므로 여러 인스턴스가
 * 같은 카운터를 공유하며, 인스턴스별로 따로 세지 않는다.</p>
 */
@Component
public class RateLimiter {

    private final Map<RateLimitCategory, Limit> limits;
    private final Clock clock;
    private final RateLimitWindowRepository rateLimitWindowRepository;

    public RateLimiter(
            @Value("${miriyum.rate-limit.sign-up.max-requests}") int signUpMaxRequests,
            @Value("${miriyum.rate-limit.sign-up.window-seconds}") long signUpWindowSeconds,
            @Value("${miriyum.rate-limit.login.max-requests}") int loginMaxRequests,
            @Value("${miriyum.rate-limit.login.window-seconds}") long loginWindowSeconds,
            @Value("${miriyum.rate-limit.token-refresh.max-requests}") int tokenRefreshMaxRequests,
            @Value("${miriyum.rate-limit.token-refresh.window-seconds}") long tokenRefreshWindowSeconds,
            @Value("${miriyum.rate-limit.csrf-preparation.max-requests}") int csrfPreparationMaxRequests,
            @Value("${miriyum.rate-limit.csrf-preparation.window-seconds}") long csrfPreparationWindowSeconds,
            Clock clock,
            RateLimitWindowRepository rateLimitWindowRepository
    ) {
        this.limits = Map.of(
                RateLimitCategory.SIGN_UP, new Limit(signUpMaxRequests, Duration.ofSeconds(signUpWindowSeconds)),
                RateLimitCategory.LOGIN, new Limit(loginMaxRequests, Duration.ofSeconds(loginWindowSeconds)),
                RateLimitCategory.TOKEN_REFRESH,
                        new Limit(tokenRefreshMaxRequests, Duration.ofSeconds(tokenRefreshWindowSeconds)),
                RateLimitCategory.CSRF_PREPARATION,
                        new Limit(csrfPreparationMaxRequests, Duration.ofSeconds(csrfPreparationWindowSeconds))
        );
        this.clock = clock;
        this.rateLimitWindowRepository = rateLimitWindowRepository;
    }

    /**
     * 이번 요청을 허용할 수 있으면 카운트를 올리고 {@code true}를 반환한다.
     */
    public boolean tryConsume(RateLimitCategory category, String key) {
        Limit limit = limits.get(category);
        LocalDateTime now = LocalDateTime.now(clock);
        String windowKey = windowKey(category, key);

        rateLimitWindowRepository.upsertWindow(windowKey, now, now.plus(limit.window()));
        RateLimitWindow window = rateLimitWindowRepository.findById(windowKey)
                .orElseThrow(() -> new IllegalStateException("upsert 직후 윈도우를 찾을 수 없습니다: " + windowKey));
        return window.getRequestCount() <= limit.maxRequests();
    }

    /**
     * 현재 윈도우가 끝나 다시 요청할 수 있게 되기까지 남은 초(최소 1초, 올림)를 반환한다.
     * 내림으로 계산하면 안내받은 초만큼 기다린 클라이언트가 아직 윈도우 안에서 다시 거부될 수 있다.
     */
    public long retryAfterSeconds(RateLimitCategory category, String key) {
        return rateLimitWindowRepository.findById(windowKey(category, key))
                .map(window -> {
                    Duration remaining = Duration.between(LocalDateTime.now(clock), window.getWindowExpiresAt());
                    long remainingSeconds = (remaining.toMillis() + 999) / 1000;
                    return Math.max(1, remainingSeconds);
                })
                .orElseGet(() -> limits.get(category).window().toSeconds());
    }

    /**
     * 같은 키 문자열이 여러 등급에서 우연히 재사용돼도 서로 다른 윈도우로 취급하도록 등급을 키에 섞는다.
     */
    private String windowKey(RateLimitCategory category, String key) {
        return category.name() + ":" + key;
    }

    private record Limit(int maxRequests, Duration window) {
    }
}
