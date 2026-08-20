package com.miriyum.global.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.global.storage.entity.FileMetadata;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 파일 메타데이터 JPA 모델과 MySQL 테이블 매핑을 검증한다. */
@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class FileMetadataJpaIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    @DisplayName("파일 메타데이터의 목적과 공개 범위, 상태를 함께 저장한다")
    void persistsMetadataWithPurposeVisibilityAndStatus() {
        // given
        String fileId = UUID.randomUUID().toString();
        Instant createdAt = Instant.parse("2026-08-10T03:00:00Z");
        FileMetadata metadata = FileMetadata.createPending(
                fileId,
                "STORE",
                11L,
                FileStoragePurpose.STORE_IMAGE,
                "public/store/11/store-image/object-2",
                "image/jpeg",
                512L,
                "b".repeat(64),
                FileStorageVisibility.PUBLIC,
                "STORE_IMAGE_DEFAULT",
                createdAt);
        metadata.confirm();

        // when
        entityManager.persist(metadata);
        entityManager.flush();
        entityManager.clear();

        // then
        FileMetadata persisted = entityManager.find(FileMetadata.class, fileId);
        assertThat(persisted.getPurpose()).isEqualTo(FileStoragePurpose.STORE_IMAGE);
        assertThat(persisted.getVisibility()).isEqualTo(FileStorageVisibility.PUBLIC);
        assertThat(persisted.getStorageStatus()).isEqualTo(FileStorageStatus.CONFIRMED);
        assertThat(persisted.getObjectKey()).isEqualTo("public/store/11/store-image/object-2");
        assertThat(persisted.getCreatedAt()).isEqualTo(createdAt);
    }
}
