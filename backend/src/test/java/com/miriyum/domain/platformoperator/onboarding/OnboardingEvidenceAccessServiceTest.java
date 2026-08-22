package com.miriyum.domain.platformoperator.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditWriter;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingEvidenceAccessService;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingEvidenceAccessService.EvidenceAccessAuthorizer;
import com.miriyum.domain.store.evidence.dto.BusinessRegistrationEvidenceContent;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.EvidenceAccessDescriptor;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.EvidenceReadQuery;
import com.miriyum.domain.store.onboarding.service.StoreOnboardingReviewWorkflow;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OnboardingEvidenceAccessServiceTest {
    @Mock StoreOnboardingReviewWorkflow workflow;
    @Mock EvidenceAccessAuthorizer authorization;
    @Mock PlatformOperatorAuditWriter audit;

    private static final String CASE_ID = "550e8400-e29b-41d4-a716-446655440277";
    private static final UUID EVIDENCE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440278");

    @Test
    void commitsOneTimeAuthorizationBeforeReadingRawEvidence() {
        var descriptor = new EvidenceAccessDescriptor(CASE_ID, 2L, 41L, 1L, EVIDENCE_ID);
        var context = context();
        var content = new BusinessRegistrationEvidenceContent("application/pdf", new byte[]{1, 2});
        given(workflow.resolveEvidenceAccess(CASE_ID, 2L)).willReturn(descriptor);
        given(authorization.authorize(principal(), descriptor, "approval", "correlation"))
                .willReturn(context);
        given(workflow.readEvidence(new EvidenceReadQuery(CASE_ID, 2L, 41L, 1L, EVIDENCE_ID)))
                .willReturn(content);
        var service = new OnboardingEvidenceAccessService(workflow, authorization, audit);

        assertThat(service.read(principal(), CASE_ID, 2L, "approval", "correlation").bytes())
                .containsExactly(1, 2);

        InOrder order = inOrder(authorization, workflow, audit);
        order.verify(authorization).authorize(principal(), descriptor, "approval", "correlation");
        order.verify(workflow).readEvidence(
                new EvidenceReadQuery(CASE_ID, 2L, 41L, 1L, EVIDENCE_ID));
        order.verify(audit).appendOnboardingReadAttempt(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void auditsStorageFailureWithoutSensitiveEvidenceIdentity() {
        var descriptor = new EvidenceAccessDescriptor(CASE_ID, 2L, 41L, 1L, EVIDENCE_ID);
        given(workflow.resolveEvidenceAccess(CASE_ID, 2L)).willReturn(descriptor);
        given(authorization.authorize(principal(), descriptor, "approval", "correlation"))
                .willReturn(context());
        given(workflow.readEvidence(org.mockito.ArgumentMatchers.any()))
                .willThrow(new IllegalStateException("storage failed"));
        var service = new OnboardingEvidenceAccessService(workflow, authorization, audit);

        assertThatThrownBy(() -> service.read(
                principal(), CASE_ID, 2L, "approval", "correlation"))
                .isInstanceOf(IllegalStateException.class);

        var event = ArgumentCaptor.forClass(
                PlatformOperatorAuditWriter.OnboardingReadAttempt.class);
        then(audit).should().appendOnboardingReadAttempt(event.capture());
        assertThat(event.getValue().outcome()).isEqualTo(PlatformOperatorAuditOutcome.FAILED);
        assertThat(event.getValue().caseId()).isEqualTo(CASE_ID);
        assertThat(event.getValue().toString())
                .doesNotContain(EVIDENCE_ID.toString())
                .doesNotContain("storage failed");
    }

    private static PlatformOperatorPrincipal principal() {
        return new PlatformOperatorPrincipal(91L, "operator@example.com", "session", 1L, 1L, false);
    }

    private static AdminAuditContext context() {
        return new AdminAuditContext(
                91L, Set.of(PlatformOperatorRole.ONBOARDING_REVIEWER),
                Set.of(PlatformOperatorPermission.ONBOARDING_EVIDENCE_READ), 1L,
                AdminCaseType.ONBOARDING_REVIEW, CASE_ID, 2L,
                AdminCommandPurpose.ONBOARDING_EVIDENCE_ACCESS,
                AdminTargetType.ONBOARDING_APPLICATION, "bound-target", "fingerprint", "correlation");
    }
}
