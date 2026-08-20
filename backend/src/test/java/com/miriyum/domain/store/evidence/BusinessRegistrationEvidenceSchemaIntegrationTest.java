package com.miriyum.domain.store.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.repository.FileMetadataRepository;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.support.TransactionTemplate;
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

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

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

    @Test
    @DisplayName("파일 삭제 전이는 잠긴 파일 검증과 증빙 연결이 커밋된 뒤에만 진행한다")
    void serializesFileDeletionAfterEvidenceLinkCommit() throws Exception {
        long applicationId = 902L;
        UUID fileId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        insertPrivateLicenseMetadata(fileId, applicationId, now);

        CountDownLatch metadataLocked = new CountDownLatch(1);
        CountDownLatch releaseEvidenceCommit = new CountDownLatch(1);
        CountDownLatch deletionAttempted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> evidenceLink = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                FileMetadata metadata = fileMetadataRepository.findByFileIdForUpdate(fileId.toString()).orElseThrow();
                assertThat(metadata.getStorageStatus().name()).isEqualTo("CONFIRMED");
                insertEvidence(applicationId, 1L, "CURRENT", 1, fileId, now);
                metadataLocked.countDown();
                await(releaseEvidenceCommit);
            }));

            assertThat(metadataLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> deletion = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                deletionAttempted.countDown();
                jdbcTemplate.update(
                        "UPDATE file_metadata SET storage_status = 'DELETED', deleted_at = ? WHERE file_id = ?",
                        now, fileId.toString());
            }));
            assertThat(deletionAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);
            assertThat(deletion.isDone()).isFalse();

            releaseEvidenceCommit.countDown();
            evidenceLink.get(5, TimeUnit.SECONDS);
            deletion.get(5, TimeUnit.SECONDS);
        }

        Integer evidenceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM store_business_registration_evidences WHERE file_id = ?",
                Integer.class, fileId.toString());
        String status = jdbcTemplate.queryForObject(
                "SELECT storage_status FROM file_metadata WHERE file_id = ?",
                String.class, fileId.toString());
        assertThat(evidenceCount).isEqualTo(1);
        assertThat(status).isEqualTo("DELETED");
    }

    private void insertEvidence(long applicationId, long applicationVersion, String status, Integer currentMarker) {
        UUID fileId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        insertPrivateLicenseMetadata(fileId, applicationId, now);
        insertEvidence(applicationId, applicationVersion, status, currentMarker, fileId, now);
    }

    private void insertEvidence(
            long applicationId,
            long applicationVersion,
            String status,
            Integer currentMarker,
            UUID fileId,
            Instant now) {
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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for concurrent transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for concurrent transaction", exception);
        }
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
