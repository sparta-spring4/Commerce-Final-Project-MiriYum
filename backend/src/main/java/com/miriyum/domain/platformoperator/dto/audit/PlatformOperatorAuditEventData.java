package com.miriyum.domain.platformoperator.dto.audit;

import java.time.Instant;
import java.util.Set;

public record PlatformOperatorAuditEventData(
        String eventKey,
        String source,
        String action,
        String outcome,
        String actorOperatorId,
        long authorityVersion,
        Set<String> roles,
        Set<String> permissions,
        String beforeStatus,
        String afterStatus,
        Set<String> beforeRoles,
        Set<String> afterRoles,
        Set<String> beforePermissions,
        Set<String> afterPermissions,
        String targetType,
        String targetId,
        String reason,
        String correctedAction,
        String correctedOutcome,
        String correctedTargetType,
        String correctedTargetId,
        String correctedReason,
        String correlationId,
        String originalEventKey,
        Instant occurredAt
) {
    public PlatformOperatorAuditEventData {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
        beforeRoles = Set.copyOf(beforeRoles);
        afterRoles = Set.copyOf(afterRoles);
        beforePermissions = Set.copyOf(beforePermissions);
        afterPermissions = Set.copyOf(afterPermissions);
    }
}
