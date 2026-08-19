package com.miriyum.global.sse;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 운영 수치는 외부 설정으로만 받고 활성화 시 완전한 양수 조합을 요구한다. */
@ConfigurationProperties(prefix = "miriyum.sse")
public record SseRuntimeProperties(
        boolean enabled,
        String cursorSecret,
        Duration timeout,
        Duration heartbeatInterval,
        Duration correctionInterval,
        int correctionBatchSize,
        int maxConnectionsTotal,
        int maxConnectionsPerAccount
) {

    private static final int MINIMUM_SECRET_LENGTH = 32;

    public RuntimePolicy requireRuntime() {
        if (!enabled
                || cursorSecret == null
                || cursorSecret.length() < MINIMUM_SECRET_LENGTH
                || !positive(timeout)
                || !positive(heartbeatInterval)
                || !positive(correctionInterval)
                || correctionBatchSize <= 0
                || maxConnectionsTotal <= 0
                || maxConnectionsPerAccount <= 0
                || maxConnectionsPerAccount > maxConnectionsTotal) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        return new RuntimePolicy(
                cursorSecret.getBytes(StandardCharsets.UTF_8),
                timeout,
                heartbeatInterval,
                correctionInterval,
                correctionBatchSize,
                maxConnectionsTotal,
                maxConnectionsPerAccount
        );
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    public record RuntimePolicy(
            byte[] cursorKey,
            Duration timeout,
            Duration heartbeatInterval,
            Duration correctionInterval,
            int correctionBatchSize,
            int maxConnectionsTotal,
            int maxConnectionsPerAccount
    ) {

        public RuntimePolicy {
            cursorKey = cursorKey.clone();
        }

        @Override
        public byte[] cursorKey() {
            return cursorKey.clone();
        }
    }
}
