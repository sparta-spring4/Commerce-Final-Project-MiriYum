package com.miriyum.global.storage;

import java.util.Arrays;
import java.util.Objects;

/**
 * 파일 저장소에서 읽은 파일 정보와 내용을 표현한다.
 *
 * @param objectKey 저장소에서 파일을 식별하는 객체 키
 * @param contentType 파일의 MIME 타입
 * @param bytes 파일 내용
 */
public record FileStorageObject(
        String objectKey,
        String contentType,
        byte[] bytes
) {

    public FileStorageObject {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("object key is required");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("content type is required");
        }
        Objects.requireNonNull(bytes, "file bytes are required");
        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
