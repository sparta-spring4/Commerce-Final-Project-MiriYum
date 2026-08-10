package com.miriyum.global.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * 파일 원문을 제외하고 DB에 보관할 파일 메타데이터다.
 *
 * <p>실제 파일 바이트는 파일 저장소에만 두고, 이 객체에는 소유자·목적·경로·검증 정보와
 * 처리 상태만 둔다.</p>
 */
public record FileStorageMetadata(
        UUID fileId,
        FileStorageOwner owner,
        FileStoragePurpose purpose,
        String objectKey,
        String contentType,
        long sizeBytes,
        String checksum,
        FileStorageVisibility visibility,
        FileStorageStatus status,
        String retentionPolicy,
        Instant createdAt,
        Instant deletedAt
) {

    public FileStorageMetadata {
        if (fileId == null) {
            throw new IllegalArgumentException("file id must not be null");
        }
        if (owner == null || purpose == null || visibility == null || status == null) {
            throw new IllegalArgumentException("file metadata enum values must not be null");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("object key must not be blank");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("content type must not be blank");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("file size must not be negative");
        }
        if (checksum == null || checksum.length() != 64) {
            throw new IllegalArgumentException("checksum must be 64 characters");
        }
        if (retentionPolicy == null || retentionPolicy.isBlank()) {
            throw new IllegalArgumentException("retention policy must not be blank");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("created at must not be null");
        }
        if (status == FileStorageStatus.DELETED && deletedAt == null) {
            throw new IllegalArgumentException("deleted at is required for deleted metadata");
        }
        if (status != FileStorageStatus.DELETED && deletedAt != null) {
            throw new IllegalArgumentException("deleted at is only allowed for deleted metadata");
        }
    }
}
