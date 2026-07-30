package com.miriyum.domain.consumer.enums;

/**
 * 일반 사용자 계정 상태이다. 1차 MVP는 ACTIVE, SUSPENDED만 사용한다.
 */
public enum ConsumerAccountStatus {

    /** 정상적으로 로그인하고 서비스를 이용할 수 있는 상태다. */
    ACTIVE,

    /** 제재 등의 사유로 로그인과 서비스 이용이 제한된 상태다. */
    SUSPENDED
}
