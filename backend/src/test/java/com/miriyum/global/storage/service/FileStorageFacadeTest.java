package com.miriyum.global.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.storage.FileStorageObject;
import com.miriyum.global.storage.FileStoragePort;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageRequest;
import com.miriyum.global.storage.FileStorageSaveResult;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.entity.FileMetadata;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FileStorageFacadeTest {

    private static final String FILE_CHECKSUM =
            "3b9c358f36f0a31b6ad3e14f309c7cf198ac9246e8316f9ce543d5b19ac02b80";

    @Test
    @DisplayName("파일 저장 후 완료 상태 기록이 실패하면 실패 상태로 덮어쓰지 않는다")
    void keepsPendingWhenConfirmationFails() {
        // given
        FileMetadata metadata = pendingMetadata();
        IllegalStateException confirmationFailure = new IllegalStateException("완료 상태를 기록할 수 없습니다.");
        RecordingTransactionExecutor transactionExecutor = new RecordingTransactionExecutor(confirmationFailure, null);
        FileStorageFacade facade = new FileStorageFacade(new SuccessfulFileStoragePort(), transactionExecutor);

        // when & then
        assertThatThrownBy(() -> facade.store(metadata, request(metadata.getObjectKey())))
                .isSameAs(confirmationFailure);
        assertThat(transactionExecutor.failedFileIds()).isEmpty();
    }

    @Test
    @DisplayName("파일 저장 실패 기록도 실패하면 원래 파일 저장 예외를 유지한다")
    void preservesStorageFailureWhenFailedStatusRecordingFails() {
        // given
        FileMetadata metadata = pendingMetadata();
        IllegalStateException storageFailure = new IllegalStateException("파일 저장소에 연결할 수 없습니다.");
        IllegalStateException failedStatusFailure = new IllegalStateException("실패 상태를 기록할 수 없습니다.");
        RecordingTransactionExecutor transactionExecutor = new RecordingTransactionExecutor(null, failedStatusFailure);
        FileStorageFacade facade = new FileStorageFacade(
                new FailingFileStoragePort(storageFailure), transactionExecutor);

        // when & then
        assertThatThrownBy(() -> facade.store(metadata, request(metadata.getObjectKey())))
                .isSameAs(storageFailure)
                .satisfies(exception -> assertThat(exception.getSuppressed()).containsExactly(failedStatusFailure));
    }

    @Test
    @DisplayName("메타데이터와 저장 요청의 파일 경로가 다르면 저장을 시작하지 않는다")
    void rejectsDifferentObjectKeysBeforeStorageStarts() {
        // given
        FileMetadata metadata = pendingMetadata();
        RecordingTransactionExecutor transactionExecutor = new RecordingTransactionExecutor(null, null);
        RecordingFileStoragePort fileStoragePort = new RecordingFileStoragePort();
        FileStorageFacade facade = new FileStorageFacade(fileStoragePort, transactionExecutor);

        // when & then
        assertThatThrownBy(() -> facade.store(metadata, request("public/store/11/store-image/different-object")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(transactionExecutor.pendingFileIds()).isEmpty();
        assertThat(fileStoragePort.savedObjectKeys()).isEmpty();
    }

    @Test
    @DisplayName("메타데이터와 저장 요청의 MIME 타입이 다르면 저장을 시작하지 않는다")
    void rejectsDifferentContentTypesBeforeStorageStarts() {
        FileMetadata metadata = pendingMetadata();
        RecordingTransactionExecutor transactionExecutor = new RecordingTransactionExecutor(null, null);
        RecordingFileStoragePort fileStoragePort = new RecordingFileStoragePort();
        FileStorageFacade facade = new FileStorageFacade(fileStoragePort, transactionExecutor);
        FileStorageRequest request = new FileStorageRequest(
                metadata.getObjectKey(),
                "image/png",
                4L,
                new ByteArrayInputStream("file".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> facade.store(metadata, request))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(transactionExecutor.pendingFileIds()).isEmpty();
        assertThat(fileStoragePort.savedObjectKeys()).isEmpty();
    }

    @Test
    @DisplayName("메타데이터와 저장 요청의 파일 크기가 다르면 저장을 시작하지 않는다")
    void rejectsDifferentSizesBeforeStorageStarts() {
        FileMetadata metadata = pendingMetadata();
        RecordingTransactionExecutor transactionExecutor = new RecordingTransactionExecutor(null, null);
        RecordingFileStoragePort fileStoragePort = new RecordingFileStoragePort();
        FileStorageFacade facade = new FileStorageFacade(fileStoragePort, transactionExecutor);
        FileStorageRequest request = new FileStorageRequest(
                metadata.getObjectKey(),
                "image/jpeg",
                5L,
                new ByteArrayInputStream("file".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> facade.store(metadata, request))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(transactionExecutor.pendingFileIds()).isEmpty();
        assertThat(fileStoragePort.savedObjectKeys()).isEmpty();
    }

    @Test
    @DisplayName("저장 결과의 MIME 타입이 다르면 실패 상태로 기록한다")
    void marksFailedWhenStoredContentTypeDiffers() {
        assertStorageResultMismatch(new FileStorageSaveResult(
                pendingMetadata().getObjectKey(), "image/png", 4L, FILE_CHECKSUM));
    }

    @Test
    @DisplayName("저장 결과의 파일 크기가 다르면 실패 상태로 기록한다")
    void marksFailedWhenStoredSizeDiffers() {
        assertStorageResultMismatch(new FileStorageSaveResult(
                pendingMetadata().getObjectKey(), "image/jpeg", 5L, FILE_CHECKSUM));
    }

    @Test
    @DisplayName("저장 결과의 SHA-256 체크섬이 다르면 실패 상태로 기록한다")
    void marksFailedWhenStoredChecksumDiffers() {
        assertStorageResultMismatch(new FileStorageSaveResult(
                pendingMetadata().getObjectKey(), "image/jpeg", 4L, "a".repeat(64)));
    }

    private void assertStorageResultMismatch(FileStorageSaveResult saveResult) {
        FileMetadata metadata = pendingMetadata();
        RecordingTransactionExecutor transactionExecutor = new RecordingTransactionExecutor(null, null);
        FileStorageFacade facade = new FileStorageFacade(
                new FixedResultFileStoragePort(saveResult), transactionExecutor);

        assertThatThrownBy(() -> facade.store(metadata, request(metadata.getObjectKey())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(transactionExecutor.failedFileIds()).containsExactly(metadata.getFileId());
    }

    private FileMetadata pendingMetadata() {
        return FileMetadata.createPending(
                "c8434be1-6b4d-473d-8e4c-5e0c35b2fbef",
                "STORE",
                11L,
                FileStoragePurpose.STORE_IMAGE,
                "public/store/11/store-image/object-9",
                "image/jpeg",
                4L,
                FILE_CHECKSUM,
                FileStorageVisibility.PUBLIC,
                "STORE_IMAGE_DEFAULT",
                LocalDateTime.of(2026, 8, 10, 16, 0));
    }

    private FileStorageRequest request(String objectKey) {
        return new FileStorageRequest(
                objectKey,
                "image/jpeg",
                4L,
                new ByteArrayInputStream("file".getBytes(StandardCharsets.UTF_8)));
    }

    private static final class RecordingTransactionExecutor extends FileMetadataTransactionExecutor {

        private final RuntimeException confirmationFailure;
        private final RuntimeException failedStatusFailure;
        private final List<String> pendingFileIds = new ArrayList<>();
        private final List<String> failedFileIds = new ArrayList<>();

        private RecordingTransactionExecutor(
                RuntimeException confirmationFailure, RuntimeException failedStatusFailure) {
            super(null);
            this.confirmationFailure = confirmationFailure;
            this.failedStatusFailure = failedStatusFailure;
        }

        @Override
        public FileMetadata savePending(FileMetadata metadata) {
            pendingFileIds.add(metadata.getFileId());
            return metadata;
        }

        @Override
        public FileMetadata confirm(String fileId) {
            if (confirmationFailure != null) {
                throw confirmationFailure;
            }
            throw new UnsupportedOperationException();
        }

        @Override
        public FileMetadata fail(String fileId) {
            failedFileIds.add(fileId);
            if (failedStatusFailure != null) {
                throw failedStatusFailure;
            }
            throw new UnsupportedOperationException();
        }

        private List<String> failedFileIds() {
            return failedFileIds;
        }

        private List<String> pendingFileIds() {
            return pendingFileIds;
        }
    }

    private static class RecordingFileStoragePort implements FileStoragePort {

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

    private static final class SuccessfulFileStoragePort extends RecordingFileStoragePort {
    }

    private record FixedResultFileStoragePort(FileStorageSaveResult result) implements FileStoragePort {

        @Override
        public FileStorageSaveResult save(FileStorageRequest request) {
            return result;
        }

        @Override
        public FileStorageObject read(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
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
}
