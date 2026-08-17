package com.miriyum.global.storage.service;

import com.miriyum.global.storage.FileStoragePort;
import com.miriyum.global.storage.FileStorageMetadata;
import com.miriyum.global.storage.FileStorageRequest;
import com.miriyum.global.storage.FileStorageSaveResult;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageOwner;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.entity.FileMetadata;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
        FileStorageMetadata pending = storePending(metadata, request);
        return transactionExecutor.confirm(pending.fileId().toString()).toPublicMetadata();
    }

    /** 외부 객체만 저장하고 공개 상태 확정은 호출 업무 트랜잭션에 맡긴다. */
    public FileStorageMetadata storePending(FileStorageMetadata metadata, FileStorageRequest request) {
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
        return persistedMetadata.toPublicMetadata();
    }

    /** 바깥 업무 트랜잭션이 성공할 때만 이미 저장된 파일을 공개한다. */
    public FileStorageMetadata confirmWithinCurrentTransaction(UUID fileId) {
        if (fileId == null) {
            throw new IllegalArgumentException("파일 식별자는 필수입니다.");
        }
        return transactionExecutor.confirmWithinCurrentTransaction(fileId.toString()).toPublicMetadata();
    }

    /** 바깥 업무 트랜잭션이 커밋될 때 함께 공개 목록에서 제외한다. */
    public FileStorageMetadata markDeletedWithinCurrentTransaction(UUID fileId, Instant deletedAt) {
        if (fileId == null || deletedAt == null) {
            throw new IllegalArgumentException("파일 식별자와 삭제 시각은 필수입니다.");
        }
        return transactionExecutor.markDeletedWithinCurrentTransaction(fileId.toString(), deletedAt)
                .toPublicMetadata();
    }

    /** 소유 도메인이 내부 Entity·Repository 없이 공개 파일 상태를 조회하는 계약이다. */
    public List<FileStorageMetadata> findPublicMetadata(
            FileStorageOwner owner,
            FileStoragePurpose purpose,
            Collection<FileStorageStatus> statuses
    ) {
        if (owner == null || purpose == null || statuses == null || statuses.isEmpty()) {
            throw new IllegalArgumentException("파일 소유자, 목적, 조회 상태는 필수입니다.");
        }
        return transactionExecutor.findPublicMetadata(owner, purpose, statuses);
    }

    /** 공개 목록 응답에 필요한 URL만 여러 소유자에 대해 한 번에 조회한다. */
    public Map<FileStorageOwner, String> findConfirmedPublicUrls(
            Collection<FileStorageOwner> owners,
            FileStoragePurpose purpose
    ) {
        return transactionExecutor.findConfirmedPublicUrls(owners, purpose);
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
                    "event=file_storage_object_delete_failed file_id={}",
                    deleted.fileId(),
                    exception);
            throw exception;
        }
        return deleted;
    }

    /** 바깥 업무 롤백으로 남은 대기 파일을 공개 전에 정리한다. */
    public FileStorageMetadata discardPending(UUID fileId, Instant deletedAt) {
        if (fileId == null || deletedAt == null) {
            throw new IllegalArgumentException("파일 식별자와 삭제 시각은 필수입니다.");
        }
        FileStorageMetadata deleted = transactionExecutor.discardPendingOrGetDeleted(fileId.toString(), deletedAt)
                .toPublicMetadata();
        try {
            fileStoragePort.delete(deleted.objectKey());
        } catch (RuntimeException exception) {
            log.warn(
                    "event=file_storage_pending_compensation_failed file_id={}",
                    deleted.fileId(),
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
