package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.waiting.entity.WaitingConversionCompensation;
import com.miriyum.domain.reservation.waiting.entity.WaitingConversionCompensationStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingConversionCompensationRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WaitingConversionCompensationServiceTest {
    private static final Instant BASE_TIME = Instant.parse("2026-08-14T00:00:00Z");

    @Mock WaitingConversionCompensationRepository repository;
    @Mock PaymentService paymentService;
    private MutableClock clock;
    private WaitingConversionCompensationService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(BASE_TIME);
        service = new WaitingConversionCompensationService(
                repository, paymentService, clock, new TestTransactionManager());
    }

    @Test
    void defaultConfigurationConstructsAndRegistersCompensationRunner() {
        new ApplicationContextRunner()
                .withBean(
                        WaitingConversionCompensationService.class,
                        () -> mock(WaitingConversionCompensationService.class))
                .withUserConfiguration(WaitingConversionCompensationRunner.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WaitingConversionCompensationRunner.class);
                });
    }

    @Test
    void compensationWorkerUsesDedicatedSingleThreadScheduler() throws NoSuchMethodException {
        Scheduled scheduled = WaitingConversionCompensationRunner.class
                .getMethod("processBatch")
                .getAnnotation(Scheduled.class);
        Scheduled reconciliationScheduled = WaitingConversionCompensationRunner.class
                .getMethod("reportReconciliationBacklog")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.scheduler())
                .isEqualTo("waitingConversionCompensationTaskScheduler");
        assertThat(reconciliationScheduled.scheduler())
                .isEqualTo("waitingConversionCompensationTaskScheduler");

        new ApplicationContextRunner()
                .withBean(
                        WaitingConversionCompensationService.class,
                        () -> mock(WaitingConversionCompensationService.class))
                .withUserConfiguration(
                        WaitingConversionCompensationSchedulingConfig.class,
                        WaitingConversionCompensationRunner.class)
                .withPropertyValues(
                        "miriyum.waiting.compensation.initial-delay-ms=60000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasBean("waitingConversionCompensationTaskScheduler");
                    ThreadPoolTaskScheduler scheduler = context.getBean(
                            "waitingConversionCompensationTaskScheduler",
                            ThreadPoolTaskScheduler.class);
                    assertThat(scheduler.getPoolSize()).isEqualTo(1);
                    assertThat(scheduler.getThreadNamePrefix())
                            .isEqualTo("waiting-conversion-compensation-");
                    AbstractBeanDefinition schedulerDefinition =
                            (AbstractBeanDefinition) context.getBeanFactory()
                                    .getBeanDefinition(
                                            "waitingConversionCompensationTaskScheduler");
                    assertThat(schedulerDefinition.isDefaultCandidate()).isFalse();
                });
    }

    @Test
    void compensationSchedulerInterruptsRunningWorkOnShutdown() {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        new ApplicationContextRunner()
                .withUserConfiguration(WaitingConversionCompensationSchedulingConfig.class)
                .run(context -> {
                    ThreadPoolTaskScheduler scheduler = context.getBean(
                            "waitingConversionCompensationTaskScheduler",
                            ThreadPoolTaskScheduler.class);
                    scheduler.execute(() -> {
                        started.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException exception) {
                            interrupted.countDown();
                            Thread.currentThread().interrupt();
                        }
                    });

                    assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
                    try {
                        context.close();
                        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
                    } finally {
                        release.countDown();
                    }
                });
    }

    @Test
    void compensationWorkerOffSwitchDisablesRunnerAndScheduler() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withBean(
                        WaitingConversionCompensationService.class,
                        () -> mock(WaitingConversionCompensationService.class))
                .withUserConfiguration(
                        WaitingConversionCompensationSchedulingConfig.class,
                        WaitingConversionCompensationRunner.class)
                .withPropertyValues("MIRIYUM_WAITING_COMPENSATION_ENABLED=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean("waitingConversionCompensationTaskScheduler");
                    assertThat(context)
                            .doesNotHaveBean("waitingConversionCompensationRunner");
                });
    }

    @Test
    void compensationWorkerPropertiesReachProductionDeployment() throws Exception {
        String application = Files.readString(Path.of("src", "main", "resources", "application.yml"));
        String environment = Files.readString(Path.of("..", "deploy", ".env.example"));
        String compose = Files.readString(Path.of("..", "deploy", "docker-compose.prod.yml"));

        assertThat(application)
                .contains("enabled: ${MIRIYUM_WAITING_COMPENSATION_ENABLED:true}")
                .contains("fixed-delay-ms: ${MIRIYUM_WAITING_COMPENSATION_FIXED_DELAY_MS:5000}")
                .contains("initial-delay-ms: ${MIRIYUM_WAITING_COMPENSATION_INITIAL_DELAY_MS:5000}")
                .contains("reconciliation-delay-ms: ${MIRIYUM_WAITING_COMPENSATION_RECONCILIATION_DELAY_MS:60000}");
        assertThat(environment)
                .contains("MIRIYUM_WAITING_COMPENSATION_ENABLED=true")
                .contains("MIRIYUM_WAITING_COMPENSATION_FIXED_DELAY_MS=5000")
                .contains("MIRIYUM_WAITING_COMPENSATION_INITIAL_DELAY_MS=5000")
                .contains("MIRIYUM_WAITING_COMPENSATION_RECONCILIATION_DELAY_MS=60000");
        assertThat(compose)
                .contains("MIRIYUM_WAITING_COMPENSATION_ENABLED: ${MIRIYUM_WAITING_COMPENSATION_ENABLED:-true}")
                .contains("MIRIYUM_WAITING_COMPENSATION_FIXED_DELAY_MS: ${MIRIYUM_WAITING_COMPENSATION_FIXED_DELAY_MS:-5000}")
                .contains("MIRIYUM_WAITING_COMPENSATION_INITIAL_DELAY_MS: ${MIRIYUM_WAITING_COMPENSATION_INITIAL_DELAY_MS:-5000}")
                .contains("MIRIYUM_WAITING_COMPENSATION_RECONCILIATION_DELAY_MS: ${MIRIYUM_WAITING_COMPENSATION_RECONCILIATION_DELAY_MS:-60000}");
    }

    @Test
    void recordRequiredReplaysExactPayloadAndRejectsConflictWithoutOverwrite() {
        WaitingConversionCompensation existing = pending();
        given(repository.findByWaitingTeamIdAndPaymentIdForUpdate(11L, "101"))
                .willReturn(Optional.of(existing));

        assertThat(recordRequired(12_000L)).isEqualTo(71L);
        assertThatThrownBy(() -> recordRequired(12_001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting");
        assertThat(existing.getRefundAmountMinor()).isEqualTo(12_000L);
    }

    @Test
    void claimPendingClaimsDueRowsWithOwnerLeaseAndMonotonicFence() {
        WaitingConversionCompensation work = pending();
        given(repository.findClaimableForUpdate(
                WaitingConversionCompensationStatus.PENDING.name(),
                WaitingConversionCompensationStatus.PROCESSING.name(),
                BASE_TIME,
                0L,
                1))
                .willReturn(List.of(work));

        List<WaitingCompensationClaim> claims = service.claimPending(
                "worker-a", 1, Duration.ofSeconds(30), 0L);

        assertThat(claims).singleElement().satisfies(claim -> {
            assertThat(claim.owner()).isEqualTo("worker-a");
            assertThat(claim.token()).isEqualTo(1L);
            assertThat(claim.paymentId()).isEqualTo("101");
        });
        assertThat(work.getStatus()).isEqualTo(WaitingConversionCompensationStatus.PROCESSING);
    }

    @Test
    void completedRefundCallsPaymentOutsideTransactionThenCompletesCurrentClaim() {
        WaitingConversionCompensation work = claimed("worker-a");
        WaitingCompensationClaim claim = WaitingCompensationClaim.from(work, "worker-a");
        given(repository.findByIdForUpdate(71L)).willReturn(Optional.of(work));
        given(paymentService.requestRefund(claim.toRefundCommand())).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return result(RefundStatus.COMPLETED);
        });

        assertThat(service.processClaim(claim)).isTrue();

        assertThat(work.getStatus()).isEqualTo(WaitingConversionCompensationStatus.COMPLETED);
        then(paymentService).should().requestRefund(claim.toRefundCommand());
    }

    @Test
    void providerUnknownOrPaymentReconciliationRequiresCompensationReconciliation() {
        WaitingConversionCompensation work = claimed("worker-a");
        WaitingCompensationClaim claim = WaitingCompensationClaim.from(work, "worker-a");
        given(repository.findByIdForUpdate(71L)).willReturn(Optional.of(work));
        given(paymentService.requestRefund(claim.toRefundCommand()))
                .willReturn(result(RefundStatus.RECONCILIATION_REQUIRED));

        assertThat(service.processClaim(claim)).isTrue();
        assertThat(work.getStatus())
                .isEqualTo(WaitingConversionCompensationStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    void definiteFailedRefundRequeuesOnlyUntilBoundedAttemptLimit() {
        WaitingConversionCompensation work = claimed("worker-a");
        given(repository.findByIdForUpdate(71L)).willReturn(Optional.of(work));
        given(paymentService.requestRefund(org.mockito.ArgumentMatchers.any()))
                .willReturn(result(RefundStatus.FAILED));

        assertThat(service.processClaim(WaitingCompensationClaim.from(work, "worker-a"))).isTrue();
        assertThat(work.getStatus()).isEqualTo(WaitingConversionCompensationStatus.PENDING);

        clock.advance(Duration.ofSeconds(6));
        work.claim("worker-b", clock.instant(), clock.instant().plusSeconds(30));
        assertThat(service.processClaim(WaitingCompensationClaim.from(work, "worker-b"))).isTrue();
        assertThat(work.getStatus()).isEqualTo(WaitingConversionCompensationStatus.PENDING);

        clock.advance(Duration.ofSeconds(6));
        work.claim("worker-c", clock.instant(), clock.instant().plusSeconds(30));
        assertThat(service.processClaim(WaitingCompensationClaim.from(work, "worker-c"))).isTrue();
        assertThat(work.getStatus())
                .isEqualTo(WaitingConversionCompensationStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    void staleTokenCannotCallPaymentCompleteRequeueOrReconcile() {
        WaitingConversionCompensation work = claimed("worker-a");
        WaitingCompensationClaim stale = WaitingCompensationClaim.from(work, "worker-a");
        clock.advance(Duration.ofSeconds(31));
        work.claim("worker-b", clock.instant(), clock.instant().plusSeconds(30));
        given(repository.findByIdForUpdate(71L)).willReturn(Optional.of(work));

        assertThat(service.processClaim(stale)).isFalse();
        assertThat(service.recordFailure(stale, true)).isFalse();
        assertThat(service.recordFailure(stale, false)).isFalse();
        assertThat(work.getStatus()).isEqualTo(WaitingConversionCompensationStatus.PROCESSING);
        assertThat(work.getLeaseOwner()).isEqualTo("worker-b");
        then(paymentService).shouldHaveNoInteractions();
    }

    @Test
    void runnerTurnsRetryableInfrastructureExceptionIntoBoundedFailureRecording() {
        WaitingConversionCompensationService workerService =
                mock(WaitingConversionCompensationService.class);
        WaitingCompensationClaim claim = WaitingCompensationClaim.from(claimed("worker-a"), "worker-a");
        RuntimeException deadlock = new RuntimeException(
                new java.sql.SQLException("deadlock", "40001", 1213));
        given(workerService.processClaim(claim)).willThrow(deadlock);
        WaitingConversionCompensationRunner runner = new WaitingConversionCompensationRunner(
                workerService, "worker-a", Duration.ofSeconds(30));

        runner.processSafely(claim);

        then(workerService).should().recordFailure(claim, true);
    }

    @ParameterizedTest
    @EnumSource(
            value = CommonErrorCode.class,
            names = {"SERVICE_UNAVAILABLE", "CONCURRENT_MODIFICATION"})
    void runnerRetriesTransientServiceErrors(CommonErrorCode errorCode) {
        WaitingConversionCompensationService workerService =
                mock(WaitingConversionCompensationService.class);
        WaitingCompensationClaim claim = WaitingCompensationClaim.from(
                claimed("worker-a"), "worker-a");
        given(workerService.processClaim(claim))
                .willThrow(new ServiceException(errorCode));
        WaitingConversionCompensationRunner runner =
                new WaitingConversionCompensationRunner(
                        workerService, "worker-a", Duration.ofSeconds(30));

        runner.processSafely(claim);

        then(workerService).should().recordFailure(claim, true);
    }

    @Test
    void runnerLogsServiceFailureClassification(CapturedOutput output) {
        WaitingConversionCompensationService workerService =
                mock(WaitingConversionCompensationService.class);
        WaitingCompensationClaim claim = WaitingCompensationClaim.from(
                claimed("worker-a"), "worker-a");
        given(workerService.processClaim(claim))
                .willThrow(new ServiceException(CommonErrorCode.VALIDATION_FAILED));
        WaitingConversionCompensationRunner runner =
                new WaitingConversionCompensationRunner(
                        workerService, "worker-a", Duration.ofSeconds(30));

        runner.processSafely(claim);

        then(workerService).should().recordFailure(claim, false);
        assertThat(output)
                .contains("event=waiting_conversion_compensation_failed")
                .contains("compensation_id=71")
                .contains("retryable=false")
                .contains("error_code=COMMON_001");
    }

    @Test
    void runnerReportsOnlyPositiveReconciliationAggregate(CapturedOutput output) {
        WaitingConversionCompensationService workerService =
                mock(WaitingConversionCompensationService.class);
        given(workerService.countReconciliationRequired())
                .willReturn(0L, 3L);
        WaitingConversionCompensationRunner runner =
                new WaitingConversionCompensationRunner(
                        workerService, "worker-a", Duration.ofSeconds(30));

        assertThat(runner.reportReconciliationBacklog()).isZero();
        assertThat(runner.reportReconciliationBacklog()).isEqualTo(3L);

        assertThat(output)
                .contains("event=waiting_conversion_compensation_reconciliation_required")
                .contains("pending_count=3")
                .doesNotContain("compensation_id=");
    }

    private long recordRequired(long amountMinor) {
        return service.recordRequired(
                11L, "101", amountMinor, "KRW", 3L,
                "waiting-conversion-cancelled:11",
                "550e8400-e29b-41d4-a716-446655440301",
                "WAITING_CANCELLED");
    }

    private WaitingConversionCompensation claimed(String owner) {
        WaitingConversionCompensation work = pending();
        work.claim(owner, clock.instant(), clock.instant().plusSeconds(30));
        return work;
    }

    private static WaitingConversionCompensation pending() {
        WaitingConversionCompensation work = WaitingConversionCompensation.pending(
                11L, "101", 12_000L, "KRW", 3L,
                "waiting-conversion-cancelled:11",
                "550e8400-e29b-41d4-a716-446655440301",
                "WAITING_CANCELLED", BASE_TIME);
        ReflectionTestUtils.setField(work, "id", 71L);
        return work;
    }

    private static RefundResult result(RefundStatus status) {
        boolean completed = status == RefundStatus.COMPLETED;
        return new RefundResult(
                "91", "101", 12_000L,
                completed ? 12_000L : 0L,
                completed ? 12_000L : 0L,
                completed ? 0L : 12_000L,
                "KRW", status, BASE_TIME, completed ? BASE_TIME : null);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
