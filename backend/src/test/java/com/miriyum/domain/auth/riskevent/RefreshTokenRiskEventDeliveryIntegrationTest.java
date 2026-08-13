package com.miriyum.domain.auth.riskevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.refreshtoken.PendingRefreshTokenRiskEvent;
import com.miriyum.domain.auth.refreshtoken.ValkeyRefreshTokenRiskEventMarkerStore;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class RefreshTokenRiskEventDeliveryIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private RefreshTokenRiskEventDelivery delivery;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ValkeyRefreshTokenRiskEventMarkerStore markerStore;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM auth_risk_events");
    }

    @Test
    @DisplayName("같은 pending 위험 사건을 여러 번 전달해도 MySQL에는 한 건만 저장한다")
    void persistsPendingEventIdempotently() {
        PendingRefreshTokenRiskEvent event = new PendingRefreshTokenRiskEvent(
                "auth:risk:pending:consumer:family-1:"
                        + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                TokenNamespace.CONSUMER,
                7L,
                "family-1",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "REUSED_ROTATED_TOKEN",
                "ROTATION",
                "AUTH-012-v1",
                Instant.parse("2026-08-10T00:00:00Z"),
                1L,
                Instant.parse("2026-08-10T00:00:00Z"));
        given(markerStore.findPendingEvents()).willReturn(List.of(event));

        delivery.deliverPendingEvents();
        delivery.deliverPendingEvents();

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_risk_events", Integer.class);
        assertThat(count).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT origin_event FROM auth_risk_events", String.class)).isEqualTo("ROTATION");
        verify(markerStore, times(2)).deleteIfUnchanged(event.eventKey(), event.occurrenceCount());
    }

    @Test
    @DisplayName("같은 위험 사건의 재사용 횟수와 마지막 발생 시각을 누적한다")
    void updatesRepeatedReuseOccurrenceDetails() {
        PendingRefreshTokenRiskEvent first = event(1L, Instant.parse("2026-08-10T00:00:00Z"));
        PendingRefreshTokenRiskEvent repeated = event(3L, Instant.parse("2026-08-10T00:02:00Z"));
        given(markerStore.findPendingEvents()).willReturn(List.of(first), List.of(repeated));

        delivery.deliverPendingEvents();
        delivery.deliverPendingEvents();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT occurrence_count FROM auth_risk_events", Long.class)).isEqualTo(3L);
        // DATETIME(6)에는 시간대가 없다. UTC로 저장하므로 UTC로 해석해야 한다.
        // Timestamp.toInstant()는 JVM 기본 시간대로 해석해 KST 환경에서 9시간 어긋난다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_occurred_at FROM auth_risk_events", LocalDateTime.class)
                .toInstant(ZoneOffset.UTC))
                .isEqualTo(Instant.parse("2026-08-10T00:02:00Z"));
    }

    @Test
    @DisplayName("occurred_at과 created_at을 같은 UTC 기준으로 저장한다")
    void storesAllTimestampColumnsInUtc() {
        // given
        Instant occurredAt = Instant.now().minusSeconds(60);
        PendingRefreshTokenRiskEvent recentEvent = new PendingRefreshTokenRiskEvent(
                "auth:risk:pending:consumer:family-utc:"
                        + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                TokenNamespace.CONSUMER,
                7L,
                "family-utc",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "REUSED_ROTATED_TOKEN",
                "ROTATION",
                "AUTH-012-v1",
                occurredAt,
                1L,
                occurredAt);
        given(markerStore.findPendingEvents()).willReturn(List.of(recentEvent));

        // when
        delivery.deliverPendingEvents();

        // then
        // created_at은 서버가 UTC_TIMESTAMP(6)으로 채우고 occurred_at은 애플리케이션이 넘긴다.
        // 두 값의 기준이 다르면 시간대 차이만큼(KST면 9시간) 벌어진다.
        long gapSeconds = jdbcTemplate.queryForObject(
                "SELECT ABS(TIMESTAMPDIFF(SECOND, occurred_at, created_at)) FROM auth_risk_events",
                Long.class);
        assertThat(gapSeconds).isLessThan(600L);
    }

    private PendingRefreshTokenRiskEvent event(long occurrenceCount, Instant lastOccurredAt) {
        return new PendingRefreshTokenRiskEvent(
                "auth:risk:pending:consumer:family-1:"
                        + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                TokenNamespace.CONSUMER,
                7L,
                "family-1",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "REUSED_ROTATED_TOKEN",
                "ROTATION",
                "AUTH-012-v1",
                Instant.parse("2026-08-10T00:00:00Z"),
                occurrenceCount,
                lastOccurredAt);
    }
}
