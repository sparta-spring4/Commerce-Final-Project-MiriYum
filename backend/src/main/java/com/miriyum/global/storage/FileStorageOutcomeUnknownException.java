package com.miriyum.global.storage;

/**
 * 객체 저장 요청은 전송됐지만, 후속 검증 또는 보상 삭제가 실패해 실제 객체 존재 여부를 확정할 수 없을 때 사용한다.
 *
 * <p>이 경우 메타데이터를 FAILED로 바꾸면 고아 객체를 재처리할 근거가 사라지므로 PENDING 상태를 유지한다.</p>
 */
public class FileStorageOutcomeUnknownException extends RuntimeException {

    public FileStorageOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
