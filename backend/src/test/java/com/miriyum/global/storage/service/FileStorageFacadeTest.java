package com.miriyum.global.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.storage.FileStorageObject;
import com.miriyum.global.storage.FileStoragePort;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageRequest;
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

    private FileMetadata pendingMetadata() {
        return FileMetadata.createPending(
                "c8434be1-6b4d-473d-8e4c-5e0c35b2fbef",
                "STORE",
                11L,
                FileStoragePurpose.STORE_IMAGE,
                "public/store/11/store-image/object-9",
                "image/jpeg",
                4L,
                "c".repeat(64),
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
        public void save(FileStorageRequest request) {
            savedObjectKeys.add(request.objectKey());
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

    private record FailingFileStoragePort(RuntimeException failure) implements FileStoragePort {

        @Override
        public void save(FileStorageRequest request) {
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
