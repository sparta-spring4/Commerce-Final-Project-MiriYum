package com.miriyum.global.storage.service;

/** 같은 파일 메타데이터 생성 또는 상태 전이가 이미 처리된 경우 발생한다. */
public class FileMetadataConflictException extends RuntimeException {

    public FileMetadataConflictException(String message) {
        super(message);
    }

    public FileMetadataConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
