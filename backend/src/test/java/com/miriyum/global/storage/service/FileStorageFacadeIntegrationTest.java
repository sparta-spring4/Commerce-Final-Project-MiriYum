package com.miriyum.global.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.global.storage.FileStorageObject;
import com.miriyum.global.storage.FileStorageMetadata;
import com.miriyum.global.storage.FileStorageOwner;
import com.miriyum.global.storage.FileStoragePort;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageRequest;
import com.miriyum.global.storage.FileStorageSaveResult;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.repository.FileMetadataRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 파일 저장 파사드가 파일 저장 결과와 메타데이터 상태를 맞추는지 검증한다. */
@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class FileStorageFacadeIntegrationTest {

    private static final String FILE_CHECKSUM =
            "3b9c358f36f0a31b6ad3e14f309c7cf198ac9246e8316f9ce543d5b19ac02b80";

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
    @DisplayName("파일 저장에 성공하면 메타데이터를 저장 완료 상태로 확정한다")
    void confirmsMetadataWhenFileStorageSucceeds() {
        // given
        String fileId = UUID.randomUUID().toString();
        String objectKey = "public/store/11/store-image/object-7";
        FileStorageMetadata metadata = pendingMetadata(fileId, objectKey, FILE_CHECKSUM,
                Instant.parse("2026-08-10T06:00:00Z"));
        RecordingFileStoragePort fileStoragePort = new RecordingFileStoragePort();
        FileStorageFacade facade = new FileStorageFacade(fileStoragePort, transactionExecutor);
        FileStorageRequest request = new FileStorageRequest(
                objectKey,
                "image/jpeg",
                4L,
                new ByteArrayInputStream("file".getBytes(StandardCharsets.UTF_8)));

        // when
        facade.store(metadata, request);

        // then
        assertThat(fileStoragePort.savedObjectKeys()).containsExactly(objectKey);
        assertThat(fileMetadataRepository.findById(fileId))
                .isPresent()
                .get()
                .extracting(FileMetadata::getStorageStatus)
                .isEqualTo(FileStorageStatus.CONFIRMED);
    }

    @Test
    @DisplayName("바깥 업무 트랜잭션이 롤백되면 지연 공개 확정도 함께 롤백되어 파일이 PENDING으로 남는다")
    void keepsPendingWhenOwnerTransactionRollsBackAfterDeferredConfirmation() {
        String fileId = UUID.randomUUID().toString();
        String objectKey = "public/store/11/store-image/object-deferred-confirmation";
        FileStorageMetadata metadata = pendingMetadata(fileId, objectKey, FILE_CHECKSUM,
                Instant.parse("2026-08-10T06:15:00Z"));
        FileStorageFacade facade = new FileStorageFacade(new RecordingFileStoragePort(), transactionExecutor);

        facade.storePending(metadata, new FileStorageRequest(
                objectKey,
                "image/jpeg",
                4L,
                new ByteArrayInputStream("file".getBytes(StandardCharsets.UTF_8))));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            facade.confirmWithinCurrentTransaction(UUID.fromString(fileId));
            status.setRollbackOnly();
        });

        assertThat(fileMetadataRepository.findById(fileId))
                .isPresent()
                .get()
                .extracting(FileMetadata::getStorageStatus)
                .isEqualTo(FileStorageStatus.PENDING);
    }

    @Test
    @DisplayName("파일 저장에 실패하면 메타데이터를 실패 상태로 기록하고 예외를 다시 던진다")
    void marksMetadataFailedWhenFileStorageFails() {
        // given
        String fileId = UUID.randomUUID().toString();
        String objectKey = "public/store/11/store-image/object-8";
        FileStorageMetadata metadata = pendingMetadata(fileId, objectKey, "b".repeat(64),
                Instant.parse("2026-08-10T06:30:00Z"));
        IllegalStateException storageFailure = new IllegalStateException("파일 저장소에 연결할 수 없습니다.");
        FileStorageFacade facade = new FileStorageFacade(
                new FailingFileStoragePort(storageFailure), transactionExecutor);
        FileStorageRequest request = new FileStorageRequest(
                objectKey,
                "image/jpeg",
                4L,
                new ByteArrayInputStream("file".getBytes(StandardCharsets.UTF_8)));

        // when & then
        assertThatThrownBy(() -> facade.store(metadata, request)).isSameAs(storageFailure);
        assertThat(fileMetadataRepository.findById(fileId))
                .isPresent()
                .get()
                .extracting(FileMetadata::getStorageStatus)
                .isEqualTo(FileStorageStatus.FAILED);
    }

    @Test
    @DisplayName("저장소 삭제 실패 뒤 같은 파일 식별자로 재호출하면 DELETED 정본 객체를 멱등 재시도한다")
    void retriesStorageDeletionAfterMetadataIsDeleted() {
        String fileId = UUID.randomUUID().toString();
        String objectKey = "public/store/11/store-image/object-delete-retry";
        transactionExecutor.savePending(FileMetadata.createPending(
                fileId,
                "STORE",
                11L,
                FileStoragePurpose.STORE_IMAGE,
                objectKey,
                "image/jpeg",
                4L,
                FILE_CHECKSUM,
                FileStorageVisibility.PUBLIC,
                "STORE_IMAGE_DEFAULT",
                Instant.parse("2026-08-10T07:00:00Z")));
        transactionExecutor.confirm(fileId);

        IllegalStateException storageFailure = new IllegalStateException("저장소 삭제 실패");
        FailingOnceDeleteFileStoragePort fileStoragePort = new FailingOnceDeleteFileStoragePort(storageFailure);
        FileStorageFacade facade = new FileStorageFacade(fileStoragePort, transactionExecutor);

        assertThatThrownBy(() -> facade.delete(UUID.fromString(fileId), Instant.parse("2026-08-15T00:00:00Z")))
                .isSameAs(storageFailure);
        assertThat(fileMetadataRepository.findById(fileId))
                .isPresent()
                .get()
                .extracting(FileMetadata::getStorageStatus)
                .isEqualTo(FileStorageStatus.DELETED);
        assertThat(fileMetadataRepository.findByFileIdAndStorageStatus(fileId, FileStorageStatus.CONFIRMED))
                .isEmpty();

        FileStorageMetadata deleted = facade.delete(
                UUID.fromString(fileId), Instant.parse("2026-08-15T00:01:00Z"));

        assertThat(deleted.status()).isEqualTo(FileStorageStatus.DELETED);
        assertThat(fileStoragePort.deletedObjectKeys()).containsExactly(objectKey, objectKey);
        assertThat(fileMetadataRepository.findById(fileId))
                .isPresent()
                .get()
                .extracting(FileMetadata::getObjectCleanupCompletedAt)
                .isNotNull();
    }

    private FileStorageMetadata pendingMetadata(String fileId, String objectKey, String checksum, Instant createdAt) {
        return new FileStorageMetadata(
                UUID.fromString(fileId),
                new FileStorageOwner("STORE", 11L),
                FileStoragePurpose.STORE_IMAGE,
                objectKey,
                "image/jpeg",
                4L,
                checksum,
                FileStorageVisibility.PUBLIC,
                FileStorageStatus.PENDING,
                "STORE_IMAGE_DEFAULT",
                createdAt,
                null);
    }

    private static final class RecordingFileStoragePort implements FileStoragePort {

        private final List<String> savedObjectKeys = new ArrayList<>();

        @Override
        public FileStorageSaveResult save(FileStorageRequest request) {
            savedObjectKeys.add(request.objectKey());
            return new FileStorageSaveResult(
                    request.objectKey(), request.contentType(), request.sizeBytes(), FILE_CHECKSUM);
        }

        @Override
        public FileStorageObject read(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
        }

        private List<String> savedObjectKeys() {
            return savedObjectKeys;
        }
    }

    private record FailingFileStoragePort(RuntimeException failure) implements FileStoragePort {

        @Override
        public FileStorageSaveResult save(FileStorageRequest request) {
            throw failure;
        }

        @Override
        public FileStorageObject read(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
        }
    }

    private static final class FailingOnceDeleteFileStoragePort implements FileStoragePort {

        private final RuntimeException failure;
        private final List<String> deletedObjectKeys = new ArrayList<>();
        private boolean failed;

        private FailingOnceDeleteFileStoragePort(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public FileStorageSaveResult save(FileStorageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileStorageObject read(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
            deletedObjectKeys.add(objectKey);
            if (!failed) {
                failed = true;
                throw failure;
            }
        }

        private List<String> deletedObjectKeys() {
            return deletedObjectKeys;
        }
    }
}
