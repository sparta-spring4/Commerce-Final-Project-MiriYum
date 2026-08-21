package com.miriyum.domain.platformoperator.onboarding.service;

import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentCommand;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.dto.authorization.HighRiskCommandRequest;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.onboarding.dto.OnboardingReviewRequests.AssignmentRequest;
import com.miriyum.domain.platformoperator.onboarding.dto.OnboardingReviewRequests.DecisionRequest;
import com.miriyum.domain.platformoperator.onboarding.dto.OnboardingReviewRequests.ReassignmentRequest;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentManager;
import com.miriyum.domain.platformoperator.service.HighRiskCommandGuard;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditWriter;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditWriter.OnboardingEvent;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.store.onboarding.config.StoreOnboardingProperties;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ApplicationData;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewCaseDetail;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewDecisionCommand;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewActorContext;
import com.miriyum.domain.store.onboarding.service.StoreOnboardingReviewWorkflow;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class OnboardingReviewCommandService {

    private static final String SUCCESS_RESPONSE_CODE = "SUCCESS";

    private final StoreOnboardingReviewWorkflow workflow;
    private final HighRiskCommandGuard guard;
    private final AdminCaseAssignmentManager assignments;
    private final PlatformOperatorAuditWriter audit;
    private final StoreOnboardingProperties properties;
    private final IdempotencyExecutor idempotency;
    private final Clock clock;

    @Transactional
    public IdempotentOutcome assign(
            IdempotencyCommand command, PlatformOperatorPrincipal principal,
            String caseId, AssignmentRequest request,
            String approval, String correlationId) {
        return idempotency.execute(command, () -> {
            ReviewCaseDetail before = workflow.getReviewCase(caseId);
            var context = guard.authorizeInitialOnboardingAssignment(highRisk(
                    principal, before, request.expectedCaseVersion(),
                    AdminCommandPurpose.ONBOARDING_ASSIGNMENT, approval, correlationId));
            Instant expiresAt = clock.instant().plusSeconds(
                    Math.multiplyExact(properties.caseAssignmentDays(), 86_400L));
            ReviewCaseDetail after = workflow.assign(
                    caseId, request.expectedCaseVersion(), principal.accountId(), expiresAt);
            assignments.assign(new AdminCaseAssignmentCommand(
                    AdminCaseType.ONBOARDING_REVIEW, caseId, after.caseVersion(),
                    principal.accountId(), expiresAt));
            audit.appendOnboarding(new OnboardingEvent(
                    context, PlatformOperatorAuditAction.ONBOARDING_CASE_ASSIGNED,
                    PlatformOperatorAuditOutcome.SUCCESS, command.idempotencyKey()));
            return success("ONBOARDING_REVIEW_CASE", caseId, after);
        });
    }

    @Transactional
    public IdempotentOutcome reassign(
            IdempotencyCommand command, PlatformOperatorPrincipal principal,
            String caseId, ReassignmentRequest request,
            String approval, String correlationId) {
        return idempotency.execute(command, () -> {
            ReviewCaseDetail before = workflow.getReviewCase(caseId);
            var context = guard.authorize(highRisk(
                    principal, before, request.expectedCaseVersion(),
                    AdminCommandPurpose.ONBOARDING_ASSIGNMENT, approval, correlationId));
            Instant expiresAt = clock.instant().plusSeconds(
                    Math.multiplyExact(properties.caseAssignmentDays(), 86_400L));
            ReviewCaseDetail after = workflow.reassign(
                    caseId, request.expectedCaseVersion(), principal.accountId(),
                    request.nextOperatorId(), expiresAt);
            assignments.close(assignment(
                    caseId, request.expectedCaseVersion(), principal.accountId()));
            assignments.assign(new AdminCaseAssignmentCommand(
                    AdminCaseType.ONBOARDING_REVIEW, caseId, after.caseVersion(),
                    request.nextOperatorId(), expiresAt));
            audit.appendOnboarding(new OnboardingEvent(
                    context, PlatformOperatorAuditAction.ONBOARDING_CASE_REASSIGNED,
                    PlatformOperatorAuditOutcome.SUCCESS, command.idempotencyKey()));
            return success("ONBOARDING_REVIEW_CASE", caseId, after);
        });
    }

    @Transactional
    public IdempotentOutcome decide(
            IdempotencyCommand command, PlatformOperatorPrincipal principal,
            String caseId, DecisionRequest request,
            String approval, String correlationId) {
        return idempotency.execute(command, () -> {
            ReviewCaseDetail detail = workflow.getReviewCase(caseId);
            var context = guard.authorize(highRisk(
                    principal, detail, request.expectedCaseVersion(),
                    AdminCommandPurpose.ONBOARDING_DECISION, approval, correlationId));
            ApplicationData result = workflow.decide(new ReviewDecisionCommand(
                    caseId, request.expectedCaseVersion(), request.expectedApplicationVersion(),
                    request.action(), request.reasonCode(),
                    new ReviewActorContext(context.operatorId()), command.idempotencyKey()));
            assignments.close(assignment(
                    caseId, request.expectedCaseVersion(), principal.accountId()));
            audit.appendOnboarding(new OnboardingEvent(
                    context, PlatformOperatorAuditAction.ONBOARDING_DECIDED,
                    PlatformOperatorAuditOutcome.SUCCESS, command.idempotencyKey()));
            return success("STORE_ONBOARDING_APPLICATION", result.applicationId(), result);
        });
    }

    private static HighRiskCommandRequest highRisk(
            PlatformOperatorPrincipal principal, ReviewCaseDetail detail, long caseVersion,
            AdminCommandPurpose purpose, String approval, String correlationId) {
        return new HighRiskCommandRequest(
                principal, PlatformOperatorPermission.ONBOARDING_REVIEW,
                AdminCaseType.ONBOARDING_REVIEW, detail.caseId(), caseVersion,
                purpose, AdminTargetType.ONBOARDING_APPLICATION,
                Long.toString(detail.applicationId()), approval, correlationId);
    }

    private static AdminCaseAssignmentRequest assignment(
            String caseId, long caseVersion, long operatorId) {
        return new AdminCaseAssignmentRequest(
                AdminCaseType.ONBOARDING_REVIEW, caseId, caseVersion, operatorId);
    }

    private static <T> BusinessResult<T> success(
            String resourceType, String resourceId, T data) {
        return new BusinessResult<>(HttpStatus.OK.value(), SUCCESS_RESPONSE_CODE,
                resourceType, resourceId, data);
    }
}
