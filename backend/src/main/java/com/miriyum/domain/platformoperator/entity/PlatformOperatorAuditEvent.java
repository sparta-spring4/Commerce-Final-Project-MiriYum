package com.miriyum.domain.platformoperator.entity;

import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Immutable;
import org.hibernate.type.SqlTypes;

/** UPDATE·DELETE 경로 없이 append만 허용하는 플랫폼 운영자 감사 원장 행이다. */
@Entity
@Immutable
@Table(name = "platform_operator_audit_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatformOperatorAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "platform_operator_audit_event_id")
    private Long id;

    @Column(name = "actor_platform_operator_account_id", nullable = false)
    private Long actorPlatformOperatorAccountId;

    @Column(name = "actor_authority_version", nullable = false)
    private long actorAuthorityVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actor_roles", nullable = false, columnDefinition = "json")
    private Set<PlatformOperatorRole> actorRoles;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actor_permissions", nullable = false, columnDefinition = "json")
    private Set<PlatformOperatorPermission> actorPermissions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PlatformOperatorAuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlatformOperatorAuditOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PlatformOperatorAuditReason reason;

    @Column(name = "target_type", nullable = false, length = 50)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 100)
    private String targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_type", length = 50)
    private AdminCaseType caseType;

    @Column(name = "case_id", length = 100)
    private String caseId;

    @Column(name = "case_version")
    private Long caseVersion;

    @Column(name = "idempotency_key", length = 36)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "before_status", length = 20)
    private PlatformOperatorAccountStatus beforeStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_status", length = 20)
    private PlatformOperatorAccountStatus afterStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_roles", nullable = false, columnDefinition = "json")
    private Set<PlatformOperatorRole> beforeRoles;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_roles", nullable = false, columnDefinition = "json")
    private Set<PlatformOperatorRole> afterRoles;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_permissions", nullable = false, columnDefinition = "json")
    private Set<PlatformOperatorPermission> beforePermissions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_permissions", nullable = false, columnDefinition = "json")
    private Set<PlatformOperatorPermission> afterPermissions;

    @Enumerated(EnumType.STRING)
    @Column(name = "corrected_action", length = 50)
    private PlatformOperatorAuditAction correctedAction;

    @Enumerated(EnumType.STRING)
    @Column(name = "corrected_outcome", length = 20)
    private PlatformOperatorAuditOutcome correctedOutcome;

    @Column(name = "corrected_target_type", length = 50)
    private String correctedTargetType;

    @Column(name = "corrected_target_id", length = 100)
    private String correctedTargetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "corrected_reason", length = 50)
    private PlatformOperatorAuditReason correctedReason;

    @Column(name = "original_event_id")
    private Long originalEventId;

    @Column(name = "original_event_source", length = 10)
    private String originalEventSource;

    @Column(name = "correlation_id", nullable = false, length = 100)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "store_sanction_id")
    private Long storeSanctionId;

    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "store_sanction_version")
    private Long storeSanctionVersion;

    @Column(name = "store_enforcement_version")
    private Long storeEnforcementVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_snapshot", columnDefinition = "json")
    private Map<String, Object> beforeSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_snapshot", columnDefinition = "json")
    private Map<String, Object> afterSnapshot;

    public static PlatformOperatorAuditEvent create(
            long actorId,
            long authorityVersion,
            Set<PlatformOperatorRole> actorRoles,
            Set<PlatformOperatorPermission> actorPermissions,
            PlatformOperatorAuditAction action,
            PlatformOperatorAuditOutcome outcome,
            PlatformOperatorAuditReason reason,
            String targetType,
            String targetId,
            AdminCaseType caseType,
            String caseId,
            long caseVersion,
            String idempotencyKey,
            PlatformOperatorAccountStatus beforeStatus,
            PlatformOperatorAccountStatus afterStatus,
            Set<PlatformOperatorRole> beforeRoles,
            Set<PlatformOperatorRole> afterRoles,
            Set<PlatformOperatorPermission> beforePermissions,
            Set<PlatformOperatorPermission> afterPermissions,
            String correlationId,
            Instant occurredAt
    ) {
        PlatformOperatorAuditEvent event = base(
                actorId, authorityVersion, actorRoles, actorPermissions,
                action, outcome, reason, targetType, targetId,
                caseType, caseId, caseVersion, idempotencyKey, correlationId, occurredAt);
        event.beforeStatus = beforeStatus;
        event.afterStatus = afterStatus;
        event.beforeRoles = Set.copyOf(Objects.requireNonNull(beforeRoles));
        event.afterRoles = Set.copyOf(Objects.requireNonNull(afterRoles));
        event.beforePermissions = Set.copyOf(Objects.requireNonNull(beforePermissions));
        event.afterPermissions = Set.copyOf(Objects.requireNonNull(afterPermissions));
        return event;
    }

    public static PlatformOperatorAuditEvent createCorrection(
            long actorId,
            long authorityVersion,
            Set<PlatformOperatorRole> actorRoles,
            Set<PlatformOperatorPermission> actorPermissions,
            String originalEventKey,
            PlatformOperatorAuditAction correctedAction,
            PlatformOperatorAuditOutcome correctedOutcome,
            String correctedTargetType,
            String correctedTargetId,
            PlatformOperatorAuditReason correctedReason,
            AdminCaseType caseType,
            String caseId,
            long caseVersion,
            String idempotencyKey,
            String correlationId,
            Instant occurredAt
    ) {
        PlatformOperatorAuditEvent event = base(
                actorId, authorityVersion, actorRoles, actorPermissions,
                PlatformOperatorAuditAction.AUDIT_CORRECTION,
                PlatformOperatorAuditOutcome.SUCCESS, PlatformOperatorAuditReason.RECORD_CORRECTION,
                "AUDIT_EVENT", requireEventKey(originalEventKey), caseType, caseId, caseVersion,
                idempotencyKey, correlationId, occurredAt);
        event.beforeRoles = Set.of();
        event.afterRoles = Set.of();
        event.beforePermissions = Set.of();
        event.afterPermissions = Set.of();
        String[] original = originalEventKey.split(":", 2);
        event.originalEventSource = original[0];
        event.originalEventId = Long.parseLong(original[1]);
        event.correctedAction = correctedAction;
        event.correctedOutcome = correctedOutcome;
        event.correctedTargetType = optionalText(correctedTargetType, 50, "correctedTargetType");
        event.correctedTargetId = optionalText(correctedTargetId, 100, "correctedTargetId");
        event.correctedReason = correctedReason;
        return event;
    }

    public static PlatformOperatorAuditEvent createStore(
            long actorId, long authorityVersion, Set<PlatformOperatorRole> actorRoles,
            Set<PlatformOperatorPermission> actorPermissions, PlatformOperatorAuditAction action,
            PlatformOperatorAuditOutcome outcome, PlatformOperatorAuditReason reason,
            String targetType, String targetId, Long storeId, String caseId, long caseVersion,
            Long sanctionId, Long sanctionVersion, Long enforcementVersion, String idempotencyKey,
            Map<String, Object> beforeSnapshot, Map<String, Object> afterSnapshot,
            String correlationId, Instant occurredAt) {
        PlatformOperatorAuditEvent event = base(actorId, authorityVersion, actorRoles, actorPermissions,
                action, outcome, reason, targetType, targetId, AdminCaseType.STORE_ENFORCEMENT,
                caseId, caseVersion, idempotencyKey, correlationId, occurredAt);
        event.beforeRoles = Set.of();
        event.afterRoles = Set.of();
        event.beforePermissions = Set.of();
        event.afterPermissions = Set.of();
        event.storeId = storeId;
        event.storeSanctionId = sanctionId;
        event.storeSanctionVersion = sanctionVersion;
        event.storeEnforcementVersion = enforcementVersion;
        event.beforeSnapshot = Map.copyOf(beforeSnapshot);
        event.afterSnapshot = Map.copyOf(afterSnapshot);
        return event;
    }

    private static PlatformOperatorAuditEvent base(
            long actorId,
            long authorityVersion,
            Set<PlatformOperatorRole> actorRoles,
            Set<PlatformOperatorPermission> actorPermissions,
            PlatformOperatorAuditAction action,
            PlatformOperatorAuditOutcome outcome,
            PlatformOperatorAuditReason reason,
            String targetType,
            String targetId,
            AdminCaseType caseType,
            String caseId,
            long caseVersion,
            String idempotencyKey,
            String correlationId,
            Instant occurredAt
    ) {
        PlatformOperatorAuditEvent event = new PlatformOperatorAuditEvent();
        event.actorPlatformOperatorAccountId = requirePositive(actorId, "actorId");
        event.actorAuthorityVersion = requirePositive(authorityVersion, "authorityVersion");
        event.actorRoles = Set.copyOf(Objects.requireNonNull(actorRoles));
        event.actorPermissions = Set.copyOf(Objects.requireNonNull(actorPermissions));
        event.action = Objects.requireNonNull(action);
        event.outcome = Objects.requireNonNull(outcome);
        event.reason = Objects.requireNonNull(reason);
        event.targetType = requireText(targetType, 50, "targetType");
        event.targetId = requireText(targetId, 100, "targetId");
        event.caseType = Objects.requireNonNull(caseType);
        event.caseId = requireText(caseId, 100, "caseId");
        event.caseVersion = requirePositive(caseVersion, "caseVersion");
        event.idempotencyKey = optionalText(idempotencyKey, 36, "idempotencyKey");
        event.correlationId = requireText(correlationId, 100, "correlationId");
        event.occurredAt = Objects.requireNonNull(occurredAt);
        return event;
    }

    private static Long requirePositive(long value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    private static String requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String optionalText(String value, int maxLength, String field) {
        return value == null ? null : requireText(value, maxLength, field);
    }

    private static String requireEventKey(String value) {
        if (value == null || !value.matches("(?:AUTH|ADMIN):[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("originalEventKey is invalid");
        }
        return value;
    }
}
