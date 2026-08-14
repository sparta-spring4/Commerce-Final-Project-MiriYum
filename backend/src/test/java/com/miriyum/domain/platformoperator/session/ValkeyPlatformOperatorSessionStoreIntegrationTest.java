package com.miriyum.domain.platformoperator.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-a")
class ValkeyPlatformOperatorSessionStoreIntegrationTest {
    private static final String PASSWORD = "test-valkey-password";
    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>(DockerImageName.parse("valkey/valkey:8.1-alpine"))
            .withExposedPorts(6379).withCommand("valkey-server", "--requirepass", PASSWORD);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private ValkeyPlatformOperatorSessionStore store;

    @BeforeEach
    void setUp() {
        var config = new RedisStandaloneConfiguration(VALKEY.getHost(), VALKEY.getMappedPort(6379));
        config.setPassword(PASSWORD);
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        store = new ValkeyPlatformOperatorSessionStore(redis);
        try (RedisConnection connection = connectionFactory.getConnection()) { connection.serverCommands().flushDb(); }
    }

    @AfterEach void tearDown() { connectionFactory.destroy(); }

    @Test
    void replacesAndRotatesAtomicallyWithoutRawSecrets() {
        Instant now = Instant.now();
        assertThat(store.replaceActiveSession(state("hash-session-one", "token-1", "hash-refresh-one", now)).status())
                .isEqualTo(PlatformOperatorSessionResult.Status.CREATED);
        assertThat(store.replaceActiveSession(state("hash-session-two", "token-2", "hash-refresh-two", now)).status())
                .isEqualTo(PlatformOperatorSessionResult.Status.REPLACED);

        assertThat(store.validateAndTouch(proof("hash-session-one", "token-1", "hash-refresh-one"),
                now, now.plusSeconds(1800)).status()).isEqualTo(PlatformOperatorSessionResult.Status.INVALID);
        assertThat(store.rotate(proof("hash-session-two", "token-2", "hash-refresh-two"),
                "token-3", "hash-refresh-three", now, now.plusSeconds(1800)).status())
                .isEqualTo(PlatformOperatorSessionResult.Status.ROTATED);
        assertThat(store.rotate(proof("hash-session-two", "token-2", "hash-refresh-two"),
                "token-4", "hash-refresh-four", now, now.plusSeconds(1800)).status())
                .isEqualTo(PlatformOperatorSessionResult.Status.REUSED);

        Set<String> keys = redis.keys("miriyum:auth:platform-operator:*");
        assertThat(String.join(" ", keys)).doesNotContain("raw-session", "raw-refresh");
    }

    @Test
    void lowerVersionLoginCannotReplaceSessionIssuedAfterPasswordChange() {
        Instant now = Instant.now();
        PlatformOperatorSessionState changedPasswordSession = state(
                "changed-password-session", "token-v2", "refresh-v2", now, 4L, 6L, false);
        PlatformOperatorSessionState staleLoginSession = state(
                "stale-login-session", "token-v1", "refresh-v1", now, 3L, 5L, true);

        assertThat(store.replaceActiveSession(changedPasswordSession).status())
                .isEqualTo(PlatformOperatorSessionResult.Status.CREATED);
        assertThat(store.replaceActiveSession(staleLoginSession).status())
                .isEqualTo(PlatformOperatorSessionResult.Status.STALE);

        assertThat(store.validateAndTouch(proof(
                "changed-password-session", "token-v2", "refresh-v2", 4L, 6L),
                now, now.plusSeconds(1800)).status()).isEqualTo(PlatformOperatorSessionResult.Status.VALID);
        assertThat(store.validateAndTouch(proof(
                "stale-login-session", "token-v1", "refresh-v1", 3L, 5L),
                now, now.plusSeconds(1800)).status()).isEqualTo(PlatformOperatorSessionResult.Status.INVALID);
        assertThat(redis.keys("miriyum:auth:platform-operator:*")).hasSize(2);
    }

