package com.miriyum.global.storage.entity;

import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.FileStorageMetadata;
import com.miriyum.global.storage.FileStorageOwner;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** MySQL의 파일 메타데이터 행을 표현하는 영속성 모델이다. */
@Entity
@Table(name = "file_metadata")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FileMetadata {

    @Id
    @Column(name = "file_id", nullable = false, length = 36)
    private String fileId;

    @Column(name = "owner_type", nullable = false, length = 32)
    private String ownerType;

    @Column(name = "owner_id", nullable = false)
    private long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private FileStoragePurpose purpose;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private FileStorageVisibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_status", nullable = false, length = 20)
    private FileStorageStatus storageStatus;

    @Column(name = "retention_policy", nullable = false, length = 64)
    private String retentionPolicy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public static FileMetadata createPending(
            String fileId,
            String ownerType,
            long ownerId,
            FileStoragePurpose purpose,
            String objectKey,
            String contentType,
            long sizeBytes,
            String checksum,
            FileStorageVisibility visibility,
            String retentionPolicy,
            Instant createdAt) {
        validateRequiredFields(fileId, ownerType, ownerId, purpose, objectKey, contentType, sizeBytes,
                checksum, visibility, retentionPolicy, createdAt);
        FileMetadata metadata = new FileMetadata();
        metadata.fileId = fileId;
        metadata.ownerType = ownerType;
        metadata.ownerId = ownerId;
        metadata.purpose = purpose;
        metadata.objectKey = objectKey;
        metadata.contentType = contentType;
        metadata.sizeBytes = sizeBytes;
        metadata.checksum = checksum;
        metadata.visibility = visibility;
        metadata.storageStatus = FileStorageStatus.PENDING;
        metadata.retentionPolicy = retentionPolicy;
        metadata.createdAt = createdAt;
        return metadata;
    }

    /** 공개 메타데이터 계약을 공통 저장소 내부의 영속 모델로 바꾼다. */
    public static FileMetadata createPending(FileStorageMetadata metadata) {
        return createPending(
                metadata.fileId().toString(),
                metadata.owner().type(),
                metadata.owner().id(),
                metadata.purpose(),
                metadata.objectKey(),
                metadata.contentType(),
                metadata.sizeBytes(),
                metadata.checksum(),
                metadata.visibility(),
                metadata.retentionPolicy(),
                metadata.createdAt());
    }

    /** 영속 모델을 다른 도메인이 사용할 수 있는 공개 메타데이터 계약으로 바꾼다. */
    public FileStorageMetadata toPublicMetadata() {
        return new FileStorageMetadata(
                UUID.fromString(fileId),
                new FileStorageOwner(ownerType, ownerId),
                purpose,
                objectKey,
                contentType,
                sizeBytes,
                checksum,
                visibility,
                storageStatus,
                retentionPolicy,
                createdAt,
                deletedAt);
    }

    private static void validateRequiredFields(
            String fileId,
            String ownerType,
            long ownerId,
            FileStoragePurpose purpose,
            String objectKey,
            String contentType,
            long sizeBytes,
            String checksum,
            FileStorageVisibility visibility,
            String retentionPolicy,
            Instant createdAt) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("file id must not be blank");
        }
        UUID.fromString(fileId);
        new FileStorageOwner(ownerType, ownerId);
        if (objectKey == null || objectKey.isBlank() || objectKey.length() > 512) {
            throw new IllegalArgumentException("object key must not be blank or exceed 512 characters");
        }
        if (contentType == null || contentType.isBlank() || contentType.length() > 128) {
            throw new IllegalArgumentException("content type must not be blank or exceed 128 characters");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("file size must not be negative");
        }
        if (retentionPolicy == null || retentionPolicy.isBlank() || retentionPolicy.length() > 64) {
            throw new IllegalArgumentException("retention policy must not be blank or exceed 64 characters");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("created at must not be null");
        }
        validateChecksum(checksum);
        validateVisibility(purpose, visibility);
    }

    private static void validateChecksum(String checksum) {
        if (checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("체크섬은 소문자 64자리 SHA-256 값이어야 합니다.");
        }
    }

    private static void validateVisibility(
            FileStoragePurpose purpose, FileStorageVisibility visibility) {
        if (purpose == null || visibility == null) {
            throw new IllegalArgumentException("파일 목적과 공개 범위는 필수입니다.");
        }
        purpose.validateVisibility(visibility);
    }

    /** 파일 저장소에 원본 파일이 정상 저장된 뒤 완료 상태로 전환한다. */
    public void confirm() {
        changeStatus(FileStorageStatus.CONFIRMED);
    }

    /** 파일 저장이 실패했을 때 후속 정리 대상임을 남긴다. */
    public void fail() {
        changeStatus(FileStorageStatus.FAILED);
    }

    private void changeStatus(FileStorageStatus nextStatus) {
        if (storageStatus != FileStorageStatus.PENDING) {
            throw new IllegalStateException("대기 상태의 파일 메타데이터만 처리 결과를 기록할 수 있습니다.");
        }
        storageStatus = nextStatus;
    }
}
