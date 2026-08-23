package com.miriyum.domain.store.enums;

/**
 * 구 task와 rollback 호환을 위해 contract 단계까지 유지하는 저장 전용 값이다.
 * 신규 기능의 업종·검색·픽업 판정에는 사용하지 않는다.
 */
public enum BusinessType {
    CAFE,
    BAKERY,
    OTHER
}
