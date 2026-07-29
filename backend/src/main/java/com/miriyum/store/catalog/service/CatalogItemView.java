package com.miriyum.store.catalog.service;

/**
 * 조회 결과의 catalog 항목 표현이다.
 *
 * @param code 불투명 코드
 * @param displayName 한글 표시명
 */
public record CatalogItemView(String code, String displayName) {
}
