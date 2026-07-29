package com.miriyum.store.catalog.web.dto;

import com.miriyum.store.catalog.service.CatalogItemView;

/**
 * 공개 catalog 항목 응답이다.
 *
 * @param code 불투명 코드
 * @param displayName 한글 표시명
 */
public record CatalogItemResponse(String code, String displayName) {

    public static CatalogItemResponse from(CatalogItemView view) {
        return new CatalogItemResponse(view.code(), view.displayName());
    }
}
