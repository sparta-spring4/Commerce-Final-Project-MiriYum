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
                Instant.parse("2026-08-10T00:00:00Z"));
        given(markerStore.findPendingEvents()).willReturn(List.of(event));

        delivery.deliverPendingEvents();
        delivery.deliverPendingEvents();

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_risk_events", Integer.class);
        assertThat(count).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT origin_event FROM auth_risk_events", String.class)).isEqualTo("ROTATION");
        verify(markerStore, times(2)).delete(event.eventKey());
    }
}
