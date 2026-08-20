package com.miriyum.global.storage;

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
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 파일 메타데이터 테이블의 저장 계약을 실제 MySQL에서 검증한다. */
@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class FileStorageMetadataSchemaIntegrationTest {

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
    @DisplayName("파일 원문 대신 메타데이터를 저장하고 다시 조회한다")
    void storesAndReadsFileMetadataWithoutFileBytes() {
        // given
        UUID fileId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-10T03:00:00Z");

        // when
        jdbcTemplate.update(
                """
                INSERT INTO file_metadata (
                    file_id, owner_type, owner_id, purpose, object_key, content_type,
                    size_bytes, checksum, visibility, storage_status, retention_policy, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                fileId.toString(),
                "STORE",
                11L,
                "STORE_IMAGE",
                "public/store/11/store-image/object-1",
                "image/jpeg",
                512L,
                "a".repeat(64),
                "PUBLIC",
                "CONFIRMED",
                "STORE_IMAGE_DEFAULT",
                createdAt);

        // then
        String objectKey = jdbcTemplate.queryForObject(
                "SELECT object_key FROM file_metadata WHERE file_id = ?", String.class, fileId.toString());
        assertThat(objectKey).isEqualTo("public/store/11/store-image/object-1");
    }

    @Test
    @DisplayName("DB에서도 사업자등록증 공개 저장을 거절한다")
    void rejectsPublicBusinessLicenseAtDatabaseBoundary() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO file_metadata (
                    file_id, owner_type, owner_id, purpose, object_key, content_type,
                    size_bytes, checksum, visibility, storage_status, retention_policy, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                "STORE_OPERATOR",
                11L,
                "BUSINESS_LICENSE",
                "private/store-operator/11/business-license/object-public",
                "image/jpeg",
                512L,
                "a".repeat(64),
                "PUBLIC",
                "PENDING",
                "BUSINESS_LICENSE_DEFAULT",
                Instant.parse("2026-08-10T03:30:00Z")))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_file_metadata_purpose_visibility");
    }

    @Test
    @DisplayName("DB에서도 매장·메뉴 이미지의 비공개 저장을 거절한다")
    void rejectsPrivatePublicImageAtDatabaseBoundary() {
        assertThatThrownBy(() -> insertMetadata("STORE_IMAGE", "PRIVATE"))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_file_metadata_purpose_visibility");
        assertThatThrownBy(() -> insertMetadata("MENU_IMAGE", "PRIVATE"))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_file_metadata_purpose_visibility");
    }

    @Test
    @DisplayName("DB는 비공개 사업자등록증과 공개 메뉴 이미지를 저장한다")
    void allowsConfiguredPurposeVisibilityCombinations() {
        insertMetadata("BUSINESS_LICENSE", "PRIVATE");
        insertMetadata("MENU_IMAGE", "PUBLIC");
    }

    private void insertMetadata(String purpose, String visibility) {
        jdbcTemplate.update(
                """
                INSERT INTO file_metadata (
                    file_id, owner_type, owner_id, purpose, object_key, content_type,
                    size_bytes, checksum, visibility, storage_status, retention_policy, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                "STORE_OPERATOR",
                11L,
                purpose,
                "private/store-operator/11/" + purpose.toLowerCase() + "/" + UUID.randomUUID(),
                "image/jpeg",
                512L,
                "a".repeat(64),
                visibility,
                "PENDING",
                purpose + "_DEFAULT",
                Instant.parse("2026-08-10T03:30:00Z"));
    }
}
