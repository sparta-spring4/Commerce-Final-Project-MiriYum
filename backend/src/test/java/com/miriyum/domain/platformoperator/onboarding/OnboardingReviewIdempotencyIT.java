package com.miriyum.domain.platformoperator.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.onboarding.dto.OnboardingReviewRequests.AssignmentRequest;
import com.miriyum.domain.platformoperator.onboarding.dto.OnboardingReviewRequests.DecisionRequest;
import com.miriyum.domain.platformoperator.onboarding.dto.OnboardingReviewRequests.ReassignmentRequest;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingReviewCommandService;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentService;
import com.miriyum.domain.platformoperator.service.HighRiskCommandGuard;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditWriter;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ApplicationData;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewCaseDetail;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewDecisionAction;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewStatus;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewType;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ApplicationStatus;
import com.miriyum.domain.store.onboarding.service.StoreOnboardingReviewWorkflow;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyCommand;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(properties = {
        "miriyum.platform-operator.enabled=true",
        "miriyum.platform-operator.reauthentication-fingerprint-secret="
                + "test-only-reauthentication-fingerprint-secret",
        "miriyum.platform-operator.temporary-password.validity=PT10M",
        "miriyum.platform-operator.temporary-password.max-failures=3",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.store.schedule.activation-enabled=false",
        "miriyum.reservation.hold-expiration.enabled=false",
        "miriyum.menu.schedule.enabled=false"
})
class OnboardingReviewIdempotencyIT {
    private static final String CASE_ID = "550e8400-e29b-41d4-a716-446655440277";
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440001";

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40");

    @Autowired OnboardingReviewCommandService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean StoreOnboardingReviewWorkflow workflow;
    @MockitoBean HighRiskCommandGuard guard;
    @MockitoBean AdminCaseAssignmentService assignments;
    @MockitoBean PlatformOperatorAuditWriter audit;

    private PlatformOperatorPrincipal principal;
    private DecisionRequest request;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE idempotency_commands");
        principal = new PlatformOperatorPrincipal(
                91L, "operator@example.com", "session", 1L, 1L, false);
        request = new DecisionRequest(ReviewDecisionAction.APPROVE, 1L, 2L, "APPROVED");
        var detail = new ReviewCaseDetail(
                CASE_ID, ReviewType.ONBOARDING, ReviewStatus.UNDER_REVIEW,
                41L, 1L, 2L, 91L, null);
        given(workflow.getReviewCase(CASE_ID)).willReturn(detail);
        given(guard.authorize(any())).willReturn(context());
        given(guard.authorizeInitialOnboardingAssignment(any())).willReturn(context());
        given(workflow.assign(any(), anyLong(), anyLong(), any()))
                .willReturn(new ReviewCaseDetail(
                        CASE_ID, ReviewType.ONBOARDING, ReviewStatus.UNDER_REVIEW,
                        41L, 1L, 3L, 91L, null));
        given(workflow.reassign(any(), anyLong(), anyLong(), anyLong(), any()))
                .willReturn(new ReviewCaseDetail(
                        CASE_ID, ReviewType.ONBOARDING, ReviewStatus.UNDER_REVIEW,
                        41L, 1L, 3L, 92L, null));
        given(workflow.decide(any())).willReturn(new ApplicationData(
                "41", 1L, ApplicationStatus.APPROVED, true, "WAIT", "7"));
    }

    @Test
    void assignmentReplaySkipsReauthenticationAndVersionDependentWork() {
        var request = new AssignmentRequest(2L);

        var first = service.assign(command("ONBOARDING_ASSIGN", "a".repeat(64)),
                principal, CASE_ID, request, "first-approval", "correlation");
        var replay = service.assign(command("ONBOARDING_ASSIGN", "a".repeat(64)),
                principal, CASE_ID, request, "already-consumed-approval", "correlation");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.data()).isEqualTo(first.data());
        verify(guard, times(1)).authorizeInitialOnboardingAssignment(any());
        verify(workflow, times(1)).assign(any(), anyLong(), anyLong(), any());
    }

    @Test
    void reassignmentReplaySkipsReauthenticationAndVersionDependentWork() {
        var request = new ReassignmentRequest(2L, 92L);

        var first = service.reassign(command("ONBOARDING_REASSIGN", "a".repeat(64)),
                principal, CASE_ID, request, "first-approval", "correlation");
        var replay = service.reassign(command("ONBOARDING_REASSIGN", "a".repeat(64)),
                principal, CASE_ID, request, "already-consumed-approval", "correlation");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.data()).isEqualTo(first.data());
        verify(guard, times(1)).authorize(any());
        verify(workflow, times(1)).reassign(any(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void sameKeyAndPayloadReplaysBeforeReauthenticationAndCaseVersionChecks() {
        var first = service.decide(command("ONBOARDING_DECIDE", "a".repeat(64)), principal, CASE_ID,
                request, "first-approval", "correlation");
        var replay = service.decide(command("ONBOARDING_DECIDE", "a".repeat(64)), principal, CASE_ID,
                request, "already-consumed-approval", "correlation");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.data()).isEqualTo(first.data());
        verify(guard, times(1)).authorize(any());
        verify(workflow, times(1)).decide(any());
    }

    @Test
    void sameKeyWithDifferentPayloadFingerprintIsRejectedWithoutBusinessReplay() {
        service.decide(command("ONBOARDING_DECIDE", "a".repeat(64)), principal, CASE_ID,
                request, "approval", "correlation");

        assertThatThrownBy(() -> service.decide(
                command("ONBOARDING_DECIDE", "b".repeat(64)), principal, CASE_ID,
                request, "approval", "correlation"))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        verify(guard, times(1)).authorize(any());
        verify(workflow, times(1)).decide(any());
    }

    private IdempotencyCommand command(String commandType, String fingerprint) {
        return new IdempotencyCommand(
                "platform-operator", principal.accountId(), commandType, KEY, fingerprint);
    }

    private static AdminAuditContext context() {
        return new AdminAuditContext(
                91L, Set.of(PlatformOperatorRole.ONBOARDING_REVIEWER),
                Set.of(PlatformOperatorPermission.ONBOARDING_REVIEW), 1L,
                AdminCaseType.ONBOARDING_REVIEW, CASE_ID, 2L,
                AdminCommandPurpose.ONBOARDING_DECISION,
                AdminTargetType.ONBOARDING_APPLICATION, "41",
                "approval-fingerprint", "correlation");
    }
}
