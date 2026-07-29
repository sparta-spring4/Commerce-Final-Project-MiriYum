package com.miriyum.global.idempotency;

/**
 * 업무 콜백이 반환하는 최초 성공 결과의 의미 필드다.
 *
 * @param httpStatus 성공 HTTP 상태
 * @param responseCode 성공 응답 code(예: {@code SUCCESS})
 * @param resourceType 결과 리소스 유형(없으면 {@code null})
 * @param resourceId 결과 리소스 ID(없으면 {@code null})
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
}
