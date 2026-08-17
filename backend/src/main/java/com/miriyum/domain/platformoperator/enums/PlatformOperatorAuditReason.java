package com.miriyum.domain.platformoperator.enums;

/** 자유문 대신 저장하는 구조화된 감사 사유다. */
public enum PlatformOperatorAuditReason {
    AUTHENTICATION_EVENT,
    ACCOUNT_PROVISIONING,
    RESPONSIBILITY_CHANGE,
    EMPLOYMENT_END,
    SECURITY_RESPONSE,
    AUDIT_VERIFICATION,
    RECORD_CORRECTION,
    STORE_ENFORCEMENT
}
