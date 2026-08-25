package com.miriyum.domain.reservation.waiting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.waiting.entity.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
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
@Transactional
class WaitingSettingRepositoryIT {
    @Container static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired WaitingSettingRepository settings;
    @Autowired WaitingSettingAuditRepository audits;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void fixture() {
        insertStoreFixture(jdbc);
    }

    @Test
    void persistsOneSettingPerStoreAndImmutableVersionAudits() {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        WaitingSetting setting = settings.saveAndFlush(WaitingSetting.create(
                22L, true, WaitingReceptionMode.AUTO, 0, now));
        audits.saveAndFlush(WaitingSettingAudit.record(setting, 31L, now));
        setting.replace(1L, false, WaitingReceptionMode.PAUSED, 180, now.plusSeconds(1));
        settings.flush();
        audits.saveAndFlush(WaitingSettingAudit.record(setting, 31L, now.plusSeconds(1)));

        WaitingSetting reloaded = settings.findByStoreId(22L).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(2L);
        assertThat(reloaded.getAdvanceOpenMinutes()).isEqualTo(180);
        assertThat(audits.countByStoreId(22L)).isEqualTo(2L);
    }

    static void insertStoreFixture(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO store_operator_accounts (
                    store_operator_account_id, email, password_hash, display_name, status,
                    created_at, updated_at
                ) VALUES (31, 'waiting-setting@example.com', 'hash', '웨이팅 설정', 'ACTIVE',
                    NOW(6), NOW(6))
                """);
        jdbc.update("""
                INSERT INTO stores (
                    store_id, store_operator_account_id, business_registration_number,
                    name, description, region, address, store_category_code,
                    time_zone_id, verification_status, operation_status,
                    reservation_enabled, menu_hold_enabled, pickup_enabled,
                    applicant_self_attested_at, required_terms_agreed_at, required_terms_version,
                    created_at, updated_at
                ) VALUES (
                    22, 31, '2710000001', '웨이팅 설정 매장', '테스트', 'SEOUL',
                    '서울시 테스트로 1', 'CAFE_BAKERY', 'Asia/Seoul', 'APPROVED', 'OPEN',
                    TRUE, TRUE, TRUE, NOW(6), NOW(6),
                    'STORE_ONBOARDING_REQUIRED_TERMS_V1', NOW(6), NOW(6))
                """);
    }
}
