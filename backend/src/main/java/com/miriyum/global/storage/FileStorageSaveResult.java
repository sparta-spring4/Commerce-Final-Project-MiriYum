package com.miriyum.global.storage;

/**
 * 파일 저장소가 실제로 저장한 객체의 검증 정보다.
 *
 * @param objectKey 실제 저장된 객체 키
 * @param contentType 저장할 때 적용된 MIME 타입
 * @param sizeBytes 실제 저장한 바이트 크기
 * @param checksum 실제 저장한 바이트의 SHA-256 체크섬
 */
public record FileStorageSaveResult(
        String objectKey,
        String contentType,
        long sizeBytes,
        String checksum
) {

    public FileStorageSaveResult {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("object key is required");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("content type is required");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("file size cannot be negative");
        }
        if (checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checksum must be a lowercase SHA-256 hex value");
        }
    }
}
