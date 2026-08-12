package com.miriyum.global.storage;

import java.io.InputStream;
import java.util.Objects;

/**
 * 파일 저장에 필요한 입력값이다.
 *
 * @param objectKey 저장소에서 파일을 식별할 내부 객체 키
 * @param contentType 파일의 MIME 타입
 * @param sizeBytes 업로드 파일의 바이트 크기
 * @param content 저장할 파일 내용 스트림
 */
public record FileStorageRequest(
        String objectKey,
        String contentType,
        long sizeBytes,
        InputStream content
) {

    public FileStorageRequest {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("object key is required");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("content type is required");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("file size cannot be negative");
        }
        Objects.requireNonNull(content, "file content is required");
    }
}
