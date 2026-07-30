package com.miriyum.domain.store.service;

/**
 * catalog 종류이다. 공개 조회 엔드포인트, 종류별 테이블, 후속 도메인의 검증 호출과 1:1로 대응한다.
 */
public enum CatalogKind {

    /** 매장 주 카테고리(`store_category`). */
    STORE_CATEGORY,

    /** 메뉴 주·보조 카테고리(`menu_category`). */
    MENU_CATEGORY,

    /** 매장 검색 보조 태그(`store_tag`). */
    STORE_TAG
}
