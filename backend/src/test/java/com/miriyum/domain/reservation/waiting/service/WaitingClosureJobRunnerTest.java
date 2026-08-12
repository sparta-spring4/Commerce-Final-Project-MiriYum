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

    @Test
    void firstSchedulerInvocationRecoversStrandedWorkExactlyOnce() {
        given(closureService.claimPendingItems(100)).willReturn(List.of());
        WaitingClosureJobRunner runner = new WaitingClosureJobRunner(closureService);

        runner.processClosureBatch();
        runner.processClosureBatch();

        then(closureService).should().recoverStrandedWork();
        then(closureService).should(org.mockito.Mockito.times(2)).claimPendingItems(100);
    }

    @Test
    void wrappedMysqlDeadlockIsRetryableButPermanentFailureIsNot() {
        var deadlock = new org.springframework.dao.CannotAcquireLockException(
                "outer", new RuntimeException(new java.sql.SQLException("deadlock", "40001", 1213)));
        given(closureService.claimPendingItems(100)).willReturn(List.of(11L, 12L));
        org.mockito.Mockito.doThrow(deadlock).doNothing().when(closureService).processClaimedItem(11L);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("permanent"))
                .when(closureService).processClaimedItem(12L);
        WaitingClosureJobRunner runner = new WaitingClosureJobRunner(closureService);

        runner.processClosureBatch();

        then(closureService).should().recordFailure(11L, true);
        then(closureService).should().recordFailure(12L, false);
    }
}
