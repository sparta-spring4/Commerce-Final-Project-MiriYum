package com.miriyum.domain.menu.model;

import com.miriyum.domain.menu.enums.MenuAuditActorType;
import com.miriyum.domain.menu.enums.MenuAuditOutcome;
import com.miriyum.domain.menu.enums.MenuImpactCheckStatus;
import com.miriyum.domain.menu.enums.MenuPublicationEventType;
import com.miriyum.domain.menu.enums.MenuRecoveryResult;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.MenuVisibility;
import java.time.Instant;

/** append-only 메뉴 감사 사건을 저장하기 위한 완전한 입력이다. */
public record MenuAuditRecord(
        long menuId,
        Integer versionNumber,
        MenuPublicationEventType eventType,
        MenuAuditActorType actorType,
        Long actorOperatorId,
        Instant commandedAt,
        Instant effectiveAt,
        Instant confirmedAt,
        String requestId,
        MenuAuditOutcome outcome,
        Integer previousVersionNumber,
        Integer newVersionNumber,
        String changedFields,
        String changeReason,
        MenuVisibility previousVisibility,
        MenuVisibility newVisibility,
        MenuSellingStatus previousSellingStatus,
        MenuSellingStatus newSellingStatus,
        MenuImpactCheckStatus impactCheckStatus,
        Integer impactCount,
        MenuRecoveryResult recoveryResult
) {
}
