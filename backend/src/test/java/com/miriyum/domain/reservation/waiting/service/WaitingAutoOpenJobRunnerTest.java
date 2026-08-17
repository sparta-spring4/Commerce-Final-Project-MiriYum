package com.miriyum.domain.reservation.waiting.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.reservation.waiting.config.WaitingAutoOpenProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class WaitingAutoOpenJobRunnerTest {

    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    @Test
    void pollInvalidatesPlansClaimsAndExecutesInOrder() {
        WaitingAutoOpenPlanner planner = mock(WaitingAutoOpenPlanner.class);
        WaitingAutoOpenService service = mock(WaitingAutoOpenService.class);
        WaitingAutoOpenMetrics metrics = mock(WaitingAutoOpenMetrics.class);
        WaitingAutoOpenClaim claim = claim();
        given(service.claimDue("worker-a", NOW, Duration.ofSeconds(30), 5))
                .willReturn(List.of(claim));
        given(service.execute(claim, NOW))
                .willReturn(WaitingAutoOpenService.ExecutionResult.COMPLETED);
        WaitingAutoOpenJobRunner runner = new WaitingAutoOpenJobRunner(
                planner,
                service,
                metrics,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        runner.poll();

        var order = inOrder(service, planner, metrics);
        order.verify(service).invalidateStale(NOW, 10);
        order.verify(planner).plan(NOW, Duration.ofHours(12), 20);
        order.verify(service).claimDue("worker-a", NOW, Duration.ofSeconds(30), 5);
        order.verify(service).execute(claim, NOW);
        order.verify(metrics).execution(WaitingAutoOpenService.ExecutionResult.COMPLETED);
        order.verifyNoMoreInteractions();
    }

    @Test
    void executionFailureIsRecordedWithSameFenceAndLaterClaimsContinue() {
        WaitingAutoOpenPlanner planner = mock(WaitingAutoOpenPlanner.class);
        WaitingAutoOpenService service = mock(WaitingAutoOpenService.class);
        WaitingAutoOpenMetrics metrics = mock(WaitingAutoOpenMetrics.class);
        WaitingAutoOpenClaim failed = claim();
        WaitingAutoOpenClaim later = new WaitingAutoOpenClaim(
                12L, 8L, "other-key", LocalDate.of(2026, 8, 17),
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), 1L, 60,
                "worker-a", 1L, 1, NOW.plusSeconds(30));
        RuntimeException failure = new RuntimeException("boom");
        given(service.claimDue("worker-a", NOW, Duration.ofSeconds(30), 5))
                .willReturn(List.of(failed, later));
        given(service.execute(failed, NOW)).willThrow(failure);
        given(service.execute(later, NOW))
                .willReturn(WaitingAutoOpenService.ExecutionResult.COMPLETED);
        WaitingAutoOpenJobRunner runner = new WaitingAutoOpenJobRunner(
                planner, service, metrics, properties(), Clock.fixed(NOW, ZoneOffset.UTC));

        runner.poll();

        var order = inOrder(service, metrics);
        order.verify(service).execute(failed, NOW);
        order.verify(service).recordFailure(
                failed, NOW, failure, 4, Duration.ofSeconds(2), Duration.ofMinutes(1));
        order.verify(metrics).failure(failure);
        order.verify(service).execute(later, NOW);
        order.verify(metrics).execution(WaitingAutoOpenService.ExecutionResult.COMPLETED);
    }

    @Test
    void pollRefreshesTimeAtEachClaimExecutionAndFailureBoundary() {
        Instant claimAt = NOW.plusSeconds(1);
        Instant firstExecutionAt = NOW.plusSeconds(2);
        Instant failureAt = NOW.plusSeconds(3);
        Instant laterExecutionAt = NOW.plusSeconds(31);
        WaitingAutoOpenPlanner planner = mock(WaitingAutoOpenPlanner.class);
        WaitingAutoOpenService service = mock(WaitingAutoOpenService.class);
        WaitingAutoOpenMetrics metrics = mock(WaitingAutoOpenMetrics.class);
        Clock clock = mock(Clock.class);
        WaitingAutoOpenClaim failed = claim();
        WaitingAutoOpenClaim later = new WaitingAutoOpenClaim(
                12L, 8L, "other-key", LocalDate.of(2026, 8, 17),
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), 1L, 60,
                "worker-a", 1L, 1, NOW.plusSeconds(30));
        RuntimeException failure = new RuntimeException("boom");
        given(clock.instant()).willReturn(
                NOW, claimAt, firstExecutionAt, failureAt, laterExecutionAt);
        given(service.claimDue("worker-a", claimAt, Duration.ofSeconds(30), 5))
                .willReturn(List.of(failed, later));
        given(service.execute(failed, firstExecutionAt)).willThrow(failure);
        given(service.execute(later, laterExecutionAt))
                .willReturn(WaitingAutoOpenService.ExecutionResult.STALE_CLAIM);
        WaitingAutoOpenJobRunner runner = new WaitingAutoOpenJobRunner(
                planner, service, metrics, properties(), clock);

        runner.poll();

        var order = inOrder(service, planner, metrics);
        order.verify(service).invalidateStale(NOW, 10);
        order.verify(planner).plan(NOW, Duration.ofHours(12), 20);
        order.verify(service).claimDue("worker-a", claimAt, Duration.ofSeconds(30), 5);
        order.verify(service).execute(failed, firstExecutionAt);
        order.verify(service).recordFailure(
                failed, failureAt, failure, 4, Duration.ofSeconds(2), Duration.ofMinutes(1));
        order.verify(metrics).failure(failure);
        order.verify(service).execute(later, laterExecutionAt);
        order.verify(metrics).execution(WaitingAutoOpenService.ExecutionResult.STALE_CLAIM);
        order.verifyNoMoreInteractions();
    }

    private static WaitingAutoOpenProperties properties() {
        return new WaitingAutoOpenProperties(
                "worker-a",
                Duration.ofHours(12),
                20,
                5,
                Duration.ofSeconds(30),
                4,
                Duration.ofSeconds(2),
                Duration.ofMinutes(1),
                10,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10));
    }

    private static WaitingAutoOpenClaim claim() {
        return new WaitingAutoOpenClaim(
                11L, 7L, "interval-key", LocalDate.of(2026, 8, 17),
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), 1L, 60,
                "worker-a", 1L, 1, NOW.plusSeconds(30));
    }
}
