package com.miriyum.domain.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class NotificationSettingsTest {

    @Test
    void missingRuntimePolicyKeepsWorkerFailClosed() {
        NotificationSettings settings = new NotificationSettings(
                true, null, null, null, null, null, null, null, null, null);

        assertThat(settings.runtimePolicy()).isEmpty();
        assertThat(settings.schedulingDelayMillis()).isEqualTo(60_000L);
        assertThat(settings.schedulingInitialDelayMillis()).isEqualTo(60_000L);
    }

    @Test
    void disabledWorkerIgnoresOtherwiseValidRuntimePolicy() {
        NotificationSettings settings = validSettings(false);

        assertThat(settings.runtimePolicy()).isEmpty();
    }

    @Test
    void unsafePolicyVersionCannotEnterAuditReason() {
        NotificationSettings settings = new NotificationSettings(
                true,
                "notification-worker-v1\nforged",
                "worker-a",
                10,
                30_000L,
                3,
                5_000L,
                60_000L,
                1_000L,
                1_000L
        );

        assertThat(settings.runtimePolicy()).isEmpty();
    }

    @Test
    void completeVersionedRuntimePolicyEnablesBoundedRetry() {
        NotificationSettings.RuntimePolicy policy = validSettings(true)
                .runtimePolicy()
                .orElseThrow();

        assertThat(policy.policyVersion()).isEqualTo("notification-worker-v1");
        assertThat(policy.workerId()).isEqualTo("worker-a");
        assertThat(policy.batchSize()).isEqualTo(10);
        assertThat(policy.leaseDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.maxAttempts()).isEqualTo(3);
        assertThat(policy.retryDelay(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.retryDelay(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.retryDelay(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.retryDelay(30)).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.pollDelay()).isEqualTo(Duration.ofSeconds(1));
    }

    private NotificationSettings validSettings(boolean enabled) {
        return new NotificationSettings(
                enabled,
                "notification-worker-v1",
                "worker-a",
                10,
                30_000L,
                3,
                5_000L,
                60_000L,
                1_000L,
                1_000L
        );
    }
}
