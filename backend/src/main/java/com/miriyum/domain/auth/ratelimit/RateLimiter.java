package com.miriyum.domain.auth.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
     * 거부와 함께 계산한 Retry-After를 반환한다.
     *
     * <p>{@code upsertWindow()}와 {@code findById()}를 별도 트랜잭션으로 호출하면, 자신이
     * 증가시킨 뒤 읽기 전에 다른 동시 요청이 카운트를 더 올려 자기 순번보다 큰 값을 읽고
     * 한도 이내 요청까지 잘못 거부할 수 있다. 이 메서드를 하나의 트랜잭션으로 묶어, upsert가
     * 커밋 전까지 쥐고 있는 InnoDB 행 잠금이 같은 키의 동시 요청을 직렬화하게 하고,
     * {@code findById()}가 정확히 이 요청이 만든 카운트·만료 시각을 읽도록 보장한다.</p>
     */
    @Transactional
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
