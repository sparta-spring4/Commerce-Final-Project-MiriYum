package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@ExtendWith(MockitoExtension.class)
class WaitingClosureJobRunnerTest {
    @Mock WaitingClosureService closureService;

    @Test
    void disabledPropertyDoesNotRegisterClosureRunner() {
        new ApplicationContextRunner()
                .withBean(WaitingClosureService.class, () -> mock(WaitingClosureService.class))
                .withUserConfiguration(WaitingClosureJobRunner.class)
                .withPropertyValues("miriyum.waiting.closure.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(WaitingClosureJobRunner.class));
    }

    @Test
    void runnerUsesStableOwnerAndFencedClaim() {
        WaitingClosureClaim claim = new WaitingClosureClaim(11L, "runner-a", 3L);
        given(closureService.claimPendingItems("runner-a", 100, Duration.ofSeconds(30)))
                .willReturn(List.of(claim));
        WaitingClosureJobRunner runner = new WaitingClosureJobRunner(
                closureService, "runner-a", Duration.ofSeconds(30));

        runner.processClosureBatch();

        then(closureService).should().processClaimedItem(claim);
    }

    @Test
    void wrappedMysqlDeadlockRequeuesUsingSameFence() {
        WaitingClosureClaim claim = new WaitingClosureClaim(11L, "runner-a", 3L);
        var deadlock = new RuntimeException(new java.sql.SQLException("deadlock", "40001", 1213));
        given(closureService.claimPendingItems("runner-a", 100, Duration.ofSeconds(30)))
                .willReturn(List.of(claim));
        org.mockito.Mockito.doThrow(deadlock).when(closureService).processClaimedItem(claim);
        WaitingClosureJobRunner runner = new WaitingClosureJobRunner(
                closureService, "runner-a", Duration.ofSeconds(30));

        runner.processClosureBatch();

        then(closureService).should().recordFailure(claim, true);
    }
}
