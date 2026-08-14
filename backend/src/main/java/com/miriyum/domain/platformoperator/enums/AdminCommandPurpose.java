package com.miriyum.domain.platformoperator.enums;

/** 현재 비밀번호 일회 승인이 결속될 수 있는 고위험 명령 목적이다. */
public enum AdminCommandPurpose {
    ONBOARDING_DECISION,
    MEMBER_RECOVERY,
    ACCOUNT_SANCTION,
    STORE_SANCTION,
    PAYMENT_RECOVERY,
    OPERATOR_AUTHORITY_CHANGE,
    OPERATOR_SUSPENSION,
    INCIDENT_RESPONSE
}
