package com.miriyum.global.response;

/**
 * 페이지 목록 응답의 공통 메타데이터이다.
 *
 * @param number 0부터 시작하는 현재 페이지 번호
 * @param size 요청에 적용된 페이지 크기
 * @param totalElements 조건에 맞는 전체 결과 수
 * @param totalPages 전체 페이지 수
 * @param hasNext 다음 페이지 존재 여부
 */
public record PageMetadata(
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
}
