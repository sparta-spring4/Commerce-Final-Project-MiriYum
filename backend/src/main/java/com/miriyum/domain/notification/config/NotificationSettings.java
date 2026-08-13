package com.miriyum.domain.notification.config;

import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Notification worker를 활성화하는 버전된 런타임 정책 입력이다.
 *
 * <p>활성화 플래그만 켜거나 일부 값이 누락된 경우에는 작업을 가져오지 않는다.
 */
@ConfigurationProperties("miriyum.notification.worker")
public record NotificationSettings(
        boolean enabled,
        String policyVersion,
        String workerId,
        Integer batchSize,
        Long leaseDurationMs,
        Integer maxAttempts,
        Long initialRetryDelayMs,
        Long maxRetryDelayMs,
        Long pollDelayMs,
        Long initialDelayMs
) {

    private static final long SAFE_DISABLED_DELAY_MS = 60_000L;

    /**
     * 모든 안전 조건이 충족될 때만 worker가 사용할 정책을 반환한다.
     *
     * @return 유효한 버전 정책, 또는 비활성 상태
     */
    public Optional<RuntimePolicy> runtimePolicy() {
        if (!enabled
                || isBlank(policyVersion)
                || isBlank(workerId)
                || policyVersion.length() > 64
                || workerId.length() > 100
                || !policyVersion.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")
                || !workerId.matches("[a-zA-Z0-9][a-zA-Z0-9._:-]*")
                || !isPositive(batchSize)
                || !isPositive(leaseDurationMs)
                || leaseDurationMs > Long.MAX_VALUE / 1_000L
                || !isPositive(maxAttempts)
                || !isPositive(initialRetryDelayMs)
                || !isPositive(maxRetryDelayMs)
                || initialRetryDelayMs > Long.MAX_VALUE / 1_000L
                || maxRetryDelayMs > Long.MAX_VALUE / 1_000L
                || !isPositive(pollDelayMs)
                || !isPositive(initialDelayMs)
                || maxRetryDelayMs < initialRetryDelayMs) {
            return Optional.empty();
        }
        return Optional.of(new RuntimePolicy(
                policyVersion,
                workerId,
                batchSize,
                Duration.ofMillis(leaseDurationMs),
                maxAttempts,
                Duration.ofMillis(initialRetryDelayMs),
                Duration.ofMillis(maxRetryDelayMs),
                Duration.ofMillis(pollDelayMs),
                Duration.ofMillis(initialDelayMs)
        ));
    }

    public long schedulingDelayMillis() {
        return runtimePolicy()
                .map(policy -> policy.pollDelay().toMillis())
                .orElse(SAFE_DISABLED_DELAY_MS);
    }

    public long schedulingInitialDelayMillis() {
        return runtimePolicy()
                .map(policy -> policy.initialDelay().toMillis())
                .orElse(SAFE_DISABLED_DELAY_MS);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isPositive(Number value) {
        return value != null && value.longValue() > 0;
    }

    /**
     * 한 번의 실행에서 고정해 사용하는 검증 완료 정책이다.
     */
    public record RuntimePolicy(
            String policyVersion,
            String workerId,
            int batchSize,
            Duration leaseDuration,
            int maxAttempts,
            Duration initialRetryDelay,
            Duration maxRetryDelay,
            Duration pollDelay,
            Duration initialDelay
    ) {

        /**
         * 실패한 횟수에 따라 상한이 있는 지수 지연을 계산한다.
         *
         * @param attemptCount 현재까지 실행한 횟수
         * @return 즉시 반복되지 않는 다음 재시도 지연
         */
        public Duration retryDelay(int attemptCount) {
            long delay = initialRetryDelay.toMillis();
            long maximum = maxRetryDelay.toMillis();
            for (int index = 1; index < attemptCount && delay < maximum; index++) {
                delay = Math.min(maximum, delay > maximum / 2 ? maximum : delay * 2);
            }
            return Duration.ofMillis(delay);
        }
    }
}
