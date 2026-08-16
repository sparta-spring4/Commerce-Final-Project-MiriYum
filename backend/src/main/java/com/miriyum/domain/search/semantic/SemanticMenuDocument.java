package com.miriyum.domain.search.semantic;

/** 인덱스에 저장할 메뉴 검색 문서와 재검증 식별자다. */
public record SemanticMenuDocument(
        long menuId,
        long storeId,
        int versionNumber,
        String text
) {
}
