package com.miriyum.global.idempotency;

/**
 * 업무 콜백이 반환하는 최초 성공 결과의 의미 필드다.
 *
 * @param httpStatus 성공 HTTP 상태
 * @param responseCode 성공 응답 code({@code SUCCESS})
 * @param resourceType 결과 리소스 유형(없으면 {@code null}, 최대 40자)
 * @param resourceId 결과 리소스 ID(없으면 {@code null}, 최대 64자)
 * @param data 응답 {@code data}로 직렬화할 결과 객체(없으면 {@code null})
 * @param <T> 결과 데이터 타입
 */
public record BusinessResult<T>(
        int httpStatus,
        String responseCode,
        String resourceType,
        String resourceId,
        T data
) {
    private static final String SUCCESS_CODE = "SUCCESS";
    private static final int MAX_RESOURCE_TYPE_LENGTH = 40;
    private static final int MAX_RESOURCE_ID_LENGTH = 64;

    /**
     * 저장·재생 가능한 성공 결과만 생성한다.
     *
     * @throws IllegalArgumentException 성공 상태·응답 코드 또는 리소스 식별자가 계약을 벗어난 경우
     */
    public BusinessResult {
        if (httpStatus < 200 || httpStatus >= 300) {
            throw new IllegalArgumentException("httpStatus must be a successful HTTP status");
        }
        if (!SUCCESS_CODE.equals(responseCode)) {
            throw new IllegalArgumentException("responseCode must be SUCCESS");
        }
        validateOptionalIdentifier(resourceType, MAX_RESOURCE_TYPE_LENGTH, "resourceType");
        validateOptionalIdentifier(resourceId, MAX_RESOURCE_ID_LENGTH, "resourceId");
    }

    private static void validateOptionalIdentifier(String value, int maxLength, String fieldName) {
        if (value == null) {
            return;
        }
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    fieldName + " must be non-blank and at most " + maxLength + " characters");
        }
    }
}
