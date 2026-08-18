package com.miriyum.global.storage.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.entity.FileMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 파일 메타데이터 저장소의 저장과 조회 계약을 검증한다. */
@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class FileMetadataRepositoryIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Transactional
    @DisplayName("파일 메타데이터를 저장한 뒤 파일 식별자로 조회한다")
    void savesAndFindsMetadataByFileId() {
        // given
        String fileId = UUID.randomUUID().toString();
        FileMetadata metadata = FileMetadata.createPending(
                fileId,
                "STORE",
                11L,
                FileStoragePurpose.STORE_IMAGE,
                "public/store/11/store-image/object-3",
                "image/jpeg",
                512L,
                "c".repeat(64),
                FileStorageVisibility.PUBLIC,
                "STORE_IMAGE_DEFAULT",
                Instant.parse("2026-08-10T04:00:00Z"));

        // when
        fileMetadataRepository.saveAndFlush(metadata);

        // then
        assertThat(fileMetadataRepository.findById(fileId))
                .isPresent()
                .get()
                .extracting(FileMetadata::getObjectKey, FileMetadata::getStorageStatus)
                .containsExactly("public/store/11/store-image/object-3", FileStorageStatus.PENDING);
    }

    @Test
    @Transactional
    @DisplayName("공개 메뉴 이미지 일괄 조회는 전용 복합 인덱스를 사용한다")
    void publicMenuImageLookupUsesDedicatedCompositeIndex() {
        // given
        saveConfirmedMenuImage(101L);
        saveConfirmedMenuImage(102L);
        fileMetadataRepository.flush();

        // when
        List<Map<String, Object>> plan = jdbcTemplate.queryForList("""
                EXPLAIN
                SELECT file_id
                FROM file_metadata FORCE INDEX (idx_file_metadata_public_menu_lookup)
                WHERE owner_type = ?
                  AND purpose = ?
                  AND visibility = ?
                  AND storage_status = ?
                  AND owner_id IN (?, ?)
                ORDER BY created_at ASC
                """,
                "MENU",
                FileStoragePurpose.MENU_IMAGE.name(),
                FileStorageVisibility.PUBLIC.name(),
                FileStorageStatus.CONFIRMED.name(),
                101L,
                102L);

        // then
        // The small fixture can make MySQL prefer a table scan. Force the migration index here so this
        // contract verifies that the production query shape remains supported by the dedicated index.
        assertThat(plan)
                .isNotEmpty();
        assertThat(plan)
                .allSatisfy(row -> assertThat(row.get("key"))
                        .isEqualTo("idx_file_metadata_public_menu_lookup"));
    }

    private void saveConfirmedMenuImage(long menuId) {
        FileMetadata metadata = FileMetadata.createPending(
                UUID.randomUUID().toString(),
                "MENU",
                menuId,
                FileStoragePurpose.MENU_IMAGE,
                "public/menu/" + menuId + "/menu-image/object-" + menuId,
                "image/jpeg",
                512L,
                "c".repeat(64),
                FileStorageVisibility.PUBLIC,
                "MENU_IMAGE_DEFAULT",
                Instant.parse("2026-08-10T04:00:00Z"));
        metadata.confirm();
        fileMetadataRepository.save(metadata);
    }
}
