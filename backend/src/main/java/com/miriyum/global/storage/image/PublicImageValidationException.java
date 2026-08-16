package com.miriyum.global.storage.image;

/** 공개 이미지 검증 실패 사유를 HTTP 오류 계약으로 변환하기 위한 예외다. */
public class PublicImageValidationException extends IllegalArgumentException {

    public enum Reason {
        EMPTY_CONTENT,
        SIZE_EXCEEDED,
        UNSUPPORTED_MEDIA_TYPE
    }

    private final Reason reason;

    public PublicImageValidationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
