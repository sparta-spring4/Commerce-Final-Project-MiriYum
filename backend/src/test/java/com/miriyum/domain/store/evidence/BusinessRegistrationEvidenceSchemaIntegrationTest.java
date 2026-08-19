package com.miriyum.domain.store.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 비공개 사업자등록증 원장의 current/replaced 저장 제약을 실제 MySQL에서 검증한다. */
@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class BusinessRegistrationEvidenceSchemaIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("현재 증빙은 하나만 보관하고 교체 이력은 여러 건 보관한다")
    void allowsOneCurrentEvidenceAndMultipleReplacedHistoryRows() {
        long applicationId = 901L;
        long applicationVersion = 3L;
        insertEvidence(applicationId, applicationVersion, "REPLACED", null);
        insertEvidence(applicationId, applicationVersion, "REPLACED", null);
        insertEvidence(applicationId, applicationVersion, "CURRENT", 1);

        assertThatThrownBy(() -> insertEvidence(applicationId, applicationVersion, "CURRENT", 1))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_store_business_registration_current");

        Integer currentCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM store_business_registration_evidences
                WHERE onboarding_application_id = ?
                  AND application_version = ?
                  AND current_marker = 1
                """,
                Integer.class,
                applicationId,
                applicationVersion);
        Integer replacedCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM store_business_registration_evidences
                WHERE onboarding_application_id = ?
                  AND application_version = ?
                  AND evidence_status = 'REPLACED'
                """,
                Integer.class,
                applicationId,
                applicationVersion);

        assertThat(currentCount).isEqualTo(1);
        assertThat(replacedCount).isEqualTo(2);
    }

    private void insertEvidence(long applicationId, long applicationVersion, String status, Integer currentMarker) {
        UUID fileId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        insertPrivateLicenseMetadata(fileId, applicationId, now);
        jdbcTemplate.update(
                """
                INSERT INTO store_business_registration_evidences (
                    evidence_id, onboarding_application_id, application_version, store_operator_account_id,
                    file_id, evidence_status, current_marker, retention_due_at, replaced_at, created_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                applicationId,
                applicationVersion,
                55L,
                fileId.toString(),
                status,
                currentMarker,
                "REPLACED".equals(status) ? now.plusSeconds(7 * 24 * 60 * 60) : null,
                "REPLACED".equals(status) ? now : null,
                now,
                0L);
    }

    private void insertPrivateLicenseMetadata(UUID fileId, long applicationId, Instant createdAt) {
        jdbcTemplate.update(
                """
                INSERT INTO file_metadata (
                    file_id, owner_type, owner_id, purpose, object_key, content_type,
                    size_bytes, checksum, visibility, storage_status, retention_policy, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                fileId.toString(),
                "STORE_ONBOARDING_APPLICATION",
                applicationId,
                "BUSINESS_LICENSE",
                "private/onboarding/" + applicationId + "/" + fileId,
                "application/pdf",
                512L,
                "a".repeat(64),
                "PRIVATE",
                "CONFIRMED",
                "BUSINESS_LICENSE_REVIEW",
                createdAt);
    }
}
