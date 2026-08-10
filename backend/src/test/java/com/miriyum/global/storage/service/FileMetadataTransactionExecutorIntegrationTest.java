package com.miriyum.global.storage.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.repository.FileMetadataRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 파일 메타데이터 상태 기록이 외부 파일 저장 흐름과 독립적으로 남는지 검증한다. */
@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class FileMetadataTransactionExecutorIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private FileMetadataTransactionExecutor transactionExecutor;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("바깥 작업이 롤백되어도 대기 중인 파일 메타데이터를 독립적으로 저장한다")
    void savesPendingMetadataInIndependentTransaction() {
        // given
        String fileId = UUID.randomUUID().toString();
        FileMetadata metadata = FileMetadata.createPending(
                fileId,
                "STORE",
                11L,
                FileStoragePurpose.STORE_IMAGE,
                "public/store/11/store-image/object-5",
                "image/jpeg",
                512L,
                "e".repeat(64),
                FileStorageVisibility.PUBLIC,
                "STORE_IMAGE_DEFAULT",
                LocalDateTime.of(2026, 8, 10, 14, 0));
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);

        // when
        outerTransaction.executeWithoutResult(status -> {
            transactionExecutor.savePending(metadata);
            status.setRollbackOnly();
        });

        // then
        assertThat(fileMetadataRepository.findById(fileId))
                .isPresent()
                .get()
                .extracting(FileMetadata::getStorageStatus)
                .isEqualTo(FileStorageStatus.PENDING);
    }

    @Test
    @DisplayName("대기 중인 파일 메타데이터를 파일 식별자로 저장 완료 처리한다")
    void confirmsPendingMetadataByFileId() {
        // given
        String fileId = UUID.randomUUID().toString();
        transactionExecutor.savePending(FileMetadata.createPending(
                fileId,
                "STORE",
                11L,
                FileStoragePurpose.STORE_IMAGE,
                "public/store/11/store-image/object-6",
                "image/jpeg",
                512L,
                "f".repeat(64),
                FileStorageVisibility.PUBLIC,
                "STORE_IMAGE_DEFAULT",
                LocalDateTime.of(2026, 8, 10, 14, 30)));

        // when
        transactionExecutor.confirm(fileId);

        // then
        assertThat(fileMetadataRepository.findById(fileId))
                .isPresent()
                .get()
                .extracting(FileMetadata::getStorageStatus)
                .isEqualTo(FileStorageStatus.CONFIRMED);
    }
}
