package com.miriyum.global.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.reservation.service.ReservationHoldExpirationJob;
import com.miriyum.domain.schedule.closure.service.RegularClosureActivationJob;
import com.miriyum.domain.schedule.service.StoreScheduleActivationJob;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-c")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.datasource.hikari.maximum-pool-size=4",
            "spring.datasource.hikari.minimum-idle=0",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.menu.schedule.enabled=false",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.waiting.compensation.enabled=false",
            "miriyum.reservation.hold-expiration.enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false"
        })
@AutoConfigureMockMvc
@Import(SseJdbcConnectionLifecycleIT.SsePolicyConfig.class)
class SseJdbcConnectionLifecycleIT {

    private static final Instant TOKEN_EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DataSource dataSource;
    @Autowired SseConnectionRegistry connectionRegistry;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean RegularClosureActivationJob regularClosureActivationJob;
    @MockitoBean StoreScheduleActivationJob storeScheduleActivationJob;
    @MockitoBean ReservationHoldExpirationJob reservationHoldExpirationJob;

    @AfterEach
    void closeStreams() {
        connectionRegistry.all().forEach(SseConnection::complete);
    }

    @Test
    @DisplayName("비동기 SSE 요청이 열려 있어도 초기 조회의 JDBC 연결은 즉시 반환한다")
    void openNotificationStreamsReleaseJdbcConnectionsBeforeAsyncRequestsComplete()
            throws Exception {
        // given
        for (long accountId = 41L; accountId <= 44L; accountId++) {
            insertConsumer(accountId);
            given(jwtTokenProvider.parseAccessToken(token(accountId)))
                    .willReturn(new ParsedToken(
                            TokenNamespace.CONSUMER,
                            accountId,
                            null,
                            null,
                            null,
                            null,
                            TOKEN_EXPIRES_AT));
        }

        // when
        for (long accountId = 41L; accountId <= 44L; accountId++) {
            mockMvc.perform(get("/api/v1/consumers/me/notification-events")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(accountId)))
                    .andExpect(status().isOk())
                    .andExpect(request().asyncStarted());
        }

        // then
        assertThat(connectionRegistry.count()).isEqualTo(4);
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        assertThat(hikari.getHikariPoolMXBean().getActiveConnections())
                .as("SSE 요청 수명과 JDBC 연결 수명은 분리되어야 한다")
                .isZero();
    }

    private void insertConsumer(long accountId) {
        jdbcTemplate.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name, status,
                    created_at, updated_at
                ) VALUES (?, ?, 'hash', 'SSE수명검증', 'ACTIVE', NOW(6), NOW(6))
                """, accountId, "sse-lifecycle-" + accountId + "@example.com");
    }

    private static String token(long accountId) {
        return "consumer-token-" + accountId;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SsePolicyConfig {

        @Bean
        @Primary
        SseRuntimeProperties testSseRuntimeProperties() {
            return new SseRuntimeProperties(
                    true,
                    "test-only-sse-cursor-secret-32-bytes",
                    Duration.ofMinutes(1),
                    Duration.ofHours(1),
                    Duration.ofHours(1),
                    100,
                    10,
                    6);
        }
    }
}
