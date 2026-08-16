package com.miriyum.global.storage.service;

import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.repository.FileMetadataRepository;
import com.miriyum.global.storage.FileStorageMetadata;
import com.miriyum.global.storage.FileStorageOwner;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 외부 파일 저장과 분리해 파일 메타데이터 상태를 독립 트랜잭션으로 기록한다. */
@Service
@RequiredArgsConstructor
public class FileMetadataTransactionExecutor {

    private final FileMetadataRepository fileMetadataRepository;

    /** 파일 저장을 시도하기 전에 대기 상태를 별도로 확정한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileMetadata savePending(FileMetadata metadata) {
        if (fileMetadataRepository.existsById(metadata.getFileId())) {
            throw new FileMetadataConflictException("이미 존재하는 파일 메타데이터 식별자입니다.");
        }
        try {
            return fileMetadataRepository.saveAndFlush(metadata);
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateKey(exception)) {
                throw new FileMetadataConflictException("파일 메타데이터 식별자 또는 객체 키가 이미 존재합니다.", exception);
            }
            throw exception;
        }
    }

    /** 파일 저장 성공 후 대기 상태의 메타데이터를 완료 상태로 확정한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileMetadata confirm(String fileId) {
        FileMetadata metadata = findMetadata(fileId);
        metadata.confirm();
        return saveTerminalState(metadata);
    }

    /** 바깥 업무·멱등 결과 트랜잭션과 함께 파일 공개 상태를 확정한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public FileMetadata confirmWithinCurrentTransaction(String fileId) {
        FileMetadata metadata = findMetadata(fileId);
        metadata.confirm();
        return saveTerminalState(metadata);
    }

    /** 바깥 업무 트랜잭션과 함께 공개 파일을 조회 대상에서 제외한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public FileMetadata markDeletedWithinCurrentTransaction(String fileId, Instant deletedAt) {
        FileMetadata metadata = fileMetadataRepository.findByFileIdForUpdate(fileId)
                .orElseThrow(() -> new IllegalStateException("파일 메타데이터를 찾을 수 없습니다."));
        if (metadata.getStorageStatus() == FileStorageStatus.DELETED) {
            return metadata;
        }
        metadata.delete(deletedAt);
        return saveTerminalState(metadata);
    }

    /** 다른 도메인은 공개 계약만 받고 저장소 영속 모델에는 접근하지 않는다. */
    @Transactional(readOnly = true)
    public List<FileStorageMetadata> findPublicMetadata(
            FileStorageOwner owner,
            FileStoragePurpose purpose,
            Collection<FileStorageStatus> statuses
    ) {
        return fileMetadataRepository
                .findAllByOwnerTypeAndOwnerIdAndPurposeAndVisibilityAndStorageStatusInOrderByCreatedAtAsc(
                        owner.type(), owner.id(), purpose, FileStorageVisibility.PUBLIC, statuses)
                .stream()
                .map(FileMetadata::toPublicMetadata)
                .toList();
    }

    /** 여러 공개 소유자의 현재 대표 URL을 한 번에 조회한다. */
    @Transactional(readOnly = true)
    public Map<FileStorageOwner, String> findConfirmedPublicUrls(
            Collection<FileStorageOwner> owners,
            FileStoragePurpose purpose
    ) {
        if (owners == null || owners.isEmpty() || purpose == null) {
            throw new IllegalArgumentException("파일 소유자와 목적은 필수입니다.");
        }
        String ownerType = owners.iterator().next().type();
        if (owners.stream().anyMatch(owner -> !ownerType.equals(owner.type()))) {
            throw new IllegalArgumentException("한 번의 공개 URL 조회에는 같은 소유자 종류만 사용할 수 있습니다.");
        }
        Map<FileStorageOwner, String> urls = new LinkedHashMap<>();
        fileMetadataRepository
                .findAllByOwnerTypeAndOwnerIdInAndPurposeAndVisibilityAndStorageStatusOrderByCreatedAtAsc(
                        ownerType,
                        owners.stream().map(FileStorageOwner::id).distinct().toList(),
                        purpose,
                        FileStorageVisibility.PUBLIC,
                        FileStorageStatus.CONFIRMED)
                .forEach(metadata -> urls.putIfAbsent(
                        new FileStorageOwner(metadata.getOwnerType(), metadata.getOwnerId()),
                        "/api/v1/public-files/" + metadata.getFileId()));
        return Map.copyOf(urls);
    }

    /** 파일 저장 실패 후 대기 상태의 메타데이터를 실패 상태로 확정한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileMetadata fail(String fileId) {
        FileMetadata metadata = findMetadata(fileId);
        metadata.fail();
        return saveTerminalState(metadata);
    }

    /**
     * 파일을 외부 조회 대상에서 제외하고, 이미 삭제된 파일이면 같은 정본을 반환해 외부 객체 삭제를 재시도한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileMetadata deleteOrGetDeleted(String fileId, Instant deletedAt) {
        FileMetadata metadata = fileMetadataRepository.findByFileIdForUpdate(fileId)
                .orElseThrow(() -> new IllegalStateException("파일 메타데이터를 찾을 수 없습니다."));
        if (metadata.getStorageStatus() == FileStorageStatus.DELETED) {
            return metadata;
        }
        metadata.delete(deletedAt);
        return saveTerminalState(metadata);
    }

    /** 롤백된 바깥 업무가 남긴 대기 파일을 공개 전에 삭제 상태로 보상한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileMetadata discardPendingOrGetDeleted(String fileId, Instant deletedAt) {
        FileMetadata metadata = fileMetadataRepository.findByFileIdForUpdate(fileId)
                .orElseThrow(() -> new IllegalStateException("파일 메타데이터를 찾을 수 없습니다."));
        if (metadata.getStorageStatus() == FileStorageStatus.DELETED) {
            return metadata;
        }
        metadata.discardPending(deletedAt);
        return saveTerminalState(metadata);
    }

    private FileMetadata saveTerminalState(FileMetadata metadata) {
        try {
            return fileMetadataRepository.saveAndFlush(metadata);
        } catch (OptimisticLockingFailureException exception) {
            throw new FileMetadataConflictException("파일 메타데이터 상태가 이미 변경되었습니다.", exception);
        }
    }

    private FileMetadata findMetadata(String fileId) {
        return fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new IllegalStateException("파일 메타데이터를 찾을 수 없습니다."));
    }

    private boolean isDuplicateKey(DataIntegrityViolationException exception) {
        if (exception instanceof DuplicateKeyException) {
            return true;
        }

        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLIntegrityConstraintViolationException sqlException
                    && sqlException.getErrorCode() == 1062) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
