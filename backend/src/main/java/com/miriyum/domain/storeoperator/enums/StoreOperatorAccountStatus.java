package com.miriyum.domain.storeoperator.enums;

/**
 * 매장 운영자 계정 상태이다. 1차 MVP는 ACTIVE, SUSPENDED만 사용한다.
 */
public enum StoreOperatorAccountStatus {

    /** 정상적으로 로그인하고 서비스를 이용할 수 있는 상태다. */
    ACTIVE,

    /** 제재 등의 사유로 로그인과 서비스 이용이 제한된 상태다. */
    SUSPENDED
}
