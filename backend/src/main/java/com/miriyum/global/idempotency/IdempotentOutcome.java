package com.miriyum.global.idempotency;

import tools.jackson.databind.JsonNode;

/**
 * 멱등 실행 결과다. 신규 실행(fresh)과 재생(replay) 모두 같은 형태를 노출한다.
 *
 * <p>컨트롤러는 이 값으로 공통 응답 봉투를 만든다. 최초 message는 저장하지 않으므로 재생 시 표준
 * 안내 문구를 사용한다.</p>
 *
 * @param replayed 저장된 결과 재생 여부(신규 실행이면 {@code false})
 * @param httpStatus 최초 성공 HTTP 상태
 * @param responseCode 최초 성공 응답 code
 * @param resourceType 결과 리소스 유형
 * @param resourceId 결과 리소스 ID
 * @param data 공통 응답 {@code data}에 넣을 구조화된 JSON 값
 */
public record IdempotentOutcome(
        boolean replayed,
        int httpStatus,
        String responseCode,
        String resourceType,
        String resourceId,
        JsonNode data
) {
}
