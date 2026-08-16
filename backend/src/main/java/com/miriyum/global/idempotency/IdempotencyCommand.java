package com.miriyum.global.idempotency;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 멱등 실행 대상 명령의 식별 정보다.
 *
 * @param principalNamespace 인증 주체 namespace({@code consumer}, {@code store-operator},
 *                           {@code platform-operator})
 * @param principalId 인증 주체 계정 PK
 * @param commandType 명령 유형 상수(예: {@code STORE_REGISTER})
 * @param idempotencyKey 검증·소문자 정규화된 UUID 문자열
 * @param requestFingerprint 정규화 입력의 SHA-256 hex
 */
public record IdempotencyCommand(
        String principalNamespace,
        long principalId,
        String commandType,
        String idempotencyKey,
        String requestFingerprint
) {

    private static final Pattern NORMALIZED_UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern SHA_256_HEX_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern COMMAND_TYPE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,59}$");
    private static final Set<String> PRINCIPAL_NAMESPACES =
            Set.of("consumer", "store-operator", "platform-operator");

    /**
     * DB 경계에 전달할 명령 식별값을 검증한다.
     *
     * @throws IllegalArgumentException 필수값·길이·정규화·형식 계약을 벗어난 경우
     */
    public IdempotencyCommand {
        requireTextWithin(principalNamespace, 30, "principalNamespace");
        if (!PRINCIPAL_NAMESPACES.contains(principalNamespace)) {
            throw new IllegalArgumentException(
                    "principalNamespace는 consumer, store-operator, platform-operator 중 하나여야 합니다.");
        }
        if (principalId <= 0) {
            throw new IllegalArgumentException("principalId는 양수여야 합니다.");
        }
        requireTextWithin(commandType, 60, "commandType");
        if (!COMMAND_TYPE_PATTERN.matcher(commandType).matches()) {
            throw new IllegalArgumentException("commandType은 대문자 상수 형식이어야 합니다.");
        }
        if (idempotencyKey == null || !NORMALIZED_UUID_PATTERN.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("idempotencyKey는 소문자 표준 UUID여야 합니다.");
        }
        if (requestFingerprint == null || !SHA_256_HEX_PATTERN.matcher(requestFingerprint).matches()) {
            throw new IllegalArgumentException("requestFingerprint는 소문자 SHA-256 hex여야 합니다.");
        }
    }

    private static void requireTextWithin(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "는 필수이며 최대 " + maxLength + "자여야 합니다.");
        }
    }
}
