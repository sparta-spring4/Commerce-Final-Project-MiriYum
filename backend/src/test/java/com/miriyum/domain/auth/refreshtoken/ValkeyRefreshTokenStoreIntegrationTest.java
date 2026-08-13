package com.miriyum.domain.auth.refreshtoken;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @DisplayName("한 family의 이전 토큰 재사용 뒤 계정 전체 폐기 시 다른 활성 family도 갱신할 수 없다")
    void keepsOtherActiveFamilyAfterRotatedTokenReuse() {
        Instant now = Instant.now();
        RefreshTokenState reusedFamily = state("family-reused", "token-reused", now);
        RefreshTokenState otherFamily = state("family-other", "token-other", now);
        create(reusedFamily);
        create(otherFamily);

        assertThat(rotate(reusedFamily, now.plusSeconds(1)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.ROTATED);
        assertThat(rotate(reusedFamily, now.plusSeconds(2)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.REUSED);

        assertThat(rotate(otherFamily, now.plusSeconds(4)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.ROTATED);
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
                    assertThat(event.occurrenceCount()).isEqualTo(2L);
                    assertThat(event.lastOccurredAt()).isEqualTo(now.plusSeconds(3).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
                });
    }

    @Test
    @DisplayName("Refresh Token 재사용 marker를 만들면 pending 인덱스에도 marker 키를 등록한다")
    void indexesPendingRiskEventWhenRotatedTokenIsReused() {
        Instant now = Instant.now();
        RefreshTokenState state = state("family-risk-index", "token-first", now);
        create(state);

        assertThat(rotate(state, now.plusSeconds(1)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.ROTATED);
        assertThat(rotate(state, now.plusSeconds(2)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.REUSED);

        String markerKey = RefreshTokenRiskEventKey.forReuse(
                state.namespace(), state.familyId(), state.currentTokenHash());
        assertThat(redisTemplate.opsForSet().members(RefreshTokenRiskEventKey.pendingIndex()))
                .containsExactly(markerKey);
    }

    @Test
    @DisplayName("pending marker 101개는 두 번의 전달 조회에서 모두 탐색한다")
    void continuesPendingIndexScanFromThePreviousBatch() {
        for (int index = 0; index < 101; index++) {
            String markerKey = "auth:risk:pending:batch:" + index;
            redisTemplate.<String, String>opsForHash().put(markerKey, "namespace", TokenNamespace.CONSUMER.value());
            redisTemplate.<String, String>opsForHash().put(markerKey, "accountId", "7");
            redisTemplate.<String, String>opsForHash().put(markerKey, "familyId", "family-" + index);
            redisTemplate.<String, String>opsForHash().put(markerKey, "tokenHash", "a".repeat(64));
            redisTemplate.<String, String>opsForHash().put(markerKey, "sourceEvent", "REUSED_ROTATED_TOKEN");
            redisTemplate.<String, String>opsForHash().put(markerKey, "originEvent", "ROTATION");
            redisTemplate.<String, String>opsForHash().put(markerKey, "policyVersion", "AUTH-012-v1");
            redisTemplate.<String, String>opsForHash().put(markerKey, "occurredAt", "0");
            redisTemplate.<String, String>opsForHash().put(markerKey, "occurrenceCount", "1");
            redisTemplate.<String, String>opsForHash().put(markerKey, "lastOccurredAt", "0");
            redisTemplate.opsForSet().add(RefreshTokenRiskEventKey.pendingIndex(), markerKey);
        }

        List<String> firstBatch = markerStore.findPendingEvents().stream()
                .map(PendingRefreshTokenRiskEvent::eventKey)
                .toList();
        List<String> secondBatch = markerStore.findPendingEvents().stream()
                .map(PendingRefreshTokenRiskEvent::eventKey)
                .toList();

        assertThat(firstBatch).hasSize(100);
        assertThat(firstBatch).doesNotContainAnyElementsOf(secondBatch);
        assertThat(Stream.concat(firstBatch.stream(), secondBatch.stream()).toList())
                .hasSize(101)
                .doesNotHaveDuplicates();
        assertThat(redisTemplate.opsForSet().size(RefreshTokenRiskEventKey.pendingIndex())).isEqualTo(101);
    }

    @Test
    @DisplayName("전달 조회 중 다시 생성된 marker의 pending 인덱스 연결은 제거하지 않는다")
    void preservesRecreatedMarkerPendingIndexMembership() {
        String markerKey = "auth:risk:pending:recreated";
        redisTemplate.opsForSet().add(RefreshTokenRiskEventKey.pendingIndex(), markerKey);

        redisTemplate.<String, String>opsForHash().put(markerKey, "namespace", TokenNamespace.CONSUMER.value());

        assertThat(markerStore.removeFromPendingIndexIfMarkerMissing(markerKey)).isFalse();
        assertThat(redisTemplate.opsForSet().isMember(RefreshTokenRiskEventKey.pendingIndex(), markerKey)).isTrue();
    }

    @Test
    @DisplayName("형식이 손상된 pending marker는 격리하고 같은 배치의 정상 사건 전달은 계속한다")
    void quarantinesMalformedPendingMarkerWithoutBlockingValidEvent() {
        String malformedMarkerKey = "auth:risk:pending:malformed";
        String validMarkerKey = "auth:risk:pending:valid";
        redisTemplate.opsForSet().add(
                RefreshTokenRiskEventKey.pendingIndex(), malformedMarkerKey, validMarkerKey);
        redisTemplate.<String, String>opsForHash().putAll(malformedMarkerKey, Map.of(
                "namespace", TokenNamespace.CONSUMER.value(),
                "accountId", "not-a-number",
                "familyId", "family-malformed",
                "tokenHash", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "sourceEvent", "REUSED_ROTATED_TOKEN",
                "originEvent", "ROTATION",
                "policyVersion", "AUTH-012-v1",
                "occurredAt", "1775952000",
                "occurrenceCount", "1",
                "lastOccurredAt", "1775952000"));
        redisTemplate.<String, String>opsForHash().putAll(validMarkerKey, Map.of(
                "namespace", TokenNamespace.CONSUMER.value(),
                "accountId", "7",
                "familyId", "family-valid",
                "tokenHash", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "sourceEvent", "REUSED_ROTATED_TOKEN",
                "originEvent", "ROTATION",
                "policyVersion", "AUTH-012-v1",
                "occurredAt", "1775952000",
                "occurrenceCount", "1",
                "lastOccurredAt", "1775952000"));

        assertThat(markerStore.findPendingEvents())
                .singleElement()
                .satisfies(event -> assertThat(event.eventKey()).isEqualTo(validMarkerKey));
        assertThat(redisTemplate.opsForSet().isMember(
                RefreshTokenRiskEventKey.pendingIndex(), malformedMarkerKey)).isFalse();
        assertThat(redisTemplate.hasKey(malformedMarkerKey)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", " 7", "1e5", "0x1A", "99999999999999999999"})
    @DisplayName("Java 정수 파싱에서 거부한 숫자 형식 marker는 pending 인덱스와 함께 제거한다")
    void removesMalformedNumericMarkerRejectedByJava(String malformedAccountId) {
        String malformedMarkerKey = "auth:risk:pending:malformed-" + malformedAccountId.hashCode();
        redisTemplate.opsForSet().add(RefreshTokenRiskEventKey.pendingIndex(), malformedMarkerKey);
        redisTemplate.<String, String>opsForHash().putAll(malformedMarkerKey, Map.of(
                "namespace", TokenNamespace.CONSUMER.value(),
                "accountId", malformedAccountId,
                "familyId", "family-malformed",
                "tokenHash", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "sourceEvent", "REUSED_ROTATED_TOKEN",
                "originEvent", "ROTATION",
                "policyVersion", "AUTH-012-v1",
                "occurredAt", "1775952000",
                "occurrenceCount", "1",
                "lastOccurredAt", "1775952000"));

        assertThat(markerStore.findPendingEvents()).isEmpty();
        assertThat(redisTemplate.opsForSet().isMember(
                RefreshTokenRiskEventKey.pendingIndex(), malformedMarkerKey)).isFalse();
        assertThat(redisTemplate.hasKey(malformedMarkerKey)).isFalse();
        assertThat(markerStore.pendingEventCount()).isZero();
    }

    @Test
    @DisplayName("전달 중 재사용 횟수가 바뀐 위험 marker는 삭제하지 않는다")
    void keepsRiskMarkerWhenOccurrenceCountChangesDuringDelivery() {
        Instant now = Instant.now();
        RefreshTokenState state = state("family-risk-delete", "token-first", now);
        create(state);

        assertThat(rotate(state, now.plusSeconds(1)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.ROTATED);
        assertThat(rotate(state, now.plusSeconds(2)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.REUSED);
        assertThat(rotate(state, now.plusSeconds(3)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.REUSED);

        String markerKey = RefreshTokenRiskEventKey.forReuse(
                state.namespace(), state.familyId(), state.currentTokenHash());
        assertThat(markerStore.deleteIfUnchanged(markerKey, 1L)).isFalse();
        assertThat(markerStore.findPendingEvents()).singleElement()
                .satisfies(event -> assertThat(event.occurrenceCount()).isEqualTo(2L));
        assertThat(markerStore.deleteIfUnchanged(markerKey, 2L)).isTrue();
        assertThat(markerStore.findPendingEvents()).isEmpty();
        assertThat(redisTemplate.opsForSet().members(RefreshTokenRiskEventKey.pendingIndex())).isEmpty();
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
