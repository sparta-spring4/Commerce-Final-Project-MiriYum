package com.miriyum.global.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseConnectionRegistryTest {

    @Test
    void enforcesPerAccountAndTotalLimitsWhileAllowingMultipleTabs() {
        SseConnectionRegistry registry = new SseConnectionRegistry();
        SseRuntimeProperties.RuntimePolicy policy = policy(3, 2);
        register(registry, policy, 41L, "route-a");
        register(registry, policy, 41L, "route-a");

        assertTooMany(() -> register(registry, policy, 41L, "route-a"));

        register(registry, policy, 42L, "route-b");
        assertTooMany(() -> register(registry, policy, 43L, "route-c"));
        assertThat(registry.count()).isEqualTo(3);
    }

    @Test
    void routingIndexChangesAtomicallyAndCompletionRemovesOnlyOneConnection() {
        SseConnectionRegistry registry = new SseConnectionRegistry();
        SseRuntimeProperties.RuntimePolicy policy = policy(5, 3);
        SseConnection first = register(registry, policy, 41L, "old-route");
        SseConnection second = register(registry, policy, 41L, "old-route");

        registry.updateRoutingKeys(first.scope(), Set.of("new-route"));
        first.complete();

        assertThat(registry.findByRoutingKey("new-route")).containsExactly(second);
        assertThat(registry.findByRoutingKey("old-route")).isEmpty();
        assertThat(registry.count()).isOne();
    }

    @Test
    void correctionBatchContainsEachConnectedScopeOnlyOnce() {
        SseConnectionRegistry registry = new SseConnectionRegistry();
        SseRuntimeProperties.RuntimePolicy policy = policy(5, 3);
        register(registry, policy, 41L, "route-a");
        register(registry, policy, 41L, "route-a");
        register(registry, policy, 42L, "route-b");

        assertThat(registry.nextCorrectionScopeBatch(10)).containsExactly(
                SseStreamScope.notificationConsumer(41L),
                SseStreamScope.notificationConsumer(42L));
    }

    private static SseConnection register(
            SseConnectionRegistry registry,
            SseRuntimeProperties.RuntimePolicy policy,
            long accountId,
            String route
    ) {
        UUID id = UUID.randomUUID();
        AtomicReference<SseConnection> reference = new AtomicReference<>();
        SseConnection connection = new SseConnection(
                id,
                SseStreamScope.notificationConsumer(accountId),
                new SseEmitter(),
                Instant.parse("2026-08-19T02:00:00Z"),
                Set.of(route),
                () -> registry.remove(id));
        reference.set(connection);
        registry.register(connection, policy);
        return reference.get();
    }

    private static SseRuntimeProperties.RuntimePolicy policy(int total, int account) {
        return new SseRuntimeProperties.RuntimePolicy(
                "0123456789abcdef0123456789abcdef".getBytes(),
                Duration.ofMinutes(1), Duration.ofSeconds(15), Duration.ofSeconds(5),
                10, total, account);
    }

    private static void assertTooMany(ThrowingCall call) {
        assertThatThrownBy(call::invoke)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.TOO_MANY_REQUESTS));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void invoke();
    }
}
