package com.miriyum.domain.store.service;

/**
 * 메뉴 신규 명령이 잠긴 Store 행에서 확인한 운영 권한이다.
 *
 * @param storeId 잠긴 매장 식별자
 */
public record StoreMenuAuthority(long storeId) {
}
