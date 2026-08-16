package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.waiting.dto.*;
import com.miriyum.domain.reservation.waiting.entity.*;
import com.miriyum.domain.reservation.waiting.repository.*;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-b")
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.store.schedule.activation-enabled=false",
        "miriyum.reservation.time-policy.activation-enabled=false",
        "miriyum.waiting.closure.initial-delay-ms=600000"
})
class WaitingSettingServiceIT {
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440271");
    @Container static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired WaitingSettingService service;
    @Autowired WaitingSettingRepository settings;
    @Autowired WaitingSettingAuditRepository audits;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean WaitingStoreAuthorityPort authority;
    @MockitoBean WaitingClosureService closures;

    @BeforeEach
    void fixture() {
        jdbc.execute("DELETE FROM waiting_setting_audits");
        jdbc.execute("DELETE FROM waiting_settings");
        jdbc.execute("DELETE FROM idempotency_commands");
        jdbc.execute("DELETE FROM store_tag_assignment");
        jdbc.execute("DELETE FROM stores");
        jdbc.execute("DELETE FROM store_operator_accounts");
        insertStoreFixture();
        given(authority.requireRead(anyLong(), anyLong()))
                .willAnswer(invocation -> new WaitingStoreAuthority(
                        invocation.getArgument(1), ZoneId.of("Asia/Seoul")));
        given(authority.requireMutation(anyLong(), anyLong()))
                .willAnswer(invocation -> new WaitingStoreAuthority(
                        invocation.getArgument(1), ZoneId.of("Asia/Seoul")));
    }

    @Test
    void exactIdempotentRetryReplaysWithoutIncrementingVersion() {
        WaitingSettingUpdateRequest request = new WaitingSettingUpdateRequest(
                0L, true, WaitingReceptionMode.AUTO, 60, null);

        WaitingSettingCommandResult first = service.replace(31L, 22L, KEY, request);
        WaitingSettingCommandResult retry = service.replace(31L, 22L, KEY, request);

        assertThat(first.httpStatus()).isEqualTo(200);
        assertThat(retry.data()).isEqualTo(first.data());
        assertThat(settings.findByStoreId(22L).orElseThrow().getVersion()).isEqualTo(1L);
        assertThat(audits.countByStoreId(22L)).isOne();
    }

    @Test
    void closeActionCreatesThePublicClosureJobAfterVersionedDisable() {
        given(closures.inspectActiveTeams(31L, 22L))
                .willReturn(new WaitingActiveTeamImpact(22L, 2L));
        WaitingClosureJobSnapshot job = new WaitingClosureJobSnapshot(
                "91", "22", WaitingClosureJobStatus.PENDING, 2, 0, 0, 0,
                Instant.parse("2026-08-16T00:00:00Z"), null);
        given(closures.startClosure(31L, 22L, KEY, 1L))
                .willReturn(new WaitingClosureCommandResult(202, job));

        WaitingSettingCommandResult result = service.replace(31L, 22L, KEY,
                new WaitingSettingUpdateRequest(0L, false, WaitingReceptionMode.PAUSED,
                        60, WaitingDisableAction.CLOSE_ACTIVE_TEAMS));

        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(settings.findByStoreId(22L).orElseThrow().getVersion()).isEqualTo(1L);
        then(closures).should().startClosure(31L, 22L, KEY, 1L);
    }

    private void insertStoreFixture() {
        jdbc.update("""
                INSERT INTO store_operator_accounts (
                    store_operator_account_id, email, password_hash, display_name, status,
                    created_at, updated_at
                ) VALUES (31, 'waiting-service@example.com', 'hash', '웨이팅 서비스', 'ACTIVE',
                    NOW(6), NOW(6))
                """);
        jdbc.update("""
                INSERT INTO stores (
                    store_id, store_operator_account_id, business_registration_number,
                    business_type, name, description, region, address, store_category_code,
                    time_zone_id, verification_status, operation_status,
                    reservation_enabled, menu_hold_enabled, pickup_enabled,
                    applicant_self_attested_at, required_terms_agreed_at, required_terms_version,
                    created_at, updated_at
                ) VALUES (
                    22, 31, '2710000002', 'CAFE', '웨이팅 서비스 매장', '테스트', 'SEOUL',
                    '서울시 테스트로 2', 'CAFE_BAKERY', 'Asia/Seoul', 'APPROVED', 'OPEN',
                    TRUE, TRUE, TRUE, NOW(6), NOW(6),
                    'STORE_ONBOARDING_REQUIRED_TERMS_V1', NOW(6), NOW(6))
                """);
    }
}
