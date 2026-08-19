package com.miriyum.domain.platformoperator.controller.management.paymentrecovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.ProposalRequest;
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
