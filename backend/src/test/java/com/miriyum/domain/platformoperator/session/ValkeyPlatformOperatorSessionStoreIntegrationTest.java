package com.miriyum.domain.platformoperator.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
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
        store.replaceActiveSession(state("hash-session-one", "token-1", "hash-refresh-one", now));
        store.replaceActiveSession(state("hash-session-two", "token-2", "hash-refresh-two", now));

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

    private PlatformOperatorSessionState state(String session, String tokenId, String refresh, Instant now) {
        return new PlatformOperatorSessionState(7L, session, tokenId, refresh, now, now,
                now.plusSeconds(1800), now.plusSeconds(28800), 3L, 5L, true);
    }

    private PlatformOperatorSessionProof proof(String session, String tokenId, String refresh) {
        return new PlatformOperatorSessionProof(7L, session, tokenId, refresh, 3L, 5L);
    }
}
