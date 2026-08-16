package com.miriyum.global.storage.image;

import java.util.Arrays;

/** MIME 타입과 파일 시그니처를 함께 검증한 공개 이미지 원문이다. */
public record ValidatedPublicImage(String contentType, String extension, byte[] bytes) {

    public ValidatedPublicImage {
        if (contentType == null || extension == null || bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("검증된 공개 이미지 값은 비어 있을 수 없습니다.");
        }
        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
