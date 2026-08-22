package com.miriyum.domain.auth.riskevent;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenCreationResult;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenHash;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenRotationResult;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenState;
import com.miriyum.domain.auth.refreshtoken.ValkeyRefreshTokenRiskEventMarkerStore;
import com.miriyum.domain.auth.refreshtoken.ValkeyRefreshTokenStore;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.menu.schedule.enabled=false",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false",
            "miriyum.reservation.hold-expiration.enabled=false",
            "miriyum.auth.refresh-risk-event-delivery.enabled=false"
        })
class RefreshTokenRiskEventLifecycleIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>(DockerImageName.parse("valkey/valkey:8.1-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @Autowired
    private ValkeyRefreshTokenStore refreshTokenStore;

    private RefreshTokenRiskEventDelivery delivery;

    @Autowired
    private ValkeyRefreshTokenRiskEventMarkerStore markerStore;

    @Autowired
    private AuthRiskEventStore authRiskEventStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        delivery = new RefreshTokenRiskEventDelivery(markerStore, authRiskEventStore, 10);
        jdbcTemplate.execute("DELETE FROM auth_risk_events");
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    @DisplayName("전달 후 삭제된 marker가 재생성돼도 MySQL 위험 사건 횟수는 누적된다")
    void accumulatesOccurrencesAcrossDeliveredMarkerLifecycles() {
        Instant now = Instant.now();
        RefreshTokenState state = state(now);
        long sessionEpoch = refreshTokenStore.currentSessionEpoch(state.namespace(), state.accountId());
        assertThat(refreshTokenStore.create(state, sessionEpoch).status())
                .isEqualTo(RefreshTokenCreationResult.Status.CREATED);

        assertThat(rotate(state, now.plusSeconds(1)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.ROTATED);
        assertThat(rotate(state, now.plusSeconds(2)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.REUSED);
        assertThat(delivery.deliverPendingEvents()).isEqualTo(1);

        assertThat(rotate(state, now.plusSeconds(3)).status())
                .isEqualTo(RefreshTokenRotationResult.Status.REUSED);
        assertThat(delivery.deliverPendingEvents()).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT occurrence_count FROM auth_risk_events", Long.class)).isEqualTo(2L);
    }

    private RefreshTokenState state(Instant now) {
        String tokenId = "token-risk-lifecycle";
        return new RefreshTokenState(
                TokenNamespace.CONSUMER,
                7L,
                "family-risk-lifecycle",
                tokenId,
                RefreshTokenHash.sha256(tokenId),
                now.plusSeconds(1_209_600),
                now,
                RefreshTokenState.Status.ACTIVE);
    }

    private RefreshTokenRotationResult rotate(RefreshTokenState state, Instant now) {
        String nextTokenId = state.currentTokenId() + "-next";
        return refreshTokenStore.rotate(
                state.namespace(),
                state.familyId(),
                state.accountId(),
                state.currentTokenId(),
                state.currentTokenHash(),
                nextTokenId,
                RefreshTokenHash.sha256(nextTokenId),
                now,
                state.familyExpiresAt());
    }
}
