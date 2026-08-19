package com.miriyum.domain.platformoperator.controller.management.paymentrecovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.ApprovalRequest;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.AssignmentRequest;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.ClosureRequest;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.ProposalRequest;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.RequeryRequest;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.service.PaymentRecoveryCommandService;
import com.miriyum.domain.platformoperator.paymentrecovery.service.PaymentRecoveryQueryService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotentOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PaymentRecoveryControllerTest {
    private static final String CASE_ID = "550e8400-e29b-41d4-a716-446655440281";
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440282";
    @Mock PaymentRecoveryQueryService queries;
    @Mock PaymentRecoveryCommandService commands;

    @Test
    void uppercaseCaseIdIsNormalizedForEveryQueryAndCommand() {
        var controller = new PaymentRecoveryController(queries, commands);
        var principal = new PlatformOperatorPrincipal(
                11L, "operator@example.com", "session", 7L, 2L, false);
        String uppercaseCaseId = CASE_ID.toUpperCase(java.util.Locale.ROOT);
        var assignment = new AssignmentRequest(2L);
        var requery = new RequeryRequest(2L, 3L, 4L, 5L);
        var proposal = new ProposalRequest(RecoveryAction.RETRY_REFUND, 2L, 3L, 4L, 5L);
        var approval = new ApprovalRequest(4L, 1L);
        var closure = new ClosureRequest(4L);
        var outcome = new IdempotentOutcome(false, 200, "SUCCESS", "PAYMENT_RECOVERY_CASE",
                CASE_ID, new ObjectMapper().createObjectNode());
        when(commands.assign(any(), eq(principal), eq(CASE_ID), eq(assignment),
                eq("reauth"), eq("corr"))).thenReturn(outcome);
        when(commands.requery(any(), eq(principal), eq(CASE_ID), eq(requery),
                eq("reauth"), eq("corr"))).thenReturn(outcome);
        when(commands.propose(any(), eq(principal), eq(CASE_ID), eq(proposal),
                eq("reauth"), eq("corr"))).thenReturn(outcome);
        when(commands.approve(any(), eq(principal), eq(CASE_ID), eq(1L), eq(approval),
                eq("reauth"), eq("corr"))).thenReturn(outcome);
        when(commands.closeUnresolved(any(), eq(principal), eq(CASE_ID), eq(closure),
                eq("reauth"), eq("corr"))).thenReturn(outcome);

        controller.detail(principal, uppercaseCaseId);
        controller.assign(principal, uppercaseCaseId, KEY, "reauth", "corr", assignment);
        controller.requery(principal, uppercaseCaseId, KEY, "reauth", "corr", requery);
        controller.propose(principal, uppercaseCaseId, KEY, "reauth", "corr", proposal);
        controller.approve(principal, uppercaseCaseId, 1L, KEY, "reauth", "corr", approval);
        controller.close(principal, uppercaseCaseId, KEY, "reauth", "corr", closure);

        verify(queries).detail(principal, CASE_ID);
        verify(commands).assign(any(), eq(principal), eq(CASE_ID), eq(assignment),
                eq("reauth"), eq("corr"));
        verify(commands).requery(any(), eq(principal), eq(CASE_ID), eq(requery),
                eq("reauth"), eq("corr"));
        verify(commands).propose(any(), eq(principal), eq(CASE_ID), eq(proposal),
                eq("reauth"), eq("corr"));
        verify(commands).approve(any(), eq(principal), eq(CASE_ID), eq(1L), eq(approval),
                eq("reauth"), eq("corr"));
        verify(commands).closeUnresolved(any(), eq(principal), eq(CASE_ID), eq(closure),
                eq("reauth"), eq("corr"));
    }

    @Test
    void proposalForwardsAllVersionAndSecurityHeadersIntoOneIdempotentCommand() {
        var controller = new PaymentRecoveryController(queries, commands);
        var principal = new PlatformOperatorPrincipal(
                11L, "operator@example.com", "session", 7L, 2L, false);
        var request = new ProposalRequest(RecoveryAction.RETRY_REFUND, 2L, 3L, 4L, 5L);
        when(commands.propose(any(), eq(principal), eq(CASE_ID), eq(request),
                eq("one-time-approval"), eq("corr-281"))).thenReturn(new IdempotentOutcome(
                false, 201, "SUCCESS", "PAYMENT_RECOVERY_PROPOSAL", CASE_ID + ":1",
                new ObjectMapper().readTree("{\"proposalVersion\":1}")));

        var response = controller.propose(principal, CASE_ID, KEY,
                "one-time-approval", "corr-281", request);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        ArgumentCaptor<IdempotencyCommand> command = ArgumentCaptor.forClass(IdempotencyCommand.class);
        verify(commands).propose(command.capture(), eq(principal), eq(CASE_ID), eq(request),
                eq("one-time-approval"), eq("corr-281"));
        assertThat(command.getValue().idempotencyKey()).isEqualTo(KEY);
        assertThat(command.getValue().requestFingerprint()).hasSize(64);
        assertThat(command.getValue().toString()).doesNotContain("one-time-approval", "session");
    }
}
