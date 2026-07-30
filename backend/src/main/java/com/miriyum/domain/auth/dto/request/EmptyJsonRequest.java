package com.miriyum.domain.auth.dto.request;

/**
 * 재발급 요청의 필수 빈 JSON 본문이다. {@code docs/specs/auth-account/openapi.yaml}의
 * {@code EmptyJsonRequest}({@code additionalProperties: false}인 빈 객체)와 대응한다.
 * 전역 unknown-property 거부 설정과 {@code @RequestBody}의 기본 필수 처리 덕분에,
 * 본문이 없거나 {@code {}}가 아니면 자동으로 400이 된다.
 */
public record EmptyJsonRequest() {
}
