package com.miriyum.global.storage.service;

import com.miriyum.global.storage.FileStoragePort;
import com.miriyum.global.storage.FileStorageMetadata;
import com.miriyum.global.storage.FileStorageRequest;
import com.miriyum.global.storage.FileStorageSaveResult;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.entity.FileMetadata;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 파일 저장 결과와 메타데이터 상태 전이를 순서대로 조정한다. */
@Slf4j
@RequiredArgsConstructor
public class FileStorageFacade {

    private final FileStoragePort fileStoragePort;
    private final FileMetadataTransactionExecutor transactionExecutor;

    /** 대기 상태를 저장한 뒤 파일을 저장하고, 무결성 검증에 성공한 경우에만 완료 상태로 전환한다. */
    public FileStorageMetadata store(FileStorageMetadata metadata, FileStorageRequest request) {
        validatePendingMetadata(metadata);
        FileMetadata persistedMetadata = FileMetadata.createPending(metadata);
        validateRequest(persistedMetadata, request);
        transactionExecutor.savePending(persistedMetadata);
        try {
            FileStorageSaveResult saveResult = fileStoragePort.save(request);
            validateSaveResult(persistedMetadata, saveResult);
        } catch (RuntimeException exception) {
            markFailedWithoutHidingStorageFailure(persistedMetadata.getFileId(), exception);
            throw exception;
        }
        return transactionExecutor.confirm(persistedMetadata.getFileId()).toPublicMetadata();
    }

    /**
     * 공개 조회를 먼저 차단한 뒤 저장소 원본을 삭제한다.
     *
     * <p>후속 조회 경로가 {@code CONFIRMED} 상태만 서빙한다는 불변식 아래, 원본 삭제가 일시적으로 실패해도
     * {@code DELETED} 메타데이터는 앱 공개 URL에서 제외된다. 이미 {@code DELETED}인 파일도 DB 정본의
     * 객체 키로 외부 삭제를 멱등 재시도한다.</p>
     */
    public FileStorageMetadata delete(UUID fileId, Instant deletedAt) {
        if (fileId == null || deletedAt == null) {
            throw new IllegalArgumentException("파일 식별자와 삭제 시각은 필수입니다.");
        }
        FileStorageMetadata deleted = transactionExecutor.deleteOrGetDeleted(fileId.toString(), deletedAt)
                .toPublicMetadata();
        try {
            fileStoragePort.delete(deleted.objectKey());
        } catch (RuntimeException exception) {
            log.warn(
                    "event=file_storage_object_delete_failed file_id={} object_key={}",
                    deleted.fileId(),
                    deleted.objectKey(),
                    exception);
            throw exception;
        }
        return deleted;
    }

    private void validatePendingMetadata(FileStorageMetadata metadata) {
        if (metadata.status() != FileStorageStatus.PENDING || metadata.deletedAt() != null) {
            throw new IllegalArgumentException("새 파일 저장은 삭제 시각이 없는 대기 상태 메타데이터만 허용합니다.");
        }
    }

    private void markFailedWithoutHidingStorageFailure(String fileId, RuntimeException storageFailure) {
        try {
            transactionExecutor.fail(fileId);
        } catch (RuntimeException metadataFailure) {
            storageFailure.addSuppressed(metadataFailure);
        }
    }

    private void validateRequest(FileMetadata metadata, FileStorageRequest request) {
        if (!metadata.getObjectKey().equals(request.objectKey())) {
            throw new IllegalArgumentException("파일 메타데이터와 저장 요청의 객체 키가 일치하지 않습니다.");
        }
        if (!metadata.getContentType().equals(request.contentType())) {
            throw new IllegalArgumentException("파일 메타데이터와 저장 요청의 MIME 타입이 일치하지 않습니다.");
        }
        if (metadata.getSizeBytes() != request.sizeBytes()) {
            throw new IllegalArgumentException("파일 메타데이터와 저장 요청의 파일 크기가 일치하지 않습니다.");
        }
    }

    private void validateSaveResult(FileMetadata metadata, FileStorageSaveResult saveResult) {
        if (saveResult == null) {
            throw new IllegalStateException("파일 저장소가 저장 결과를 반환하지 않았습니다.");
        }
        if (!metadata.getObjectKey().equals(saveResult.objectKey())) {
            throw new IllegalArgumentException("파일 메타데이터와 저장 결과의 객체 키가 일치하지 않습니다.");
        }
        if (!metadata.getContentType().equals(saveResult.contentType())) {
            throw new IllegalArgumentException("파일 메타데이터와 저장 결과의 MIME 타입이 일치하지 않습니다.");
        }
        if (metadata.getSizeBytes() != saveResult.sizeBytes()) {
            throw new IllegalArgumentException("파일 메타데이터와 저장 결과의 파일 크기가 일치하지 않습니다.");
        }
        if (!metadata.getChecksum().equals(saveResult.checksum())) {
            throw new IllegalArgumentException("파일 메타데이터와 저장 결과의 체크섬이 일치하지 않습니다.");
        }
    }
}
