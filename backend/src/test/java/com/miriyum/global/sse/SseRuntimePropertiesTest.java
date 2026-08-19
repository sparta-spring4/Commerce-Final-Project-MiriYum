package com.miriyum.global.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SseRuntimePropertiesTest {

    @Test
    void acceptsOnlyCompletePositiveRuntimeSettings() {
        SseRuntimeProperties properties = settings(true, "s".repeat(32), 100, 5);

        SseRuntimeProperties.RuntimePolicy policy = properties.requireRuntime();

        assertThat(policy.timeout()).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.maxConnectionsTotal()).isEqualTo(100);
        assertThat(policy.maxConnectionsPerAccount()).isEqualTo(5);
    }

    @Test
    void disabledOrIncompleteRuntimeFailsOnlyAtSseBoundary() {
        assertUnavailable(settings(false, "s".repeat(32), 100, 5));
        assertUnavailable(settings(true, "short", 100, 5));
        assertUnavailable(settings(true, "s".repeat(32), 0, 5));
        assertUnavailable(settings(true, "s".repeat(32), 4, 5));
    }

    private static SseRuntimeProperties settings(
            boolean enabled,
            String secret,
            int total,
            int perAccount
    ) {
        return new SseRuntimeProperties(
                enabled,
                secret,
                Duration.ofMinutes(1),
                Duration.ofSeconds(15),
                Duration.ofSeconds(5),
                20,
                total,
                perAccount
        );
    }

    private static void assertUnavailable(SseRuntimeProperties properties) {
        assertThatThrownBy(properties::requireRuntime)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
    }
}
