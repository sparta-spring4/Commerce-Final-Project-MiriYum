package com.miriyum.global.idempotency;

/**
 * 멱등 실행 대상 명령의 식별 정보다.
 *
 * @param principalNamespace 인증 주체 namespace(예: {@code CONSUMER}, {@code STORE_OPERATOR})
 * @param principalId 인증 주체(행위자) 계정 PK
 * @param commandType 명령 유형 상수(예: {@code STORE_REGISTER})
 * @param idempotencyKey 검증·소문자 정규화된 UUID 키
 * @param requestFingerprint 정규 입력의 SHA-256 hex
 */
public record IdempotencyCommand(
        String principalNamespace,
        long principalId,
        String commandType,
        String idempotencyKey,
        String requestFingerprint
) {
}
