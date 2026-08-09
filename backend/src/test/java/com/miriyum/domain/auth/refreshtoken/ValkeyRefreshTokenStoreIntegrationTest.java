package com.miriyum.domain.auth.refreshtoken;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-a")
class ValkeyRefreshTokenStoreIntegrationTest {

    private static final String PASSWORD = "test-valkey-password";

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>(
            DockerImageName.parse("valkey/valkey:8.1-alpine"))
            .withExposedPorts(6379)
            .withCommand("valkey-server", "--requirepass", PASSWORD);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private ValkeyRefreshTokenStore store;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                VALKEY.getHost(), VALKEY.getMappedPort(6379));
        configuration.setPassword(PASSWORD);
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        store = new ValkeyRefreshTokenStore(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("현재 Refresh Token만 한 번 회전하고 이전 토큰 재사용은 family를 폐기한다")
    void rotatesOnlyCurrentTokenAndRevokesOnReuse() {
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        String familyId = "family-integration";
        String firstTokenId = "token-1";
        String firstTokenHash = RefreshTokenHash.sha256("refresh-token-1");
        store.create(new RefreshTokenState(
                TokenNamespace.CONSUMER,
                7L,
                familyId,
                firstTokenId,
                firstTokenHash,
                now.plusSeconds(1_209_600),
                now,
                RefreshTokenState.Status.ACTIVE));

        RefreshTokenRotationResult rotated = store.rotate(
                TokenNamespace.CONSUMER,
                familyId,
                7L,
                firstTokenId,
                firstTokenHash,
                "token-2",
                RefreshTokenHash.sha256("refresh-token-2"),
                now.plusSeconds(1),
                now.plusSeconds(1).plusSeconds(1_209_600));
        RefreshTokenRotationResult reused = store.rotate(
                TokenNamespace.CONSUMER,
                familyId,
                7L,
                firstTokenId,
                firstTokenHash,
                "token-3",
                RefreshTokenHash.sha256("refresh-token-3"),
                now.plusSeconds(2),
                now.plusSeconds(2).plusSeconds(1_209_600));

        assertThat(rotated.status()).isEqualTo(RefreshTokenRotationResult.Status.ROTATED);
        assertThat(reused.status()).isEqualTo(RefreshTokenRotationResult.Status.REUSED);
        assertThat(redisTemplate.<String, String>opsForHash()
                .entries(RefreshTokenKey.forFamily(TokenNamespace.CONSUMER, familyId)))
                .containsEntry("status", "REVOKED")
                .containsEntry("currentTokenId", "token-2");
    }

    @Test
    @DisplayName("Refresh Token 회전 시 family TTL도 새 토큰 만료 시각으로 갱신한다")
    void refreshesFamilyTtlOnRotation() {
        Instant now = Instant.now();
        String familyId = "family-ttl";
        store.create(new RefreshTokenState(
                TokenNamespace.CONSUMER,
                7L,
                familyId,
                "token-1",
                RefreshTokenHash.sha256("refresh-token-1"),
                now.plusSeconds(60),
                now,
                RefreshTokenState.Status.ACTIVE));

        Instant rotatedAt = now.plusSeconds(10);
        Instant nextExpiresAt = rotatedAt.plusSeconds(1_209_600);
        RefreshTokenRotationResult result = store.rotate(
                TokenNamespace.CONSUMER,
                familyId,
                7L,
                "token-1",
                RefreshTokenHash.sha256("refresh-token-1"),
                "token-2",
                RefreshTokenHash.sha256("refresh-token-2"),
                rotatedAt,
                nextExpiresAt);

        assertThat(result.status()).isEqualTo(RefreshTokenRotationResult.Status.ROTATED);
        assertThat(redisTemplate.getExpire(RefreshTokenKey.forFamily(TokenNamespace.CONSUMER, familyId)))
                .isBetween(1_209_590L, 1_209_600L);
    }

    @Test
    @DisplayName("존재하지 않는 family는 회전되지 않는다")
    void returnsNotFoundForMissingFamily() {
        RefreshTokenRotationResult result = store.rotate(
                TokenNamespace.STORE_OPERATOR,
                "missing-family",
                99L,
                "token-1",
                RefreshTokenHash.sha256("refresh-token-1"),
                "token-2",
                RefreshTokenHash.sha256("refresh-token-2"),
                Instant.now(),
                Instant.now().plusSeconds(1_209_600));

        assertThat(result.status()).isEqualTo(RefreshTokenRotationResult.Status.NOT_FOUND);
    }
}
