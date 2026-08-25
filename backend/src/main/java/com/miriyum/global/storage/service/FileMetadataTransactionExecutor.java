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
import java.util.UUID;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
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
    private final List<FileMetadataDeletionGuard> deletionGuards;

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
        // PENDING 메타데이터는 외부 저장 전용 REQUIRES_NEW 트랜잭션에서 커밋된다.
        // MySQL REPEATABLE_READ의 기존 읽기 스냅샷에서도 최신 행을 확인하도록 상태 전이는 잠금 조회를 사용한다.
        FileMetadata metadata = fileMetadataRepository.findByFileIdForUpdate(fileId)
                .orElseThrow(() -> new IllegalStateException("파일 메타데이터를 찾을 수 없습니다."));
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
        requireDeletionAllowed(metadata);
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
        requireDeletionAllowed(metadata);
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

    /** S3 삭제 성공을 DB에 남긴다. 재시도 중에도 같은 완료 상태로 수렴한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileMetadata completeObjectCleanup(String fileId, Instant completedAt) {
        FileMetadata metadata = fileMetadataRepository.findByFileIdForUpdate(fileId)
                .orElseThrow(() -> new IllegalStateException("파일 메타데이터를 찾을 수 없습니다."));
        metadata.completeObjectCleanup(completedAt);
        return saveTerminalState(metadata);
    }

    /** 논리 삭제됐지만 객체 삭제 완료 기록이 없는 후보만 제한된 수로 조회한다. */
    @Transactional(readOnly = true)
    public List<FileMetadata> findObjectCleanupCandidates(Instant now, int limit) {
        return fileMetadataRepository.findAllByStorageStatusAndObjectCleanupCompletedAtIsNullAndObjectCleanupNextAttemptAtLessThanEqualOrderByDeletedAtAsc(
                FileStorageStatus.DELETED, now, PageRequest.of(0, limit));
    }

    /** 후보를 다시 잠가 lease를 얻은 worker만 외부 S3 삭제를 수행한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<FileMetadata> claimObjectCleanup(String fileId, Instant now, Instant claimedUntil) {
        FileMetadata metadata = fileMetadataRepository.findByFileIdForUpdate(fileId)
                .orElseThrow(() -> new IllegalStateException("파일 메타데이터를 찾을 수 없습니다."));
        if (!metadata.claimObjectCleanup(now, claimedUntil, UUID.randomUUID().toString())) {
            return Optional.empty();
        }
        return Optional.of(saveTerminalState(metadata));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeClaimedObjectCleanup(String fileId, String claimToken, Instant completedAt) {
        FileMetadata metadata = fileMetadataRepository.findByFileIdForUpdate(fileId)
                .orElseThrow(() -> new IllegalStateException("파일 메타데이터를 찾을 수 없습니다."));
        if (!metadata.completeClaimedObjectCleanup(claimToken, completedAt)) {
            return false;
        }
        saveTerminalState(metadata);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean rescheduleClaimedObjectCleanup(
            String fileId, String claimToken, Instant now, long retryBaseSeconds) {
        FileMetadata metadata = fileMetadataRepository.findByFileIdForUpdate(fileId)
                .orElseThrow(() -> new IllegalStateException("파일 메타데이터를 찾을 수 없습니다."));
        if (!metadata.rescheduleClaimedObjectCleanup(claimToken, now, retryBaseSeconds)) {
            return false;
        }
        saveTerminalState(metadata);
        return true;
    }

    /** 오래 남은 PENDING 후보를 읽어, 실제 전이는 외부 호출 전 짧은 트랜잭션에서 다시 확인한다. */
    @Transactional(readOnly = true)
    public List<FileMetadata> findStalePendingCandidates(Instant createdBefore, int limit) {
        return fileMetadataRepository.findAllByStorageStatusAndCreatedAtLessThanEqualOrderByCreatedAtAsc(
                FileStorageStatus.PENDING, createdBefore, PageRequest.of(0, limit));
    }

    /** 후보 조회와 처리 사이에 완료된 행은 건너뛰어 S3 객체를 잘못 삭제하지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<FileMetadata> discardStalePendingForReconciliation(String fileId, Instant deletedAt) {
        FileMetadata metadata = fileMetadataRepository.findByFileIdForUpdate(fileId)
                .orElseThrow(() -> new IllegalStateException("파일 메타데이터를 찾을 수 없습니다."));
        if (metadata.getStorageStatus() == FileStorageStatus.PENDING) {
            metadata.discardPending(deletedAt);
            return Optional.of(saveTerminalState(metadata));
        }
        if (metadata.getStorageStatus() == FileStorageStatus.DELETED) {
            return Optional.of(metadata);
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public long countLongStayCandidates(Instant pendingCreatedBefore, Instant deletedBefore) {
        return fileMetadataRepository.countByStorageStatusAndCreatedAtLessThanEqual(
                        FileStorageStatus.PENDING, pendingCreatedBefore)
                + fileMetadataRepository.countByStorageStatusAndObjectCleanupCompletedAtIsNullAndDeletedAtLessThanEqual(
                        FileStorageStatus.DELETED, deletedBefore);
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

    private void requireDeletionAllowed(FileMetadata metadata) {
        if (deletionGuards.stream().anyMatch(guard -> guard.blocksDeletion(metadata.getFileId()))) {
            throw new FileMetadataConflictException("현재 보존 중인 증빙이 참조하는 파일은 삭제할 수 없습니다.");
        }
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
