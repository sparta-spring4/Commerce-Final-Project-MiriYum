package com.miriyum.global.response;

/**
 * 일반 JSON 성공 응답의 공통 봉투이다.
 *
 * @param code 성공 여부를 나타내는 고정 외부 코드
 * @param message 사용자에게 표시할 수 있는 안내 문구
 * @param data 기능별 응답 데이터 또는 {@code null}
 * @param <T> 기능별 응답 데이터 타입
 */
public record ApiResponse<T>(
        String code,
        String message,
        T data
) {

    private static final String SUCCESS_CODE = "SUCCESS";

    /**
     * 기능별 결과를 공통 성공 응답으로 감싼다.
     *
     * @param message 사용자에게 표시할 수 있는 안내 문구
     * @param data 기능별 응답 데이터 또는 {@code null}
     * @param <T> 기능별 응답 데이터 타입
     * @return 성공 코드와 전달받은 결과를 포함한 응답
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(SUCCESS_CODE, message, data);
    }
}
