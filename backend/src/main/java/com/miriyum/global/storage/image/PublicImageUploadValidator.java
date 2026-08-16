package com.miriyum.global.storage.image;

import java.util.Map;

/** 업로드 헤더와 실제 파일 시그니처가 모두 허용된 공개 이미지인지 검증한다. */
public final class PublicImageUploadValidator {

    private static final Map<String, ImageFormat> FORMATS = Map.of(
            "image/jpeg", new ImageFormat("jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
            "image/png", new ImageFormat("png", new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}),
            "image/webp", new ImageFormat("webp", new byte[] {0x52, 0x49, 0x46, 0x46})
    );

    private final long maximumSizeBytes;

    public PublicImageUploadValidator(long maximumSizeBytes) {
        if (maximumSizeBytes <= 0) {
            throw new IllegalArgumentException("이미지 최대 크기는 양수여야 합니다.");
        }
        this.maximumSizeBytes = maximumSizeBytes;
    }

    public ValidatedPublicImage validate(String declaredContentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new PublicImageValidationException(
                    PublicImageValidationException.Reason.EMPTY_CONTENT,
                    "빈 이미지는 업로드할 수 없습니다.");
        }
        if (bytes.length > maximumSizeBytes) {
            throw new PublicImageValidationException(
                    PublicImageValidationException.Reason.SIZE_EXCEEDED,
                    "이미지 파일 크기가 제한을 초과했습니다.");
        }
        ImageFormat declaredFormat = FORMATS.get(declaredContentType);
        if (declaredFormat == null || !declaredFormat.matches(bytes)) {
            throw new PublicImageValidationException(
                    PublicImageValidationException.Reason.UNSUPPORTED_MEDIA_TYPE,
                    "JPEG, PNG, WebP 이미지만 업로드할 수 있습니다.");
        }
        if ("image/webp".equals(declaredContentType) && !isWebp(bytes)) {
            throw new PublicImageValidationException(
                    PublicImageValidationException.Reason.UNSUPPORTED_MEDIA_TYPE,
                    "JPEG, PNG, WebP 이미지만 업로드할 수 있습니다.");
        }
        return new ValidatedPublicImage(declaredContentType, declaredFormat.extension(), bytes);
    }

    private boolean isWebp(byte[] bytes) {
        return bytes.length >= 12
                && bytes[8] == 0x57
                && bytes[9] == 0x45
                && bytes[10] == 0x42
                && bytes[11] == 0x50;
    }

    private record ImageFormat(String extension, byte[] signature) {
        private boolean matches(byte[] content) {
            if (content.length < signature.length) {
                return false;
            }
            for (int index = 0; index < signature.length; index++) {
                if (content[index] != signature[index]) {
                    return false;
                }
            }
            return true;
        }
    }
}
