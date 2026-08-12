package com.miriyum.domain.reservation.waiting.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WaitingClosureJobRunnerTest {

    @Mock WaitingClosureService closureService;

    @Test
    void schedulerClaimsAtMostOneHundredItemsAndProcessesEachSeparately() {
        given(closureService.claimPendingItems(100)).willReturn(List.of(11L, 12L));
        WaitingClosureJobRunner runner = new WaitingClosureJobRunner(closureService);

        runner.processClosureBatch();

        InOrder order = org.mockito.Mockito.inOrder(closureService);
        order.verify(closureService).claimPendingItems(100);
        order.verify(closureService).processClaimedItem(11L);
        order.verify(closureService).processClaimedItem(12L);
    }
}
