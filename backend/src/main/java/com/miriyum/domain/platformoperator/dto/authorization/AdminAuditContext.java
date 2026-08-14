package com.miriyum.domain.platformoperator.dto.authorization;

import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import java.util.Set;

public record AdminAuditContext(
        long operatorId,
        Set<PlatformOperatorRole> roles,
        Set<PlatformOperatorPermission> permissions,
        long authorityVersion,
        AdminCaseType caseType,
        String caseId,
        long caseVersion,
        AdminCommandPurpose purpose,
        AdminTargetType targetType,
        String targetId,
        String approvalFingerprint,
        String correlationId
) {
    public AdminAuditContext {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }
}
