package com.miriyum.store.catalog.domain;

/**
 * catalog 종류이다.
 *
 * <p>각 상수는 공개 조회 엔드포인트와 1:1로 대응하며, code 유일성은 이 종류 안에서만 요구한다.</p>
 */
public enum CatalogKind {

    /** 매장 주 카테고리. */
    STORE_CATEGORY,

    /** 메뉴 주·보조 카테고리. */
    MENU_CATEGORY,

    /** 매장 검색 보조 태그. */
    STORE_TAG
}
