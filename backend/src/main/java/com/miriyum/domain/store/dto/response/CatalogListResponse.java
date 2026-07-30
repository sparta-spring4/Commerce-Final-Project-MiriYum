package com.miriyum.domain.store.dto.response;

import java.util.List;

/**
 * catalog 목록 응답의 {@code data} 본문이다.
 *
 * @param items 활성 항목을 정렬 순서대로 담은 목록
 */
public record CatalogListResponse(List<CatalogItemResponse> items) {
}
