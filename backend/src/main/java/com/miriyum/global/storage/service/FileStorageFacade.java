package com.miriyum.global.storage.service;

import com.miriyum.global.storage.FileStoragePort;
import com.miriyum.global.storage.FileStorageRequest;
import com.miriyum.global.storage.entity.FileMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** 파일 저장 결과와 메타데이터 상태 전이를 순서대로 조정한다. */
@Service
@ConditionalOnBean(FileStoragePort.class)
@RequiredArgsConstructor
public class FileStorageFacade {

    private final FileStoragePort fileStoragePort;
    private final FileMetadataTransactionExecutor transactionExecutor;

    /** 대기 상태를 확정한 뒤 파일을 저장하고, 성공한 경우에만 완료 상태로 전환한다. */
    public FileMetadata store(FileMetadata metadata, FileStorageRequest request) {
        validateObjectKey(metadata, request);
        transactionExecutor.savePending(metadata);
        try {
            fileStoragePort.save(request);
        } catch (RuntimeException exception) {
            markFailedWithoutHidingStorageFailure(metadata.getFileId(), exception);
            throw exception;
        }
        return transactionExecutor.confirm(metadata.getFileId());
    }

    private void markFailedWithoutHidingStorageFailure(String fileId, RuntimeException storageFailure) {
        try {
            transactionExecutor.fail(fileId);
        } catch (RuntimeException metadataFailure) {
            storageFailure.addSuppressed(metadataFailure);
        }
    }

    private void validateObjectKey(FileMetadata metadata, FileStorageRequest request) {
        if (!metadata.getObjectKey().equals(request.objectKey())) {
            throw new IllegalArgumentException("파일 메타데이터와 저장 요청의 객체 키가 일치하지 않습니다.");
        }
    }
}
