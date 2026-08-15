package com.miriyum.global.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FileStorageFacadeTest {

    private static final String FILE_CHECKSUM =
            "3b9c358f36f0a31b6ad3e14f309c7cf198ac9246e8316f9ce543d5b19ac02b80";

    @Test
    @DisplayName("파일 저장 후 완료 상태 기록이 실패하면 실패 상태로 덮어쓰지 않는다")
    void keepsPendingWhenConfirmationFails() {
        // given
        FileStorageMetadata metadata = pendingMetadata();
        IllegalStateException confirmationFailure = new IllegalStateException("완료 상태를 기록할 수 없습니다.");
        RecordingTransactionExecutor transactionExecutor = new RecordingTransactionExecutor(confirmationFailure, null);
        FileStorageFacade facade = new FileStorageFacade(new SuccessfulFileStoragePort(), transactionExecutor);

        // when & then
        assertThatThrownBy(() -> facade.store(metadata, request(metadata.objectKey())))
                .isSameAs(confirmationFailure);
        assertThat(transactionExecutor.failedFileIds()).isEmpty();
    }

    @Test
    @DisplayName("파일 저장 실패 기록도 실패하면 원래 파일 저장 예외를 유지한다")
    void preservesStorageFailureWhenFailedStatusRecordingFails() {
        // given
        FileStorageMetadata metadata = pendingMetadata();
        IllegalStateException storageFailure = new IllegalStateException("파일 저장소에 연결할 수 없습니다.");
        IllegalStateException failedStatusFailure = new IllegalStateException("실패 상태를 기록할 수 없습니다.");
        RecordingTransactionExecutor transactionExecutor = new RecordingTransactionExecutor(null, failedStatusFailure);
        FileStorageFacade facade = new FileStorageFacade(
                new FailingFileStoragePort(storageFailure), transactionExecutor);

        // when & then
        assertThatThrownBy(() -> facade.store(metadata, request(metadata.objectKey())))
                .isSameAs(storageFailure)
                .satisfies(exception -> assertThat(exception.getSuppressed()).containsExactly(failedStatusFailure));
    }

    @Test
    @DisplayName("메타데이터와 저장 요청의 파일 경로가 다르면 저장을 시작하지 않는다")
    void rejectsDifferentObjectKeysBeforeStorageStarts() {
        // given
        FileStorageMetadata metadata = pendingMetadata();
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
    @DisplayName("완료된 메타데이터로 새 파일 저장을 시작하지 않는다")
    void rejectsNonPendingMetadataBeforeStorageStarts() {
        // given
        FileStorageMetadata pending = pendingMetadata();
        FileStorageMetadata confirmed = new FileStorageMetadata(
                pending.fileId(), pending.owner(), pending.purpose(), pending.objectKey(), pending.contentType(),
                pending.sizeBytes(), pending.checksum(), pending.visibility(), FileStorageStatus.CONFIRMED,
                pending.retentionPolicy(), pending.createdAt(), null);
        RecordingTransactionExecutor transactionExecutor = new RecordingTransactionExecutor(null, null);
        RecordingFileStoragePort fileStoragePort = new RecordingFileStoragePort();
        FileStorageFacade facade = new FileStorageFacade(fileStoragePort, transactionExecutor);

        // when & then
        assertThatThrownBy(() -> facade.store(confirmed, request(confirmed.objectKey())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(transactionExecutor.pendingFileIds()).isEmpty();
        assertThat(fileStoragePort.savedObjectKeys()).isEmpty();
    }

    @Test
    @DisplayName("메타데이터와 저장 요청의 MIME 타입이 다르면 저장을 시작하지 않는다")
    void rejectsDifferentContentTypesBeforeStorageStarts() {
        FileStorageMetadata metadata = pendingMetadata();
        RecordingTransactionExecutor transactionExecutor = new RecordingTransactionExecutor(null, null);
        RecordingFileStoragePort fileStoragePort = new RecordingFileStoragePort();
        FileStorageFacade facade = new FileStorageFacade(fileStoragePort, transactionExecutor);
        FileStorageRequest request = new FileStorageRequest(
                metadata.objectKey(),
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
        FileStorageMetadata metadata = pendingMetadata();
        RecordingTransactionExecutor transactionExecutor = new RecordingTransactionExecutor(null, null);
        RecordingFileStoragePort fileStoragePort = new RecordingFileStoragePort();
        FileStorageFacade facade = new FileStorageFacade(fileStoragePort, transactionExecutor);
        FileStorageRequest request = new FileStorageRequest(
                metadata.objectKey(),
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
                pendingMetadata().objectKey(), "image/png", 4L, FILE_CHECKSUM));
    }

    @Test
    @DisplayName("저장 결과의 파일 크기가 다르면 실패 상태로 기록한다")
    void marksFailedWhenStoredSizeDiffers() {
        assertStorageResultMismatch(new FileStorageSaveResult(
                pendingMetadata().objectKey(), "image/jpeg", 5L, FILE_CHECKSUM));
    }

    @Test
    @DisplayName("저장 결과의 SHA-256 체크섬이 다르면 실패 상태로 기록한다")
    void marksFailedWhenStoredChecksumDiffers() {
        assertStorageResultMismatch(new FileStorageSaveResult(
                pendingMetadata().objectKey(), "image/jpeg", 4L, "a".repeat(64)));
    }

    @Test
    @DisplayName("저장 완료된 파일은 DB 정본 객체 키로 메타데이터를 먼저 삭제 처리한 뒤 저장소 객체를 삭제한다")
    void deletesMetadataBeforeStorageObjectUsingAuthoritativeObjectKey() {
        FileStorageMetadata confirmed = confirmedMetadata();
        List<String> events = new ArrayList<>();
        RecordingTransactionExecutor transactionExecutor = new RecordingTransactionExecutor(null, null, events);
        RecordingFileStoragePort fileStoragePort = new RecordingFileStoragePort(events);
        FileStorageFacade facade = new FileStorageFacade(fileStoragePort, transactionExecutor);

        facade.delete(confirmed.fileId(), Instant.parse("2026-08-15T00:00:00Z"));

        assertThat(events).containsExactly(
                "metadata-delete:" + confirmed.fileId(),
                "storage-delete:public/store/11/store-image/deleted-object");
    }

    @Test
    @DisplayName("저장소 객체 삭제가 실패한 뒤 같은 파일 식별자로 다시 호출하면 삭제를 재시도한다")
    void retriesStorageDeletionForAlreadyDeletedMetadata() {
        FileStorageMetadata confirmed = confirmedMetadata();
        List<String> events = new ArrayList<>();
        RecordingTransactionExecutor transactionExecutor = new RecordingTransactionExecutor(null, null, events);
        IllegalStateException deletionFailure = new IllegalStateException("저장소 삭제 실패");
        FailingOnceDeleteFileStoragePort fileStoragePort = new FailingOnceDeleteFileStoragePort(events, deletionFailure);
        FileStorageFacade facade = new FileStorageFacade(fileStoragePort, transactionExecutor);

        assertThatThrownBy(() -> facade.delete(confirmed.fileId(), Instant.parse("2026-08-15T00:00:00Z")))
                .isSameAs(deletionFailure);
        assertThat(facade.delete(confirmed.fileId(), Instant.parse("2026-08-15T00:01:00Z")).status())
                .isEqualTo(FileStorageStatus.DELETED);
        assertThat(events).containsExactly(
                "metadata-delete:" + confirmed.fileId(),
                "storage-delete:public/store/11/store-image/deleted-object",
                "metadata-delete:" + confirmed.fileId(),
                "storage-delete:public/store/11/store-image/deleted-object");
    }

    private void assertStorageResultMismatch(FileStorageSaveResult saveResult) {
        FileStorageMetadata metadata = pendingMetadata();
        RecordingTransactionExecutor transactionExecutor = new RecordingTransactionExecutor(null, null);
        FileStorageFacade facade = new FileStorageFacade(
                new FixedResultFileStoragePort(saveResult), transactionExecutor);

        assertThatThrownBy(() -> facade.store(metadata, request(metadata.objectKey())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(transactionExecutor.failedFileIds()).containsExactly(metadata.fileId().toString());
    }

    private FileStorageMetadata pendingMetadata() {
        return new FileStorageMetadata(
                UUID.fromString("c8434be1-6b4d-473d-8e4c-5e0c35b2fbef"),
                new FileStorageOwner("STORE", 11L),
                FileStoragePurpose.STORE_IMAGE,
                "public/store/11/store-image/object-9",
                "image/jpeg",
                4L,
                FILE_CHECKSUM,
                FileStorageVisibility.PUBLIC,
                FileStorageStatus.PENDING,
                "STORE_IMAGE_DEFAULT",
                Instant.parse("2026-08-10T07:00:00Z"),
                null);
    }

    private FileStorageMetadata confirmedMetadata() {
        FileStorageMetadata pending = pendingMetadata();
        return new FileStorageMetadata(
                pending.fileId(), pending.owner(), pending.purpose(), pending.objectKey(), pending.contentType(),
                pending.sizeBytes(), pending.checksum(), pending.visibility(), FileStorageStatus.CONFIRMED,
                pending.retentionPolicy(), pending.createdAt(), null);
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
        private final List<String> events;

        private RecordingTransactionExecutor(
                RuntimeException confirmationFailure, RuntimeException failedStatusFailure) {
            this(confirmationFailure, failedStatusFailure, new ArrayList<>());
        }

        private RecordingTransactionExecutor(
                RuntimeException confirmationFailure,
                RuntimeException failedStatusFailure,
                List<String> events
        ) {
            super(null);
            this.confirmationFailure = confirmationFailure;
            this.failedStatusFailure = failedStatusFailure;
            this.events = events;
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

        @Override
        public FileMetadata deleteOrGetDeleted(String fileId, Instant deletedAt) {
            events.add("metadata-delete:" + fileId);
            FileMetadata metadata = FileMetadata.createPending(
                    fileId,
                    "STORE",
                    11L,
                    FileStoragePurpose.STORE_IMAGE,
                    "public/store/11/store-image/deleted-object",
                    "image/jpeg",
                    4L,
                    FILE_CHECKSUM,
                    FileStorageVisibility.PUBLIC,
                    "STORE_IMAGE_DEFAULT",
                    Instant.parse("2026-08-10T07:00:00Z"));
            metadata.confirm();
            metadata.delete(deletedAt);
            return metadata;
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
        private final List<String> events;

        private RecordingFileStoragePort() {
            this(new ArrayList<>());
        }

        private RecordingFileStoragePort(List<String> events) {
            this.events = events;
        }

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
            events.add("storage-delete:" + objectKey);
        }

        private List<String> savedObjectKeys() {
            return savedObjectKeys;
        }
    }

    private static final class SuccessfulFileStoragePort extends RecordingFileStoragePort {
    }

    private static final class FailingOnceDeleteFileStoragePort implements FileStoragePort {

        private final List<String> events;
        private final RuntimeException failure;
        private boolean failed;

        private FailingOnceDeleteFileStoragePort(List<String> events, RuntimeException failure) {
            this.events = events;
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
            events.add("storage-delete:" + objectKey);
            if (!failed) {
                failed = true;
                throw failure;
            }
        }
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
