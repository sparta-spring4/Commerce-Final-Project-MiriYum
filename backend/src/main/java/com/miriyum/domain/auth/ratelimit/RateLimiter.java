package com.miriyum.domain.auth.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 등급({@link RateLimitCategory})+키(호출자가 넘긴 값, 보통 IP)별 고정 윈도우 요청 횟수를
 * MySQL({@link RateLimitWindowRepository})에서 제한한다. 등급별 기본값은
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
     * 이번 요청을 허용할 수 있으면 카운트를 올려 허용 결과를 반환하고, 한도를 넘었으면
     * 거부와 함께 같은 조회 결과에서 계산한 Retry-After를 반환한다. 허용 여부 판정과
     * 남은 시간 계산을 하나의 저장소 조회에서 함께 처리해, 두 값을 별도로 조회하는 동안
     * 다른 요청이 윈도우를 갱신해 값이 어긋나는 경합을 없앤다.
     */
    public RateLimitResult tryConsume(RateLimitCategory category, String key) {
        Limit limit = limits.get(category);
        LocalDateTime now = LocalDateTime.now(clock);
        String windowKey = windowKey(category, key);

        rateLimitWindowRepository.upsertWindow(windowKey, now, now.plus(limit.window()));
        RateLimitWindow window = rateLimitWindowRepository.findById(windowKey)
                .orElseThrow(() -> new IllegalStateException("upsert 직후 윈도우를 찾을 수 없습니다: " + windowKey));

        if (window.getRequestCount() <= limit.maxRequests()) {
            return RateLimitResult.allow();
        }
        return RateLimitResult.reject(retryAfterSeconds(now, window.getWindowExpiresAt()));
    }

    /**
     * 현재 윈도우가 끝나 다시 요청할 수 있게 되기까지 남은 초(최소 1초, 올림)를 반환한다.
     * {@code Duration.toMillis()}로 먼저 밀리초로 내림하면 그보다 더 작은 나머지(나노초)가
     * 반올림 전에 사라져 진짜 올림이 되지 않으므로, 초·나노초 성분을 직접 써서 올림한다.
     */
    private long retryAfterSeconds(LocalDateTime now, LocalDateTime windowExpiresAt) {
        Duration remaining = Duration.between(now, windowExpiresAt);
        long remainingSeconds = remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0);
        return Math.max(1, remainingSeconds);
    }

    /**
     * 같은 키 문자열이 여러 등급에서 우연히 재사용돼도 서로 다른 윈도우로 취급하도록 등급을 키에 섞는다.
     */
    private String windowKey(RateLimitCategory category, String key) {
        return category.name() + ":" + key;
    }

    private record Limit(int maxRequests, Duration window) {
    }

    public record RateLimitResult(boolean allowed, long retryAfterSeconds) {

        public static RateLimitResult allow() {
            return new RateLimitResult(true, 0);
        }

        private static RateLimitResult reject(long retryAfterSeconds) {
            return new RateLimitResult(false, retryAfterSeconds);
        }
    }
}