    @Test
    void concurrentLoginsLeaveExactlyOneActiveSession() throws Exception {
        Instant now = Instant.now();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(
                    executor.submit(() -> replaceAfterBarrier(state("concurrent-one", "token-1", "refresh-1", now), ready, start)),
                    executor.submit(() -> replaceAfterBarrier(state("concurrent-two", "token-2", "refresh-2", now), ready, start)));
            ready.await();
            start.countDown();
            assertThat(futures).allSatisfy(future -> {
                try { assertThat(future.get().status()).isIn(
                        PlatformOperatorSessionResult.Status.CREATED,
                        PlatformOperatorSessionResult.Status.REPLACED); }
                catch (Exception exception) { throw new AssertionError(exception); }
            });
        }
        long valid = List.of(
                store.validateAndTouch(proof("concurrent-one", "token-1", "refresh-1"), now, now.plusSeconds(1800)),
                store.validateAndTouch(proof("concurrent-two", "token-2", "refresh-2"), now, now.plusSeconds(1800)))
                .stream().filter(result -> result.status() == PlatformOperatorSessionResult.Status.VALID).count();
        assertThat(valid).isEqualTo(1);
        assertThat(redis.keys("miriyum:auth:platform-operator:*")).hasSize(2);
    }

    @Test
    void concurrentRefreshHasOneWinnerAndReuseRevokesTheSession() throws Exception {
        Instant now = Instant.now();
        store.replaceActiveSession(state("rotation-race", "token-old", "refresh-old", now));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<PlatformOperatorSessionResult> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(
                    executor.submit(() -> rotateAfterBarrier("token-next-1", "refresh-next-1", now, ready, start)),
                    executor.submit(() -> rotateAfterBarrier("token-next-2", "refresh-next-2", now, ready, start)));
            ready.await();
            start.countDown();
            results = futures.stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).toList();
        }
        assertThat(results).extracting(PlatformOperatorSessionResult::status)
                .containsExactlyInAnyOrder(PlatformOperatorSessionResult.Status.ROTATED,
                        PlatformOperatorSessionResult.Status.REUSED);
        assertThat(store.validateAndTouch(proof("rotation-race", "token-next-1", "refresh-next-1"),
                now, now.plusSeconds(1800)).status()).isEqualTo(PlatformOperatorSessionResult.Status.INVALID);
        assertThat(store.validateAndTouch(proof("rotation-race", "token-next-2", "refresh-next-2"),
                now, now.plusSeconds(1800)).status()).isEqualTo(PlatformOperatorSessionResult.Status.INVALID);
    }

    private PlatformOperatorSessionResult replaceAfterBarrier(
            PlatformOperatorSessionState state, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown(); start.await(); return store.replaceActiveSession(state);
    }

    private PlatformOperatorSessionResult rotateAfterBarrier(
            String nextId, String nextHash, Instant now, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown(); start.await();
        return store.rotate(proof("rotation-race", "token-old", "refresh-old"),
                nextId, nextHash, now, now.plusSeconds(1800));
    }

    private PlatformOperatorSessionState state(String session, String tokenId, String refresh, Instant now) {
        return state(session, tokenId, refresh, now, 3L, 5L, true);
    }

    private PlatformOperatorSessionState state(
            String session, String tokenId, String refresh, Instant now,
            long authorityVersion, long sessionVersion, boolean passwordChangeRequired) {
        return new PlatformOperatorSessionState(7L, session, tokenId, refresh, now, now,
                now.plusSeconds(1800), now.plusSeconds(28800), authorityVersion, sessionVersion,
                passwordChangeRequired);
    }

    private PlatformOperatorSessionProof proof(String session, String tokenId, String refresh) {
        return proof(session, tokenId, refresh, 3L, 5L);
    }

    private PlatformOperatorSessionProof proof(
            String session, String tokenId, String refresh, long authorityVersion, long sessionVersion) {
        return new PlatformOperatorSessionProof(
                7L, session, tokenId, refresh, authorityVersion, sessionVersion);
    }
}
