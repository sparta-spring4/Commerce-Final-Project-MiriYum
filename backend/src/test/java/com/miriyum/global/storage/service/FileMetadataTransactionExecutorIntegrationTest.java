package com.miriyum.global.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.repository.FileMetadataRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 파일 메타데이터 상태 기록이 외부 파일 저장 흐름과 독립적으로 남는지 검증한다. */
@Tag("integration")
@Tag("integration-shard-b")
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
                Instant.parse("2026-08-10T05:00:00Z"));
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
                Instant.parse("2026-08-10T05:30:00Z")));

        // when
        transactionExecutor.confirm(fileId);

        // then
        assertThat(fileMetadataRepository.findById(fileId))
                .isPresent()
                .get()
                .extracting(FileMetadata::getStorageStatus)
                .isEqualTo(FileStorageStatus.CONFIRMED);
    }

    @Test
    @DisplayName("이미 종료된 파일 식별자로 대기 메타데이터를 다시 저장하지 않는다")
    void doesNotOverwriteTerminalMetadataWithDuplicateFileId() {
        // given
        String fileId = UUID.randomUUID().toString();
        transactionExecutor.savePending(createMetadata(fileId, 11L, "object-original"));
        transactionExecutor.confirm(fileId);

        FileMetadata duplicate = createMetadata(fileId, 99L, "object-duplicate");

        // when & then
        assertThatThrownBy(() -> transactionExecutor.savePending(duplicate))
                .isInstanceOf(FileMetadataConflictException.class)
                .hasMessageContaining("이미 존재");

        assertThat(fileMetadataRepository.findById(fileId))
                .isPresent()
                .get()
                .satisfies(metadata -> {
                    assertThat(metadata.getOwnerId()).isEqualTo(11L);
                    assertThat(metadata.getObjectKey()).endsWith("object-original");
                    assertThat(metadata.getStorageStatus()).isEqualTo(FileStorageStatus.CONFIRMED);
                });
    }

    @Test
    @DisplayName("동시에 완료와 실패를 기록해도 하나의 종료 상태만 반영한다")
    void allowsOnlyOneTerminalTransitionUnderConcurrency() throws Exception {
        // given
        String fileId = UUID.randomUUID().toString();
        transactionExecutor.savePending(createMetadata(fileId, 11L, "object-race"));
        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch transition = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        try {
            // when
            List<Future<FileStorageStatus>> futures = List.of(
                    executorService.submit(() -> transitionInTransaction(
                            fileId, FileStorageStatus.CONFIRMED, loaded, transition)),
                    executorService.submit(() -> transitionInTransaction(
                            fileId, FileStorageStatus.FAILED, loaded, transition)));

            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
            transition.countDown();

            int successCount = 0;
            int conflictCount = 0;
            for (Future<FileStorageStatus> future : futures) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                    successCount++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause())
                            .isInstanceOf(OptimisticLockingFailureException.class);
                    conflictCount++;
                }
            }

            // then
            assertThat(successCount).isEqualTo(1);
            assertThat(conflictCount).isEqualTo(1);
            assertThat(fileMetadataRepository.findById(fileId))
                    .isPresent()
                    .get()
                    .extracting(FileMetadata::getStorageStatus)
                    .isIn(FileStorageStatus.CONFIRMED, FileStorageStatus.FAILED);
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    @DisplayName("동시에 파일 삭제를 요청해도 DB 정본의 삭제 상태와 객체 키로 수렴한다")
    void returnsDeletedMetadataForConcurrentDeleteRequests() throws Exception {
        String fileId = UUID.randomUUID().toString();
        transactionExecutor.savePending(createMetadata(fileId, 11L, "object-delete-race"));
        transactionExecutor.confirm(fileId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        try {
            List<Future<FileMetadata>> futures = List.of(
                    executorService.submit(() -> deleteAfterStart(
                            fileId, Instant.parse("2026-08-15T00:00:00Z"), start)),
                    executorService.submit(() -> deleteAfterStart(
                            fileId, Instant.parse("2026-08-15T00:01:00Z"), start)));

            start.countDown();

            for (Future<FileMetadata> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS).getStorageStatus())
                        .isEqualTo(FileStorageStatus.DELETED);
            }
            assertThat(fileMetadataRepository.findById(fileId))
                    .isPresent()
                    .get()
                    .satisfies(metadata -> {
                        assertThat(metadata.getStorageStatus()).isEqualTo(FileStorageStatus.DELETED);
                        assertThat(metadata.getObjectKey()).endsWith("object-delete-race");
                    });
        } finally {
            executorService.shutdownNow();
        }
    }

    private FileStorageStatus transitionInTransaction(
            String fileId,
            FileStorageStatus nextStatus,
            CountDownLatch loaded,
            CountDownLatch transition) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            FileMetadata metadata = fileMetadataRepository.findById(fileId).orElseThrow();
            loaded.countDown();
            await(transition);
            if (nextStatus == FileStorageStatus.CONFIRMED) {
                metadata.confirm();
            } else {
                metadata.fail();
            }
            return fileMetadataRepository.saveAndFlush(metadata).getStorageStatus();
        });
    }

    private FileMetadata deleteAfterStart(String fileId, Instant deletedAt, CountDownLatch start) {
        await(start);
        return transactionExecutor.deleteOrGetDeleted(fileId, deletedAt);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 대기 시간이 초과되었습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 대기가 중단되었습니다.", exception);
        }
    }

    private FileMetadata createMetadata(String fileId, long ownerId, String objectName) {
        return FileMetadata.createPending(
                fileId,
                "STORE",
                ownerId,
                FileStoragePurpose.STORE_IMAGE,
                "public/store/" + ownerId + "/store-image/" + objectName,
                "image/jpeg",
                512L,
                "a".repeat(64),
                FileStorageVisibility.PUBLIC,
                "STORE_IMAGE_DEFAULT",
                Instant.parse("2026-08-10T06:00:00Z"));
    }
}
