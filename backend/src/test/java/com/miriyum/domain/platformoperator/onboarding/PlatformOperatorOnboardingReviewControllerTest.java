package com.miriyum.domain.platformoperator.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.platformoperator.controller.management.onboarding.PlatformOperatorOnboardingReviewController;
import com.miriyum.domain.platformoperator.onboarding.dto.OnboardingReviewRequests.AssignmentRequest;
import com.miriyum.domain.platformoperator.onboarding.dto.OnboardingReviewRequests.DecisionRequest;
import com.miriyum.domain.platformoperator.onboarding.dto.OnboardingReviewRequests.ReassignmentRequest;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingReviewCommandService;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingReviewQueryService;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingEvidenceAccessService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewDecisionAction;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotentOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class PlatformOperatorOnboardingReviewControllerTest {
    private static final String CASE_ID = "550e8400-e29b-41d4-a716-446655440277";
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440001";

    @Test
    void rejectsNonUuidPublicCaseIdBeforeQuery() {
        var controller = new PlatformOperatorOnboardingReviewController(
                Mockito.mock(OnboardingReviewQueryService.class),
                Mockito.mock(OnboardingReviewCommandService.class),
                Mockito.mock(OnboardingEvidenceAccessService.class));

        assertThatThrownBy(() -> controller.detail(null, "internal-sequence-41"))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void assignmentPayloadProducesStableAndCompleteIdempotencyFingerprint() {
        var commands = Mockito.mock(OnboardingReviewCommandService.class);
        var controller = controller(commands);
        var principal = principal();
        when(commands.assign(any(), eq(principal), eq(CASE_ID), any(),
                eq("reauth"), eq("correlation"))).thenReturn(outcome());

        controller.assign(principal, CASE_ID, KEY, "reauth", "correlation", new AssignmentRequest(2L));
        controller.assign(principal, CASE_ID, KEY, "reauth", "correlation", new AssignmentRequest(2L));
        controller.assign(principal, CASE_ID, KEY, "reauth", "correlation", new AssignmentRequest(3L));

        ArgumentCaptor<IdempotencyCommand> command = ArgumentCaptor.forClass(IdempotencyCommand.class);
        verify(commands, Mockito.times(3)).assign(command.capture(), eq(principal),
                eq(CASE_ID), any(), eq("reauth"), eq("correlation"));
        assertStableThenChanged(command, "ONBOARDING_ASSIGN");
    }

    @Test
    void reassignmentPayloadProducesStableAndCompleteIdempotencyFingerprint() {
        var commands = Mockito.mock(OnboardingReviewCommandService.class);
        var controller = controller(commands);
        var principal = principal();
        when(commands.reassign(any(), eq(principal), eq(CASE_ID), any(),
                eq("reauth"), eq("correlation"))).thenReturn(outcome());

        controller.reassign(principal, CASE_ID, KEY, "reauth", "correlation",
                new ReassignmentRequest(2L, 92L));
        controller.reassign(principal, CASE_ID, KEY, "reauth", "correlation",
                new ReassignmentRequest(2L, 92L));
        controller.reassign(principal, CASE_ID, KEY, "reauth", "correlation",
                new ReassignmentRequest(3L, 92L));
        controller.reassign(principal, CASE_ID, KEY, "reauth", "correlation",
                new ReassignmentRequest(2L, 93L));

        ArgumentCaptor<IdempotencyCommand> command = ArgumentCaptor.forClass(IdempotencyCommand.class);
        verify(commands, Mockito.times(4)).reassign(command.capture(), eq(principal),
                eq(CASE_ID), any(), eq("reauth"), eq("correlation"));
        assertFingerprintCoverage(command, "ONBOARDING_REASSIGN");
    }

    @Test
    void decisionPayloadProducesStableAndCompleteIdempotencyFingerprint() {
        var queries = Mockito.mock(OnboardingReviewQueryService.class);
        var commands = Mockito.mock(OnboardingReviewCommandService.class);
        var controller = new PlatformOperatorOnboardingReviewController(
                queries, commands, Mockito.mock(OnboardingEvidenceAccessService.class));
        var principal = principal();
        var request = new DecisionRequest(ReviewDecisionAction.APPROVE, 1L, 2L, "APPROVED");
        when(commands.decide(any(), eq(principal), eq(CASE_ID), any(),
                eq("reauth"), eq("correlation"))).thenReturn(outcome());

        controller.decide(principal, CASE_ID, KEY, "reauth", "correlation", request);
        controller.decide(principal, CASE_ID, KEY, "reauth", "correlation", request);
        controller.decide(principal, CASE_ID, KEY, "reauth", "correlation",
                new DecisionRequest(ReviewDecisionAction.REJECT, 1L, 2L, "APPROVED"));
        controller.decide(principal, CASE_ID, KEY, "reauth", "correlation",
                new DecisionRequest(ReviewDecisionAction.APPROVE, 2L, 2L, "APPROVED"));
        controller.decide(principal, CASE_ID, KEY, "reauth", "correlation",
                new DecisionRequest(ReviewDecisionAction.APPROVE, 1L, 3L, "APPROVED"));
        controller.decide(principal, CASE_ID, KEY, "reauth", "correlation",
                new DecisionRequest(ReviewDecisionAction.APPROVE, 1L, 2L, "REVIEW_APPROVED"));

        ArgumentCaptor<IdempotencyCommand> command = ArgumentCaptor.forClass(IdempotencyCommand.class);
        verify(commands, Mockito.times(6)).decide(command.capture(), eq(principal),
                eq(CASE_ID), any(), eq("reauth"), eq("correlation"));
        assertFingerprintCoverage(command, "ONBOARDING_DECIDE");
    }

    private static void assertStableThenChanged(
            ArgumentCaptor<IdempotencyCommand> command, String commandType) {
        assertThat(command.getAllValues()).extracting(IdempotencyCommand::commandType)
                .containsOnly(commandType);
        assertThat(command.getAllValues().get(0).requestFingerprint())
                .isEqualTo(command.getAllValues().get(1).requestFingerprint());
        assertThat(command.getAllValues().get(2).requestFingerprint())
                .isNotEqualTo(command.getAllValues().get(0).requestFingerprint());
        assertThat(command.getAllValues().getFirst().toString())
                .doesNotContain("reauth", "correlation", "session");
    }

    private static void assertFingerprintCoverage(
            ArgumentCaptor<IdempotencyCommand> command, String commandType) {
        assertStableThenChanged(command, commandType);
        assertThat(command.getAllValues().subList(2, command.getAllValues().size()))
                .extracting(IdempotencyCommand::requestFingerprint)
                .doesNotHaveDuplicates()
                .doesNotContain(command.getAllValues().getFirst().requestFingerprint());
    }

    private static PlatformOperatorOnboardingReviewController controller(
            OnboardingReviewCommandService commands) {
        return new PlatformOperatorOnboardingReviewController(
                Mockito.mock(OnboardingReviewQueryService.class), commands,
                Mockito.mock(OnboardingEvidenceAccessService.class));
    }

    private static PlatformOperatorPrincipal principal() {
        return new PlatformOperatorPrincipal(
                91L, "operator@example.com", "session", 1L, 1L, false);
    }

    private static IdempotentOutcome outcome() {
        return new IdempotentOutcome(false, 200, "SUCCESS",
                "STORE_ONBOARDING_APPLICATION", "41", new ObjectMapper().createObjectNode());
    }
}
