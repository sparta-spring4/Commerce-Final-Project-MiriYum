package com.miriyum.domain.platformoperator.enums;

/** 현재 비밀번호 일회 승인이 결속될 수 있는 대상 유형이다. */
public enum AdminTargetType {
    ONBOARDING_APPLICATION,
    CONSUMER_ACCOUNT,
    STORE_OPERATOR_ACCOUNT,
    STORE,
    PAYMENT_RECOVERY_CASE,
    PLATFORM_OPERATOR_ACCOUNT,
    AUDIT_EVENT,
    INCIDENT
}
