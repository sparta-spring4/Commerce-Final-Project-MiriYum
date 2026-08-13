package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.scheduling.config.ScheduledTaskHolder;

@ExtendWith(MockitoExtension.class)
class WaitingClosureJobRunnerTest {
    @Mock WaitingClosureService closureService;

    @Test
    void applicationDefaultsDoNotRegisterClosureSchedulingOrRunner() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME))
                .withBean(WaitingClosureService.class, () -> mock(WaitingClosureService.class))
                .withUserConfiguration(ClosureSchedulingTestConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(WaitingClosureScheduleConfig.class)
                            .doesNotHaveBean(WaitingClosureJobRunner.class);
                });
    }

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
    void enabledWaitingClosureRegistersScheduledTaskWithoutOtherSchedulers() {
        new ApplicationContextRunner()
                .withBean(WaitingClosureService.class, () -> mock(WaitingClosureService.class))
                .withUserConfiguration(
                        WaitingClosureScheduleConfig.class,
                        WaitingClosureJobRunner.class)
                .withPropertyValues(
                        "miriyum.waiting.closure.enabled=true",
                        "miriyum.waiting.closure.initial-delay-ms=3600000",
                        "miriyum.waiting.closure.fixed-delay-ms=3600000")
                .run(context -> {
                    assertThat(context).hasSingleBean(WaitingClosureJobRunner.class);
                    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks())
                            .hasSize(1);
                });
    }

    @Test
    void runnerClaimsEachItemImmediatelyBeforeProcessing() {
        WaitingClosureClaim first = new WaitingClosureClaim(11L, "runner-a", 3L);
        WaitingClosureClaim second = new WaitingClosureClaim(12L, "runner-a", 1L);
        given(closureService.claimPendingItems("runner-a", 1, Duration.ofSeconds(30), 0L))
                .willReturn(List.of(first));
        given(closureService.claimPendingItems("runner-a", 1, Duration.ofSeconds(30), 11L))
                .willReturn(List.of(second));
        given(closureService.claimPendingItems("runner-a", 1, Duration.ofSeconds(30), 12L))
                .willReturn(List.of());
        WaitingClosureJobRunner runner = new WaitingClosureJobRunner(
                closureService, "runner-a", Duration.ofSeconds(30));

        runner.processClosureBatch();

        var order = org.mockito.Mockito.inOrder(closureService);
        order.verify(closureService).claimPendingItems("runner-a", 1, Duration.ofSeconds(30), 0L);
        order.verify(closureService).processClaimedItem(first);
        order.verify(closureService).claimPendingItems("runner-a", 1, Duration.ofSeconds(30), 11L);
        order.verify(closureService).processClaimedItem(second);
        order.verify(closureService).claimPendingItems("runner-a", 1, Duration.ofSeconds(30), 12L);
        order.verifyNoMoreInteractions();
    }

    @Test
    void runnerProcessesAtMostOneHundredItemsPerPoll() {
        WaitingClosureClaim claim = new WaitingClosureClaim(11L, "runner-a", 3L);
        given(closureService.claimPendingItems(
                org.mockito.ArgumentMatchers.eq("runner-a"),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(30)),
                org.mockito.ArgumentMatchers.anyLong()))
                .willReturn(List.of(claim));
        WaitingClosureJobRunner runner = new WaitingClosureJobRunner(
                closureService, "runner-a", Duration.ofSeconds(30));

        runner.processClosureBatch();

        then(closureService).should(times(100))
                .claimPendingItems(
                        org.mockito.ArgumentMatchers.eq("runner-a"),
                        org.mockito.ArgumentMatchers.eq(1),
                        org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(30)),
                        org.mockito.ArgumentMatchers.anyLong());
        then(closureService).should(times(100)).processClaimedItem(claim);
    }

    @Test
    void wrappedMysqlDeadlockRequeuesUsingSameFence() {
        WaitingClosureClaim claim = new WaitingClosureClaim(11L, "runner-a", 3L);
        var deadlock = new RuntimeException(new java.sql.SQLException("deadlock", "40001", 1213));
        given(closureService.claimPendingItems("runner-a", 1, Duration.ofSeconds(30), 0L))
                .willReturn(List.of(claim));
        given(closureService.claimPendingItems("runner-a", 1, Duration.ofSeconds(30), 11L))
                .willReturn(List.of());
        org.mockito.Mockito.doThrow(deadlock).when(closureService).processClaimedItem(claim);
        WaitingClosureJobRunner runner = new WaitingClosureJobRunner(
                closureService, "runner-a", Duration.ofSeconds(30));

        runner.processClosureBatch();

        then(closureService).should().recordFailure(claim, true);
    }

    @Test
    void retryableFailureIsDeferredUntilNextPollWhileLaterItemContinues() {
        WaitingClosureClaim failed = new WaitingClosureClaim(11L, "runner-a", 3L);
        WaitingClosureClaim later = new WaitingClosureClaim(12L, "runner-a", 1L);
        var deadlock = new RuntimeException(new java.sql.SQLException("deadlock", "40001", 1213));
        given(closureService.claimPendingItems(
                "runner-a", 1, Duration.ofSeconds(30), 0L))
                .willReturn(List.of(failed));
        given(closureService.claimPendingItems(
                "runner-a", 1, Duration.ofSeconds(30), 11L))
                .willReturn(List.of(later));
        given(closureService.claimPendingItems(
                "runner-a", 1, Duration.ofSeconds(30), 12L))
                .willReturn(List.of());
        org.mockito.Mockito.doThrow(deadlock)
                .when(closureService).processClaimedItem(failed);
        WaitingClosureJobRunner runner = new WaitingClosureJobRunner(
                closureService, "runner-a", Duration.ofSeconds(30));

        runner.processClosureBatch();

        var order = org.mockito.Mockito.inOrder(closureService);
        order.verify(closureService).claimPendingItems(
                "runner-a", 1, Duration.ofSeconds(30), 0L);
        order.verify(closureService).processClaimedItem(failed);
        order.verify(closureService).recordFailure(failed, true);
        order.verify(closureService).claimPendingItems(
                "runner-a", 1, Duration.ofSeconds(30), 11L);
        order.verify(closureService).processClaimedItem(later);
        order.verify(closureService).claimPendingItems(
                "runner-a", 1, Duration.ofSeconds(30), 12L);
        order.verifyNoMoreInteractions();
    }

    @Configuration(proxyBeanMethods = false)
    @Import({WaitingClosureScheduleConfig.class, WaitingClosureJobRunner.class})
    static class ClosureSchedulingTestConfig {
    }
}
