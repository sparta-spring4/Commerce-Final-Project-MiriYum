package com.miriyum.domain.auth.qrepoch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenHash;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenKey;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenState;
import com.miriyum.domain.auth.refreshtoken.ValkeyRefreshTokenStore;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
class ValkeyConsumerQrEpochStoreIntegrationTest {

    private static final String PASSWORD = "test-valkey-password";
    private static final String GENERATION = "restore-2026_08.14";

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>(
            DockerImageName.parse("valkey/valkey:8.1-alpine"))
            .withExposedPorts(6379)
            .withCommand("valkey-server", "--requirepass", PASSWORD);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private ValkeyConsumerQrEpochStore store;
    private ValkeyRefreshTokenStore refreshTokenStore;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                VALKEY.getHost(), VALKEY.getMappedPort(6379));
        configuration.setPassword(PASSWORD);
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        store = new ValkeyConsumerQrEpochStore(redisTemplate, properties(GENERATION));
        refreshTokenStore = new ValkeyRefreshTokenStore(redisTemplate);
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void capturesStableNoTtlSnapshotAndComparesByEquality() {
        ConsumerQrEpochSnapshot first = store.captureCurrent(7L);
        ConsumerQrEpochSnapshot second = store.captureCurrent(7L);

        assertThat(second).isEqualTo(first);
        assertThat(store.isCurrent(7L, first.opaqueVersion())).isTrue();
        assertThat(store.isCurrent(7L, "v1." + "B".repeat(43))).isFalse();
        assertThat(redisTemplate.getExpire(ConsumerQrEpochKey.forAccount(GENERATION, 7L))).isEqualTo(-1L);
    }

    @Test
    void concurrentFirstCaptureReturnsSingleWinningSnapshot() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<ConsumerQrEpochSnapshot>> requests = java.util.stream.IntStream.range(0, 32)
                    .mapToObj(ignored -> (Callable<ConsumerQrEpochSnapshot>) () -> store.captureCurrent(7L))
                    .toList();

            List<ConsumerQrEpochSnapshot> snapshots = executor.invokeAll(requests).stream()
                    .map(this::get)
                    .distinct()
                    .toList();

            assertThat(snapshots).containsExactly(store.captureCurrent(7L));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void usesStorageGenerationAsRestoreFenceNamespace() {
        ConsumerQrEpochSnapshot beforeRestore = store.captureCurrent(7L);
        ValkeyConsumerQrEpochStore afterRestoreStore = new ValkeyConsumerQrEpochStore(
                redisTemplate, properties("restore-2026_08.15"));

        ConsumerQrEpochSnapshot afterRestore = afterRestoreStore.captureCurrent(7L);

        assertThat(afterRestore).isNotEqualTo(beforeRestore);
        assertThat(redisTemplate.hasKey(ConsumerQrEpochKey.forAccount(GENERATION, 7L))).isTrue();
        assertThat(redisTemplate.hasKey(ConsumerQrEpochKey.forAccount("restore-2026_08.15", 7L))).isTrue();
    }

    @Test
    void oldSnapshotIsNotCurrentBeforeFirstCaptureInNewGeneration() {
        ConsumerQrEpochSnapshot oldSnapshot = store.captureCurrent(7L);
        ValkeyConsumerQrEpochStore afterRestoreStore = new ValkeyConsumerQrEpochStore(
                redisTemplate, properties("restore-2026_08.15"));

        assertThat(afterRestoreStore.isCurrent(7L, oldSnapshot.opaqueVersion())).isFalse();
        assertThat(redisTemplate.hasKey(ConsumerQrEpochKey.forAccount("restore-2026_08.15", 7L))).isFalse();
    }

    @Test
    void failsClosedForWrongValkeyType() {
        redisTemplate.opsForValue().set(ConsumerQrEpochKey.forAccount(GENERATION, 7L), "not-a-hash");

        assertThatThrownBy(() -> store.captureCurrent(7L))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void captureAndReadFailClosedWhenEpochUnexpectedlyHasTtl() {
        ConsumerQrEpochSnapshot snapshot = store.captureCurrent(7L);
        String epochKey = ConsumerQrEpochKey.forAccount(GENERATION, 7L);
        redisTemplate.expire(epochKey, Duration.ofMinutes(1));

        assertUnavailable(() -> store.captureCurrent(7L));
        assertUnavailable(() -> store.isCurrent(7L, snapshot.opaqueVersion()));
    }

    @Test
    void activeRefreshRevocationAndQrAdvanceAreAtomicAndIdempotent() {
        RefreshFixture refresh = createActiveRefresh("family-logout", "token-current", "raw-refresh-token");
        ConsumerQrEpochSnapshot before = store.captureCurrent(7L);

        ConsumerQrEpochAdvanceResult first = store.advanceForLogout(
                TokenNamespace.CONSUMER, refresh.parsed(), refresh.rawToken(), Instant.parse("2026-08-14T00:00:01Z"));
        ConsumerQrEpochSnapshot afterFirst = store.captureCurrent(7L);
        ConsumerQrEpochAdvanceResult duplicate = store.advanceForLogout(
                TokenNamespace.CONSUMER, refresh.parsed(), refresh.rawToken(), Instant.parse("2026-08-14T00:00:02Z"));
        ConsumerQrEpochSnapshot afterDuplicate = store.captureCurrent(7L);

        assertThat(first.status()).isEqualTo(ConsumerQrEpochAdvanceResult.Status.APPLIED);
        assertThat(duplicate.status()).isEqualTo(ConsumerQrEpochAdvanceResult.Status.ALREADY_APPLIED);
        assertThat(afterFirst).isNotEqualTo(before).isEqualTo(afterDuplicate);
        assertThat(redisTemplate.<String, String>opsForHash().entries(refresh.familyKey()))
                .containsEntry("status", "REVOKED")
                .containsEntry("qrLogoutNamespace", TokenNamespace.CONSUMER.value())
                .containsEntry("qrLogoutAccountId", "7")
                .containsEntry("qrLogoutFamilyId", refresh.parsed().familyId())
                .containsEntry("qrLogoutGeneration", GENERATION)
                .containsEntry("qrLogoutTokenId", refresh.parsed().tokenId())
                .containsEntry("qrLogoutTokenHash", RefreshTokenHash.sha256(refresh.rawToken()));
        assertThat(redisTemplate.opsForSet().members(refresh.accountFamiliesKey()))
                .doesNotContain(refresh.familyKey());
    }

    @Test
    void invalidCurrentRefreshDoesNotAdvanceOrRevoke() {
        RefreshFixture refresh = createActiveRefresh("family-invalid", "token-current", "raw-refresh-token");
        ConsumerQrEpochSnapshot before = store.captureCurrent(7L);

        ConsumerQrEpochAdvanceResult result = store.advanceForLogout(
                TokenNamespace.CONSUMER, refresh.parsed(), "different-raw-token", Instant.now());

        assertThat(result.status()).isEqualTo(ConsumerQrEpochAdvanceResult.Status.NOT_AUTHORIZED);
        assertThat(store.captureCurrent(7L)).isEqualTo(before);
        assertThat(redisTemplate.<String, String>opsForHash().get(refresh.familyKey(), "status"))
                .isEqualTo("ACTIVE");
        assertThat(redisTemplate.opsForSet().members(refresh.accountFamiliesKey()))
                .contains(refresh.familyKey());
    }

    @Test
    void invalidCurrentRefreshDoesNotInspectCorruptQrState() {
        RefreshFixture refresh = createActiveRefresh("family-rotated", "token-current", "raw-refresh-token");
        redisTemplate.opsForHash().put(refresh.familyKey(), "currentTokenId", "token-next");
        redisTemplate.opsForHash().put(
                refresh.familyKey(), "currentTokenHash", RefreshTokenHash.sha256("raw-next-token"));
        String epochKey = ConsumerQrEpochKey.forAccount(GENERATION, 7L);
        redisTemplate.opsForValue().set(epochKey, "not-a-hash");

        ConsumerQrEpochAdvanceResult result = store.advanceForLogout(
                TokenNamespace.CONSUMER, refresh.parsed(), refresh.rawToken(), true, Instant.now());

        assertThat(result.status()).isEqualTo(ConsumerQrEpochAdvanceResult.Status.NOT_AUTHORIZED);
        assertThat(redisTemplate.opsForValue().get(epochKey)).isEqualTo("not-a-hash");
        assertThat(redisTemplate.<String, String>opsForHash().get(refresh.familyKey(), "status"))
                .isEqualTo("ACTIVE");
    }

    @Test
    void activeRefreshDetectsAccessSubjectMismatchBeforeMutation() {
        RefreshFixture refresh = createActiveRefresh("family-mismatch", "token-current", "raw-refresh-token");
        ConsumerQrEpochSnapshot before = store.captureCurrent(7L);

        ConsumerQrEpochAdvanceResult result = store.advanceForLogout(
                TokenNamespace.CONSUMER, refresh.parsed(), refresh.rawToken(), true, Instant.now());

        assertThat(result.status().name()).isEqualTo("SUBJECT_MISMATCH");
        assertThat(store.captureCurrent(7L)).isEqualTo(before);
        assertRefreshStillActive(refresh);
    }

    @Test
    void missingFamilyCleanupDoesNotRequireStorageGeneration() {
        ValkeyConsumerQrEpochStore invalidGenerationStore = new ValkeyConsumerQrEpochStore(
                redisTemplate, properties(null));
        ParsedToken missing = new ParsedToken(
                TokenNamespace.CONSUMER, 7L, "family-missing", "token-missing");

        ConsumerQrEpochAdvanceResult result = invalidGenerationStore.advanceForLogout(
                TokenNamespace.CONSUMER, missing, "raw-refresh-token", false, Instant.now());

        assertThat(result.status()).isEqualTo(ConsumerQrEpochAdvanceResult.Status.NOT_AUTHORIZED);
    }

    @Test
    void activeRefreshFailsClosedWhenStorageGenerationIsInvalid() {
        RefreshFixture refresh = createActiveRefresh("family-invalid-generation", "token-current", "raw-refresh-token");
        ValkeyConsumerQrEpochStore invalidGenerationStore = new ValkeyConsumerQrEpochStore(
                redisTemplate, properties(null));

        assertUnavailable(() -> invalidGenerationStore.advanceForLogout(
                TokenNamespace.CONSUMER, refresh.parsed(), refresh.rawToken(), false, Instant.now()));

        assertRefreshStillActive(refresh);
    }

    @ParameterizedTest
    @MethodSource("malformedFamilyFields")
    void malformedFamilyStateFailsClosedWithoutMutation(String field, String malformedValue) {
        RefreshFixture refresh = createActiveRefresh("family-malformed", "token-current", "raw-refresh-token");
        ConsumerQrEpochSnapshot beforeEpoch = store.captureCurrent(7L);
        redisTemplate.opsForHash().put(refresh.familyKey(), field, malformedValue);
        Map<Object, Object> beforeFamily = redisTemplate.opsForHash().entries(refresh.familyKey());
        Set<String> beforeIndex = redisTemplate.opsForSet().members(refresh.accountFamiliesKey());

        assertUnavailable(() -> store.advanceForLogout(
                TokenNamespace.CONSUMER, refresh.parsed(), refresh.rawToken(), Instant.now()));

        assertThat(redisTemplate.opsForHash().entries(refresh.familyKey())).isEqualTo(beforeFamily);
        assertThat(redisTemplate.opsForSet().members(refresh.accountFamiliesKey())).isEqualTo(beforeIndex);
        assertThat(store.captureCurrent(7L)).isEqualTo(beforeEpoch);
    }

    @Test
    void duplicateMarkerRequiresFamilyTupleToRemainConsistent() {
        RefreshFixture refresh = createActiveRefresh("family-marker", "token-current", "raw-refresh-token");
        store.advanceForLogout(
                TokenNamespace.CONSUMER, refresh.parsed(), refresh.rawToken(), Instant.now());
        redisTemplate.opsForHash().put(
                refresh.familyKey(), "currentTokenHash", RefreshTokenHash.sha256("different-token"));
        ConsumerQrEpochSnapshot before = store.captureCurrent(7L);

        assertUnavailable(() -> store.advanceForLogout(
                TokenNamespace.CONSUMER, refresh.parsed(), refresh.rawToken(), Instant.now()));

        assertThat(store.captureCurrent(7L)).isEqualTo(before);
    }

    @Test
    void revokedMarkerFromOldGenerationIsCleanupOnlyBeforeNewEpochExists() {
        RefreshFixture refresh = createActiveRefresh("family-restored", "token-current", "raw-refresh-token");
        store.advanceForLogout(
                TokenNamespace.CONSUMER, refresh.parsed(), refresh.rawToken(), Instant.now());
        ValkeyConsumerQrEpochStore afterRestoreStore = new ValkeyConsumerQrEpochStore(
                redisTemplate, properties("restore-2026_08.15"));

        ConsumerQrEpochAdvanceResult result = afterRestoreStore.advanceForLogout(
                TokenNamespace.CONSUMER, refresh.parsed(), refresh.rawToken(), Instant.now());

        assertThat(result.status()).isEqualTo(ConsumerQrEpochAdvanceResult.Status.NOT_AUTHORIZED);
        assertThat(redisTemplate.hasKey(ConsumerQrEpochKey.forAccount("restore-2026_08.15", 7L))).isFalse();
    }

    @Test
    void logoutKeepsOtherDeviceRefreshFamilyActive() {
        RefreshFixture loggedOut = createActiveRefresh("family-logged-out", "token-current", "raw-refresh-token");
        RefreshFixture otherDevice = createActiveRefresh("family-other-device", "token-other", "raw-other-token");

        ConsumerQrEpochAdvanceResult result = store.advanceForLogout(
                TokenNamespace.CONSUMER, loggedOut.parsed(), loggedOut.rawToken(), Instant.now());

        assertThat(result.status()).isEqualTo(ConsumerQrEpochAdvanceResult.Status.APPLIED);
        assertThat(redisTemplate.<String, String>opsForHash().get(otherDevice.familyKey(), "status"))
                .isEqualTo("ACTIVE");
        assertThat(redisTemplate.opsForSet().members(otherDevice.accountFamiliesKey()))
                .containsExactly(otherDevice.familyKey());
    }

    @Test
    void logoutAgainstMissingEpochInitializesAndAdvancesOnce() {
        RefreshFixture refresh = createActiveRefresh("family-missing-epoch", "token-current", "raw-refresh-token");

        ConsumerQrEpochAdvanceResult result = store.advanceForLogout(
                TokenNamespace.CONSUMER, refresh.parsed(), refresh.rawToken(), Instant.now());

        assertThat(result.status()).isEqualTo(ConsumerQrEpochAdvanceResult.Status.APPLIED);
        assertThat(redisTemplate.<String, String>opsForHash()
                .get(ConsumerQrEpochKey.forAccount(GENERATION, 7L), "counter"))
                .isEqualTo("1");
        assertThat(store.isCurrent(7L, store.captureCurrent(7L).opaqueVersion())).isTrue();
    }

    @Test
    void corruptEpochTypeDoesNotPartiallyRevokeRefresh() {
        RefreshFixture refresh = createActiveRefresh("family-corrupt", "token-current", "raw-refresh-token");
        redisTemplate.opsForValue().set(ConsumerQrEpochKey.forAccount(GENERATION, 7L), "not-a-hash");

        assertUnavailable(() -> store.advanceForLogout(
                TokenNamespace.CONSUMER, refresh.parsed(), refresh.rawToken(), Instant.now()));

        assertRefreshStillActive(refresh);
        assertThat(redisTemplate.opsForValue().get(ConsumerQrEpochKey.forAccount(GENERATION, 7L)))
                .isEqualTo("not-a-hash");
    }

    @Test
    void epochTtlDoesNotPartiallyRevokeRefresh() {
        RefreshFixture refresh = createActiveRefresh("family-ttl", "token-current", "raw-refresh-token");
        String epochKey = ConsumerQrEpochKey.forAccount(GENERATION, 7L);
        store.captureCurrent(7L);
        redisTemplate.expire(epochKey, Duration.ofMinutes(1));

        assertUnavailable(() -> store.advanceForLogout(
                TokenNamespace.CONSUMER, refresh.parsed(), refresh.rawToken(), Instant.now()));

        assertRefreshStillActive(refresh);
        assertThat(redisTemplate.getExpire(epochKey)).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void counterOverflowDoesNotPartiallyRevokeRefresh() {
        RefreshFixture refresh = createActiveRefresh("family-overflow", "token-current", "raw-refresh-token");
        String epochKey = ConsumerQrEpochKey.forAccount(GENERATION, 7L);
        redisTemplate.opsForHash().put(epochKey, "salt", "A".repeat(22));
        redisTemplate.opsForHash().put(epochKey, "counter", Long.toString(Long.MAX_VALUE));

        assertUnavailable(() -> store.advanceForLogout(
                TokenNamespace.CONSUMER, refresh.parsed(), refresh.rawToken(), Instant.now()));

        assertRefreshStillActive(refresh);
        assertThat(redisTemplate.<String, String>opsForHash().get(epochKey, "counter"))
                .isEqualTo(Long.toString(Long.MAX_VALUE));
    }

    @Test
    void concurrentDuplicateLogoutAdvancesExactlyOnce() throws Exception {
        RefreshFixture refresh = createActiveRefresh("family-concurrent", "token-current", "raw-refresh-token");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<ConsumerQrEpochAdvanceResult>> requests = java.util.stream.IntStream.range(0, 24)
                    .mapToObj(ignored -> (Callable<ConsumerQrEpochAdvanceResult>) () -> store.advanceForLogout(
                            TokenNamespace.CONSUMER, refresh.parsed(), refresh.rawToken(), Instant.now()))
                    .toList();

            List<ConsumerQrEpochAdvanceResult.Status> statuses = executor.invokeAll(requests).stream()
                    .map(this::getAdvance)
                    .map(ConsumerQrEpochAdvanceResult::status)
                    .toList();

            assertThat(statuses).filteredOn(ConsumerQrEpochAdvanceResult.Status.APPLIED::equals).hasSize(1);
            assertThat(statuses).filteredOn(ConsumerQrEpochAdvanceResult.Status.ALREADY_APPLIED::equals).hasSize(23);
            assertThat(redisTemplate.<String, String>opsForHash()
                    .get(ConsumerQrEpochKey.forAccount(GENERATION, 7L), "counter"))
                    .isEqualTo("1");
        } finally {
            executor.shutdownNow();
        }
    }

    private ConsumerQrEpochProperties properties(String generation) {
        ConsumerQrEpochProperties properties = new ConsumerQrEpochProperties();
        properties.setStorageGeneration(generation);
        return properties;
    }

    private static java.util.stream.Stream<Arguments> malformedFamilyFields() {
        return java.util.stream.Stream.of(
                Arguments.of("namespace", "BROKEN"),
                Arguments.of("accountId", "0"),
                Arguments.of("familyId", " "),
                Arguments.of("currentTokenId", ""),
                Arguments.of("currentTokenHash", "not-a-sha256"),
                Arguments.of("status", "BROKEN"),
                Arguments.of("lastRotatedAt", "not-a-number"));
    }

    private RefreshFixture createActiveRefresh(String familyId, String tokenId, String rawToken) {
        Instant now = Instant.parse("2026-08-14T00:00:00Z");
        RefreshTokenState state = new RefreshTokenState(
                TokenNamespace.CONSUMER,
                7L,
                familyId,
                tokenId,
                RefreshTokenHash.sha256(rawToken),
                now.plusSeconds(1_209_600),
                now,
                RefreshTokenState.Status.ACTIVE);
        assertThat(refreshTokenStore.create(state, 0L).status().name()).isEqualTo("CREATED");
        return new RefreshFixture(
                new ParsedToken(TokenNamespace.CONSUMER, 7L, familyId, tokenId),
                rawToken,
                RefreshTokenKey.forFamily(TokenNamespace.CONSUMER, familyId),
                RefreshTokenKey.forAccountFamilies(TokenNamespace.CONSUMER, 7L));
    }

    private void assertRefreshStillActive(RefreshFixture refresh) {
        assertThat(redisTemplate.<String, String>opsForHash().get(refresh.familyKey(), "status"))
                .isEqualTo("ACTIVE");
        assertThat(redisTemplate.opsForSet().members(refresh.accountFamiliesKey()))
                .contains(refresh.familyKey());
    }

    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    private ConsumerQrEpochSnapshot get(Future<ConsumerQrEpochSnapshot> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private ConsumerQrEpochAdvanceResult getAdvance(Future<ConsumerQrEpochAdvanceResult> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record RefreshFixture(
            ParsedToken parsed,
            String rawToken,
            String familyKey,
            String accountFamiliesKey
    ) {
    }
}
