package com.miriyum.domain.platformoperator.enums;

import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.ACCOUNT_APPEAL_REVIEW;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.ACCOUNT_PERMANENT_SANCTION_APPROVE;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.ACCOUNT_SANCTION;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.AUDIT_READ;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.BREAK_GLASS_APPROVE;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.INCIDENT_RESPOND;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.MEMBER_READ_MINIMAL;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.MEMBER_RECOVERY;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.ONBOARDING_EVIDENCE_READ;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.ONBOARDING_REVIEW;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.OPERATIONS_MONITOR_READ;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.OPERATOR_AUTHORITY_MANAGE;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.OPERATOR_CREATE;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.OPERATOR_SUSPEND;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.PAYMENT_RECOVERY_HIGH_VALUE_APPROVE;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.STORE_READ_MINIMAL;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.STORE_SANCTION;

import java.util.Set;

/** 역할은 세부 권한을 편리하게 부여하는 고정 묶음이며 최종 인가는 세부 권한으로 판정한다. */
public enum PlatformOperatorRole {
    SUPER_ADMIN(Set.of(
            OPERATOR_CREATE,
            OPERATOR_AUTHORITY_MANAGE,
            OPERATOR_SUSPEND,
            ACCOUNT_PERMANENT_SANCTION_APPROVE,
            PAYMENT_RECOVERY_HIGH_VALUE_APPROVE,
            BREAK_GLASS_APPROVE)),
    ONBOARDING_REVIEWER(Set.of(ONBOARDING_REVIEW, ONBOARDING_EVIDENCE_READ)),
    MEMBER_SUPPORT_OPERATOR(Set.of(MEMBER_READ_MINIMAL, MEMBER_RECOVERY, ACCOUNT_APPEAL_REVIEW)),
    ENFORCEMENT_OPERATOR(Set.of(ACCOUNT_SANCTION, STORE_READ_MINIMAL, STORE_SANCTION)),
    PAYMENT_RECOVERY_OPERATOR(Set.of(PAYMENT_RECOVERY_EXECUTE)),
    OPERATIONS_MONITOR(Set.of(OPERATIONS_MONITOR_READ)),
    AUDIT_READER(Set.of(AUDIT_READ)),
    INCIDENT_RESPONDER(Set.of(INCIDENT_RESPOND));

    private final Set<PlatformOperatorPermission> permissions;

    PlatformOperatorRole(Set<PlatformOperatorPermission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    /** 이 역할이 부여하는 수정 불가능한 세부 권한 묶음을 반환한다. */
    public Set<PlatformOperatorPermission> permissions() {
        return permissions;
    }
}
