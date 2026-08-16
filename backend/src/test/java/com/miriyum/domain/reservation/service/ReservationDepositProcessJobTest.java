package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ReservationDepositProcessJobTest {

    @Test
    void claimsBeforeCommandsAndContinuesAfterAnIndividualFailure() {
        ReservationDepositProcessService processService =
                mock(ReservationDepositProcessService.class);
        ReservationDepositProcessCommandFacade commandFacade =
                mock(ReservationDepositProcessCommandFacade.class);
        ReservationDepositProcessService.Claim first =
                new ReservationDepositProcessService.Claim(91L, "worker-a", 1L);
        ReservationDepositProcessService.Claim second =
                new ReservationDepositProcessService.Claim(92L, "worker-a", 1L);
        given(processService.claimDue("worker-a", 10))
                .willReturn(List.of(first, second));
        given(commandFacade.reconcileClaimed(first))
                .willThrow(new IllegalStateException("individual failure"));
        given(commandFacade.reconcileClaimed(second)).willReturn(true);
        ReservationDepositProcessJob job = new ReservationDepositProcessJob(
                processService,
                commandFacade);

        int reconciled = job.runOnce("worker-a", 10);

        assertThat(reconciled).isEqualTo(1);
        InOrder order = inOrder(processService, commandFacade);
        order.verify(processService).claimDue("worker-a", 10);
        order.verify(commandFacade).reconcileClaimed(first);
        order.verify(commandFacade).reconcileClaimed(second);
    }
}
