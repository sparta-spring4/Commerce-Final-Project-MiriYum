package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.platformoperator.dto.audit.PlatformOperatorAuditCorrectionRequest;
import com.miriyum.domain.platformoperator.dto.audit.PlatformOperatorAuditDetailData;
import com.miriyum.domain.platformoperator.dto.audit.PlatformOperatorAuditEventData;
import com.miriyum.domain.platformoperator.dto.audit.PlatformOperatorAuditSearchData;
import com.miriyum.domain.platformoperator.dto.audit.PlatformOperatorAuditSearchRequest;
import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.dto.authorization.HighRiskCommandRequest;
import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAuditEvent;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuditEventRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.response.PageMetadata;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorAuditService {

    private final OperatorAuthorityReader authorityReader;
    private final AdminCaseAssignmentVerifier assignmentVerifier;
    private final PlatformOperatorAuditWriter writer;
    private final PlatformOperatorAuditEventRepository repository;
    private final HighRiskCommandGuard highRiskGuard;
    private final LastSuperAdminPolicy singletonPolicy;
    private final IdempotencyExecutor idempotency;

    public PlatformOperatorAuditService(
            OperatorAuthorityReader authorityReader,
            AdminCaseAssignmentVerifier assignmentVerifier,
            PlatformOperatorAuditWriter writer,
            PlatformOperatorAuditEventRepository repository,
            HighRiskCommandGuard highRiskGuard,
            LastSuperAdminPolicy singletonPolicy,
            IdempotencyExecutor idempotency
    ) {
        this.authorityReader = authorityReader;
        this.assignmentVerifier = assignmentVerifier;
        this.writer = writer;
        this.repository = repository;
        this.highRiskGuard = highRiskGuard;
        this.singletonPolicy = singletonPolicy;
        this.idempotency = idempotency;
    }

    @Transactional
    public PlatformOperatorAuditSearchData search(
            PlatformOperatorPrincipal principal,
            PlatformOperatorAuditSearchRequest request,
            String caseId,
            long caseVersion,
            PlatformOperatorAuditReason reason,
            String correlationId
    ) {
        authorizeAndAuditRead(principal, PlatformOperatorAuditAction.AUDIT_SEARCH,
                "AUDIT_QUERY", "search", caseId, caseVersion, reason, correlationId);
        PlatformOperatorAuditEventRepository.AuditPage result = repository.search(request);
        int totalPages = result.totalElements() == 0 ? 0
                : (int) ((result.totalElements() + request.size() - 1) / request.size());
        return new PlatformOperatorAuditSearchData(
                result.content().stream().map(PlatformOperatorAuditService::data).toList(),
                new PageMetadata(request.page(), request.size(), result.totalElements(), totalPages,
                        request.page() + 1 < totalPages));
    }

    @Transactional
    public PlatformOperatorAuditDetailData detail(
            PlatformOperatorPrincipal principal,
            String eventKey,
            String caseId,
            long caseVersion,
            PlatformOperatorAuditReason reason,
            String correlationId
    ) {
        requireEventKey(eventKey);
        authorizeAndAuditRead(principal, PlatformOperatorAuditAction.AUDIT_DETAIL_READ,
                "AUDIT_EVENT", eventKey, caseId, caseVersion, reason, correlationId);
        PlatformOperatorAuditEventRepository.AuditRow original = repository.findProjected(eventKey)
                .orElseThrow(() -> new ServiceException(AdminAuthorizationErrorCode.AUDIT_EVENT_NOT_FOUND));
        PlatformOperatorAuditSearchRequest corrections = new PlatformOperatorAuditSearchRequest(
                0, 1, "ADMIN", PlatformOperatorAuditAction.AUDIT_CORRECTION, null,
                null, null, null, null, null, eventKey);
        return new PlatformOperatorAuditDetailData(
                data(original), repository.search(corrections).content().stream()
                        .map(PlatformOperatorAuditService::data).toList());
    }

    @Transactional
    public IdempotentOutcome correct(
            IdempotencyCommand command,
            PlatformOperatorPrincipal principal,
            String eventKey,
            PlatformOperatorAuditCorrectionRequest request,
            String caseId,
            long caseVersion,
            String approval,
            String correlationId
    ) {
        EventIdentity original = requireEventKey(eventKey);
        singletonPolicy.requireSingletonActor(principal.accountId());
        return idempotency.execute(command, () -> {
            repository.findProjected(eventKey)
                    .orElseThrow(() -> new ServiceException(AdminAuthorizationErrorCode.AUDIT_EVENT_NOT_FOUND));
            AdminAuditContext context = authorizeCorrection(
                    principal, eventKey, caseId, caseVersion, approval, correlationId);
            if (repository.existsCorrection(original.source(), original.id())) {
                throw new ServiceException(AdminAuthorizationErrorCode.AUDIT_CORRECTION_CONFLICT);
            }
            try {
                PlatformOperatorAuditEvent correction = writer.appendCorrection(
                        new PlatformOperatorAuditWriter.CorrectionEvent(
                                context, eventKey, request.correctedAction(), request.correctedOutcome(),
                                request.correctedTargetType(), request.correctedTargetId(),
                                request.correctedReason(), command.idempotencyKey()));
                PlatformOperatorAuditEventData response = correctionData(correction, context, eventKey);
                return new BusinessResult<>(HttpStatus.CREATED.value(), "SUCCESS", "AUDIT_EVENT",
                        response.eventKey(), response);
            } catch (DataIntegrityViolationException exception) {
                throw new ServiceException(AdminAuthorizationErrorCode.AUDIT_CORRECTION_CONFLICT);
            }
        });
    }

    private AdminAuditContext authorizeCorrection(
            PlatformOperatorPrincipal principal,
            String eventKey,
            String caseId,
            long caseVersion,
            String approval,
            String correlationId
    ) {
        return highRiskGuard.authorize(new HighRiskCommandRequest(
                principal, PlatformOperatorPermission.OPERATOR_AUTHORITY_MANAGE,
                AdminCaseType.AUDIT_REVIEW, caseId, caseVersion,
                AdminCommandPurpose.AUDIT_CORRECTION, AdminTargetType.AUDIT_EVENT,
                eventKey, approval, correlationId));
    }

    private OperatorAuthority authorizeAndAuditRead(
            PlatformOperatorPrincipal principal,
            PlatformOperatorAuditAction action,
            String targetType,
            String targetId,
            String caseId,
            long caseVersion,
            PlatformOperatorAuditReason reason,
            String correlationId
    ) {
        OperatorAuthority authority = null;
        try {
            authority = authorityReader.requireCurrentAuthority(
                    principal.accountId(), principal.authorityVersion());
            if (!authority.permissions().contains(PlatformOperatorPermission.AUDIT_READ)) {
                throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
            }
            assignmentVerifier.verify(new AdminCaseAssignmentRequest(
                    AdminCaseType.AUDIT_REVIEW, caseId, caseVersion, principal.accountId()));
            appendRead(authority, action, PlatformOperatorAuditOutcome.SUCCESS, reason,
                    targetType, targetId, caseId, caseVersion, correlationId);
            return authority;
        } catch (ServiceException exception) {
            Set<PlatformOperatorRole> roles = authority == null ? Set.of() : authority.roles();
            Set<PlatformOperatorPermission> permissions = authority == null ? Set.of() : authority.permissions();
            appendRead(new OperatorAuthority(principal.accountId(), principal.authorityVersion(), roles, permissions),
                    action, PlatformOperatorAuditOutcome.DENIED, reason, targetType, targetId,
                    caseId, caseVersion, correlationId);
            throw exception;
        }
    }

    private void appendRead(
            OperatorAuthority authority,
            PlatformOperatorAuditAction action,
            PlatformOperatorAuditOutcome outcome,
            PlatformOperatorAuditReason reason,
            String targetType,
            String targetId,
            String caseId,
            long caseVersion,
            String correlationId
    ) {
        try {
            writer.appendReadAttempt(new PlatformOperatorAuditWriter.ReadEvent(
                    authority.operatorId(), authority.authorityVersion(), authority.roles(), authority.permissions(),
                    action, outcome, reason, targetType, targetId, caseId, caseVersion, correlationId));
        } catch (RuntimeException exception) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static PlatformOperatorAuditEventData data(PlatformOperatorAuditEventRepository.AuditRow row) {
        return new PlatformOperatorAuditEventData(
                row.eventKey(), row.source(), row.action(), row.outcome(), row.actorId(),
                row.authorityVersion(), row.roles(), row.permissions(), row.beforeStatus(), row.afterStatus(),
                row.beforeRoles(), row.afterRoles(), row.beforePermissions(), row.afterPermissions(),
                row.targetType(), row.targetId(), row.reason(), row.correctedAction(), row.correctedOutcome(),
                row.correctedTargetType(), row.correctedTargetId(), row.correctedReason(),
                row.correlationId(), row.originalEventKey(), row.occurredAt());
    }

    private static PlatformOperatorAuditEventData correctionData(
            PlatformOperatorAuditEvent event,
            AdminAuditContext context,
            String originalEventKey
    ) {
        if (event.getId() == null) throw new IllegalStateException("persisted correction event id is missing");
        return new PlatformOperatorAuditEventData(
                "ADMIN:" + event.getId(), "ADMIN", event.getAction().name(), event.getOutcome().name(),
                Long.toString(context.operatorId()), context.authorityVersion(),
                context.roles().stream().map(Enum::name).collect(Collectors.toSet()),
                context.permissions().stream().map(Enum::name).collect(Collectors.toSet()),
                null, null, Set.of(), Set.of(), Set.of(), Set.of(),
                event.getTargetType(), event.getTargetId(), event.getReason().name(),
                name(event.getCorrectedAction()), name(event.getCorrectedOutcome()),
                event.getCorrectedTargetType(), event.getCorrectedTargetId(), name(event.getCorrectedReason()),
                event.getCorrelationId(), originalEventKey, event.getOccurredAt());
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static EventIdentity requireEventKey(String eventKey) {
        if (eventKey == null || !eventKey.matches("(?:AUTH|ADMIN):[1-9][0-9]{0,18}")) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        int separator = eventKey.indexOf(':');
        return new EventIdentity(eventKey.substring(0, separator),
                Long.parseLong(eventKey.substring(separator + 1)));
    }

    private record EventIdentity(String source, long id) {
    }
}
