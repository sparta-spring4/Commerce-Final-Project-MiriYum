package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAuditEvent;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuditEventRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorAuditWriter {

    private final PlatformOperatorAuditEventRepository repository;
    private final Clock clock;

    public PlatformOperatorAuditWriter(PlatformOperatorAuditEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PlatformOperatorAuditEvent appendManagement(ManagementEvent event) {
        AdminAuditContext context = event.context();
        return repository.append(PlatformOperatorAuditEvent.create(
                context.operatorId(), context.authorityVersion(), context.roles(), context.permissions(),
                event.action(), event.outcome(), event.reason(),
                context.targetType().name(), context.targetId(), context.caseType(), context.caseId(),
                context.caseVersion(), event.idempotencyKey(), event.beforeStatus(), event.afterStatus(),
                event.beforeRoles(), event.afterRoles(), event.beforePermissions(), event.afterPermissions(),
                context.correlationId(), clock.instant()));
    }

    /** 허용·거부된 감사 조회 시도는 조회 트랜잭션과 분리해 반드시 먼저 확정한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PlatformOperatorAuditEvent appendReadAttempt(ReadEvent event) {
        return repository.append(PlatformOperatorAuditEvent.create(
                event.actorId(), event.authorityVersion(), event.roles(), event.permissions(),
                event.action(), event.outcome(), event.reason(),
                event.targetType(), event.targetId(), AdminCaseType.AUDIT_REVIEW, event.caseId(),
                event.caseVersion(), null, null, null, Set.of(), Set.of(),
                Set.of(), Set.of(), event.correlationId(), clock.instant()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PlatformOperatorAuditEvent appendCorrection(CorrectionEvent event) {
        AdminAuditContext context = event.context();
        return repository.append(PlatformOperatorAuditEvent.createCorrection(
                context.operatorId(), context.authorityVersion(), context.roles(), context.permissions(),
                event.originalEventKey(),
                event.correctedAction(), event.correctedOutcome(), event.correctedTargetType(),
                event.correctedTargetId(), event.correctedReason(), context.caseType(), context.caseId(),
                context.caseVersion(), event.idempotencyKey(), context.correlationId(), clock.instant()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PlatformOperatorAuditEvent appendStore(StoreEvent event) {
        return repository.append(PlatformOperatorAuditEvent.createStore(
                event.operatorId(), event.authorityVersion(), event.roles(), event.permissions(),
                event.action(), event.outcome(), event.reason(), event.targetType(), event.targetId(),
                event.storeId(), event.caseId(), event.caseVersion(), event.sanctionId(),
                event.sanctionVersion(), event.storeEnforcementVersion(), event.idempotencyKey(),
                event.beforeSnapshot(), event.afterSnapshot(), event.correlationId(), clock.instant()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PlatformOperatorAuditEvent appendRecovery(RecoveryEvent event) {
        return repository.append(PlatformOperatorAuditEvent.createRecovery(
                event.context().operatorId(), event.context().authorityVersion(),
                event.context().roles(), event.context().permissions(), event.action(),
                event.outcome(), event.reason(), event.targetType(), event.targetId(),
                event.context().caseType(), event.context().caseId(), event.context().caseVersion(),
                event.idempotencyKey(), event.beforeSnapshot(), event.afterSnapshot(),
                event.context().correlationId(), clock.instant()));
    }

    public record StoreEvent(long operatorId, long authorityVersion, Set<PlatformOperatorRole> roles,
                             Set<PlatformOperatorPermission> permissions,
                             PlatformOperatorAuditAction action, PlatformOperatorAuditOutcome outcome,
                             PlatformOperatorAuditReason reason, String targetType, String targetId,
                             Long storeId, String caseId, long caseVersion, Long sanctionId,
                             Long sanctionVersion, Long storeEnforcementVersion, String idempotencyKey,
                             Map<String, Object> beforeSnapshot, Map<String, Object> afterSnapshot,
                             String correlationId) {
        public StoreEvent { roles=Set.copyOf(roles); permissions=Set.copyOf(permissions);
            beforeSnapshot=Map.copyOf(beforeSnapshot); afterSnapshot=Map.copyOf(afterSnapshot); }
    }

    public record RecoveryEvent(
            AdminAuditContext context, PlatformOperatorAuditAction action,
            PlatformOperatorAuditOutcome outcome, PlatformOperatorAuditReason reason,
            String targetType, String targetId, String idempotencyKey,
            Map<String, Object> beforeSnapshot, Map<String, Object> afterSnapshot) {
        public RecoveryEvent {
            Objects.requireNonNull(context);
            Objects.requireNonNull(action);
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(reason);
            beforeSnapshot = Map.copyOf(beforeSnapshot);
            afterSnapshot = Map.copyOf(afterSnapshot);
        }
    }

    public record ManagementEvent(
            AdminAuditContext context,
            PlatformOperatorAuditAction action,
            PlatformOperatorAuditOutcome outcome,
            PlatformOperatorAuditReason reason,
            String idempotencyKey,
            PlatformOperatorAccountStatus beforeStatus,
            PlatformOperatorAccountStatus afterStatus,
            Set<PlatformOperatorRole> beforeRoles,
            Set<PlatformOperatorRole> afterRoles,
            Set<PlatformOperatorPermission> beforePermissions,
            Set<PlatformOperatorPermission> afterPermissions
    ) {
        public ManagementEvent {
            Objects.requireNonNull(context);
            Objects.requireNonNull(action);
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(reason);
            beforeRoles = Set.copyOf(beforeRoles);
            afterRoles = Set.copyOf(afterRoles);
            beforePermissions = Set.copyOf(beforePermissions);
            afterPermissions = Set.copyOf(afterPermissions);
        }
    }

    public record ReadEvent(
            long actorId,
            long authorityVersion,
            Set<PlatformOperatorRole> roles,
            Set<PlatformOperatorPermission> permissions,
            PlatformOperatorAuditAction action,
            PlatformOperatorAuditOutcome outcome,
            PlatformOperatorAuditReason reason,
            String targetType,
            String targetId,
            String caseId,
            long caseVersion,
            String correlationId
    ) {
        public ReadEvent {
            roles = Set.copyOf(roles);
            permissions = Set.copyOf(permissions);
        }
    }

    public record CorrectionEvent(
            AdminAuditContext context,
            String originalEventKey,
            PlatformOperatorAuditAction correctedAction,
            PlatformOperatorAuditOutcome correctedOutcome,
            String correctedTargetType,
            String correctedTargetId,
            PlatformOperatorAuditReason correctedReason,
            String idempotencyKey
    ) {
    }
}
