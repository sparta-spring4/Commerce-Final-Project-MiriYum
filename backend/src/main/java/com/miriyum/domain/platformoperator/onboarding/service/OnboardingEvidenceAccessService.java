package com.miriyum.domain.platformoperator.onboarding.service;

import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.dto.authorization.HighRiskCommandRequest;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.service.HighRiskCommandGuard;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditWriter;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditWriter.OnboardingReadAttempt;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.store.evidence.dto.BusinessRegistrationEvidenceContent;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.EvidenceAccessDescriptor;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.EvidenceReadQuery;
import com.miriyum.domain.store.onboarding.service.StoreOnboardingReviewWorkflow;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class OnboardingEvidenceAccessService {

    private final StoreOnboardingReviewWorkflow workflow;
    private final EvidenceAccessAuthorizer authorization;
    private final PlatformOperatorAuditWriter audit;

    public BusinessRegistrationEvidenceContent read(
            PlatformOperatorPrincipal principal, String caseId, long expectedCaseVersion,
            String approval, String correlationId) {
        AdminAuditContext context = null;
        try {
            EvidenceAccessDescriptor descriptor =
                    workflow.resolveEvidenceAccess(caseId, expectedCaseVersion);
            // 별도 트랜잭션 프록시가 반환된 시점에 일회 승인 소비가 먼저 커밋된다.
            context = authorization.authorize(principal, descriptor, approval, correlationId);
            BusinessRegistrationEvidenceContent content = workflow.readEvidence(new EvidenceReadQuery(
                    descriptor.caseId(), descriptor.caseVersion(), descriptor.applicationId(),
                    descriptor.applicationVersion(), descriptor.evidenceId()));
            audit.appendOnboardingReadAttempt(success(context));
            return content;
        } catch (RuntimeException exception) {
            audit.appendOnboardingReadAttempt(failure(
                    principal, context, caseId, expectedCaseVersion, correlationId));
            throw exception;
        }
    }

    private static OnboardingReadAttempt success(AdminAuditContext context) {
        return new OnboardingReadAttempt(
                context.operatorId(), context.authorityVersion(), context.roles(), context.permissions(),
                PlatformOperatorAuditOutcome.SUCCESS, context.caseId(), context.caseVersion(),
                context.correlationId());
    }

    private static OnboardingReadAttempt failure(
            PlatformOperatorPrincipal principal, AdminAuditContext context,
            String caseId, long caseVersion, String correlationId) {
        if (context != null) {
            return new OnboardingReadAttempt(
                    context.operatorId(), context.authorityVersion(), context.roles(),
                    context.permissions(), PlatformOperatorAuditOutcome.FAILED,
                    context.caseId(), context.caseVersion(), context.correlationId());
        }
        return new OnboardingReadAttempt(
                principal.accountId(), principal.authorityVersion(), Set.of(), Set.of(),
                PlatformOperatorAuditOutcome.FAILED, caseId, caseVersion, correlationId);
    }

    public interface EvidenceAccessAuthorizer {
        AdminAuditContext authorize(
                PlatformOperatorPrincipal principal, EvidenceAccessDescriptor descriptor,
                String approval, String correlationId);
    }
}

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
class OnboardingEvidenceAccessAuthorization implements OnboardingEvidenceAccessService.EvidenceAccessAuthorizer {

    private final HighRiskCommandGuard guard;

    @Transactional
    @Override
    public AdminAuditContext authorize(
            PlatformOperatorPrincipal principal, EvidenceAccessDescriptor descriptor,
            String approval, String correlationId) {
        String boundTarget = descriptor.applicationId() + ":"
                + descriptor.applicationVersion() + ":" + descriptor.evidenceId();
        return guard.authorize(new HighRiskCommandRequest(
                principal, PlatformOperatorPermission.ONBOARDING_EVIDENCE_READ,
                AdminCaseType.ONBOARDING_REVIEW, descriptor.caseId(), descriptor.caseVersion(),
                AdminCommandPurpose.ONBOARDING_EVIDENCE_ACCESS,
                AdminTargetType.ONBOARDING_APPLICATION, boundTarget, approval, correlationId));
    }
}
