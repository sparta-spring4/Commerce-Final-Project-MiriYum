package com.miriyum.domain.auth.refreshtoken;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.data.redis.connection.RedisConnection;
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
    private ValkeyRefreshTokenRiskEventMarkerStore markerStore;

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
        markerStore = new ValkeyRefreshTokenRiskEventMarkerStore(redisTemplate);
        // 정적 Testcontainers 인스턴스를 공유하므로 이전 테스트의 family·marker·세대를 남기지 않는다.
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
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
        create(new RefreshTokenState(
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
        create(new RefreshTokenState(
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
        long actualTtl = redisTemplate.getExpire(
                RefreshTokenKey.forFamily(TokenNamespace.CONSUMER, familyId));
        long expectedTtl = nextExpiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        assertThat(actualTtl).isBetween(expectedTtl - 2, expectedTtl + 1);
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

    @Test
    @DisplayName("계정 전체 폐기는 같은 계정의 모든 Refresh Token family를 폐기한다")
    void revokesAllFamiliesForAccount() {
        Instant now = Instant.now();
        RefreshTokenState first = state("family-first", "token-first", now);
        RefreshTokenState second = state("family-second", "token-second", now);
        create(first);
        create(second);

        store.revokeAll(TokenNamespace.CONSUMER, 7L, now.plusSeconds(1), now.plusSeconds(1_209_600));

        assertThat(rotate(first, now.plusSeconds(2)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.REUSED);
        assertThat(rotate(second, now.plusSeconds(2)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.REUSED);
    }

    @Test
    @DisplayName("같은 교체 Refresh Token 재사용은 하나의 pending 위험 사건만 남긴다")
    void createsOnePendingRiskEventForRepeatedReuse() {
        Instant now = Instant.now();
        RefreshTokenState state = state("family-risk", "token-first", now);
        create(state);

        assertThat(rotate(state, now.plusSeconds(1)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.ROTATED);
        assertThat(rotate(state, now.plusSeconds(2)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.REUSED);
        assertThat(rotate(state, now.plusSeconds(3)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.REUSED);

        assertThat(markerStore.findPendingEvents())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.namespace()).isEqualTo(TokenNamespace.CONSUMER);
                    assertThat(event.accountId()).isEqualTo(7L);
                    assertThat(event.familyId()).isEqualTo("family-risk");
                    assertThat(event.tokenHash()).isEqualTo(state.currentTokenHash());
                    assertThat(event.sourceEvent()).isEqualTo("REUSED_ROTATED_TOKEN");
                    assertThat(event.originEvent()).isEqualTo("ROTATION");
                    assertThat(event.policyVersion()).isEqualTo("AUTH-012-v1");
                });
    }

    @Test
    @DisplayName("재사용 위험 marker는 MySQL 전달 성공 전까지 family 만료와 무관하게 보존한다")
    void keepsPendingRiskEventUntilDeliverySucceeds() {
        Instant now = Instant.now();
        RefreshTokenState state = state("family-risk-retention", "token-first", now);
        create(state);

        assertThat(rotate(state, now.plusSeconds(1)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.ROTATED);
        assertThat(rotate(state, now.plusSeconds(2)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.REUSED);

        String markerKey = RefreshTokenRiskEventKey.forReuse(
                state.namespace(), state.familyId(), state.currentTokenHash());
        assertThat(redisTemplate.getExpire(markerKey, TimeUnit.SECONDS)).isEqualTo(-1L);
    }

    @Test
    @DisplayName("로그아웃으로 폐기된 현재 Refresh Token을 다시 사용하면 위험 사건을 남긴다")
    void createsPendingRiskEventWhenRevokedCurrentTokenIsReused() {
        Instant now = Instant.now();
        RefreshTokenState state = state("family-revoked-risk", "token-current", now);
        create(state);
        store.revoke(state.namespace(), state.familyId(), state.accountId(), now.plusSeconds(1));

        RefreshTokenRotationResult result = rotate(state, now.plusSeconds(2));

        assertThat(result.status()).isEqualTo(RefreshTokenRotationResult.Status.REUSED);
        assertThat(markerStore.findPendingEvents())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.familyId()).isEqualTo(state.familyId());
                    assertThat(event.tokenHash()).isEqualTo(state.currentTokenHash());
                });
    }

    @Test
    @DisplayName("전체 세션 폐기와 경합한 이전 로그인은 Refresh Token family를 만들지 못한다")
    void rejectsStaleSessionEpochWhenCreatingFamily() {
        Instant now = Instant.now();
        RefreshTokenState first = state("family-before-revoke", "token-before-revoke", now);
        assertThat(store.create(first, 0L).status()).isEqualTo(RefreshTokenCreationResult.Status.CREATED);
        store.revokeAll(TokenNamespace.CONSUMER, 7L, now.plusSeconds(1), now.plusSeconds(1_209_600));

        Long epochTtl = redisTemplate.getExpire(
                RefreshTokenKey.forAccountSessionEpoch(TokenNamespace.CONSUMER, 7L), TimeUnit.SECONDS);
        assertThat(epochTtl).isBetween(1_209_590L, 1_209_600L);

        RefreshTokenState staleLogin = state("family-stale-login", "token-stale-login", now.plusSeconds(2));
        assertThat(store.create(staleLogin, 0L).status())
                .isEqualTo(RefreshTokenCreationResult.Status.SESSION_EPOCH_CHANGED);

        long currentEpoch = store.currentSessionEpoch(TokenNamespace.CONSUMER, 7L);
        RefreshTokenState freshLogin = state("family-after-revoke", "token-after-revoke", now.plusSeconds(3));
        assertThat(store.create(freshLogin, currentEpoch).status())
                .isEqualTo(RefreshTokenCreationResult.Status.CREATED);
    }

    @Test
    @DisplayName("회전 후 로그아웃된 family의 이전 Refresh Token 재사용도 위험 사건으로 남긴다")
    void createsPendingRiskEventWhenRotatedTokenIsReusedAfterLogout() {
        Instant now = Instant.now();
        RefreshTokenState state = state("family-rotated-logout-risk", "token-first", now);
        create(state);

        assertThat(rotate(state, now.plusSeconds(1)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.ROTATED);
        store.revoke(state.namespace(), state.familyId(), state.accountId(), now.plusSeconds(2));

        assertThat(rotate(state, now.plusSeconds(3)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.REUSED);
        assertThat(markerStore.findPendingEvents())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.sourceEvent()).isEqualTo("REUSED_ROTATED_TOKEN");
                    assertThat(event.originEvent()).isEqualTo("ROTATION");
                });
    }
    @Test
    @DisplayName("회전 후 전체 로그아웃된 family의 이전 Refresh Token 재사용도 위험 사건으로 남긴다")
    void createsPendingRiskEventWhenRotatedTokenIsReusedAfterRevokeAll() {
        Instant now = Instant.now();
        RefreshTokenState state = state("family-rotated-revoke-all-risk", "token-first", now);
        create(state);

        assertThat(rotate(state, now.plusSeconds(1)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.ROTATED);
        store.revokeAll(
                state.namespace(), state.accountId(), now.plusSeconds(2), now.plusSeconds(1_209_600));

        assertThat(rotate(state, now.plusSeconds(3)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.REUSED);
        assertThat(markerStore.findPendingEvents())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.sourceEvent()).isEqualTo("REUSED_ROTATED_TOKEN");
                    assertThat(event.originEvent()).isEqualTo("ROTATION");
                });
    }
    private RefreshTokenState state(String familyId, String tokenId, Instant now) {
        return new RefreshTokenState(
                TokenNamespace.CONSUMER,
                7L,
                familyId,
                tokenId,
                RefreshTokenHash.sha256(tokenId),
                now.plusSeconds(1_209_600),
                now,
                RefreshTokenState.Status.ACTIVE);
    }

    private void create(RefreshTokenState state) {
        long sessionEpoch = store.currentSessionEpoch(state.namespace(), state.accountId());
        assertThat(store.create(state, sessionEpoch).status())
                .isEqualTo(RefreshTokenCreationResult.Status.CREATED);
    }

    private RefreshTokenRotationResult rotate(RefreshTokenState state, Instant now) {
        return store.rotate(
                state.namespace(),
                state.familyId(),
                state.accountId(),
                state.currentTokenId(),
                state.currentTokenHash(),
                state.currentTokenId() + "-next",
                RefreshTokenHash.sha256(state.currentTokenId() + "-next"),
                now,
                now.plusSeconds(1_209_600));
    }
}
