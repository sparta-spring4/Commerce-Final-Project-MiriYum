package com.miriyum.domain.platformoperator.onboarding;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.onboarding.dto.OnboardingReviewRequests.DecisionRequest;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingReviewCommandService;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentManager;
import com.miriyum.domain.platformoperator.service.HighRiskCommandGuard;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditWriter;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.store.onboarding.config.StoreOnboardingProperties;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewCaseDetail;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewDecisionAction;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewStatus;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewType;
import com.miriyum.domain.store.onboarding.service.StoreOnboardingReviewWorkflow;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OnboardingReviewCommandServiceTest {
    @Mock StoreOnboardingReviewWorkflow workflow;
    @Mock HighRiskCommandGuard guard;
    @Mock AdminCaseAssignmentManager assignments;
    @Mock PlatformOperatorAuditWriter audit;
    @Mock IdempotencyExecutor idempotency;

    @Test
    void decisionRequiresPermissionAssignmentVersionsAndReauthentication() {
        String caseId = "550e8400-e29b-41d4-a716-446655440277";
        var detail = new ReviewCaseDetail(
                caseId, ReviewType.ONBOARDING, ReviewStatus.UNDER_REVIEW,
                41L, 1L, 2L, 91L, null);
        given(workflow.getReviewCase(caseId)).willReturn(detail);
        willThrow(new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED))
                .given(guard).authorize(any());
        given(idempotency.execute(any(), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<Object>> work = invocation.getArgument(1);
            work.get();
            return org.mockito.Mockito.mock(IdempotentOutcome.class);
        });
        var service = new OnboardingReviewCommandService(
                workflow, guard, assignments, audit,
                new StoreOnboardingProperties(false, 5000, 30000, 25, 30, 3650),
                idempotency,
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));
        var principal = new PlatformOperatorPrincipal(
                91L, "operator@example.com", "session", 1L, 1L, false);

        assertThatThrownBy(() -> service.decide(
                new IdempotencyCommand(
                        "platform-operator", principal.accountId(), "ONBOARDING_DECIDE",
                        "550e8400-e29b-41d4-a716-446655440001", "a".repeat(64)),
                principal, caseId,
                new DecisionRequest(ReviewDecisionAction.APPROVE, 1L, 2L, "APPROVED"),
                "approval", "correlation"))
                .isInstanceOf(ServiceException.class);
        then(workflow).shouldHaveNoMoreInteractions();
    }
}
