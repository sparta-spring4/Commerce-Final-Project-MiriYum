package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.miriyum.domain.payment.dto.PaymentContracts.ApplyReservationDepositDispositionCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionResult;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.GetReservationDepositDispositionQuery;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.config.ReservationDepositProcessConfig;
import com.miriyum.domain.reservation.entity.ReservationDepositDispositionObligation;
import com.miriyum.domain.reservation.repository.ReservationDepositDispositionObligationRepository;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

class ReservationDepositDispositionJobTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);
    private static final Duration QUERY_DELAY = Duration.ofSeconds(30);

    @Test
    void missingEnablementKeepsDispositionWorkerAndSchedulerDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(ReservationDepositProcessConfig.class)
                .withBean(
                        ReservationDepositDispositionJob.class,
                        () -> mock(ReservationDepositDispositionJob.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            ReservationDepositProcessConfig
                                    .DispositionScheduledWorker.class);
                    assertThat(context).doesNotHaveBean(
                            "reservationDepositDispositionScheduler");
                });
    }

    @Test
    void conditionalWorkerUsesDedicatedScheduler() throws Exception {
        ReservationDepositDispositionService service =
                mock(ReservationDepositDispositionService.class);
        PaymentService paymentService = mock(PaymentService.class);
        given(service.claimDue(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(100))).willReturn(List.of());
        ReservationDepositDispositionJob job = new ReservationDepositDispositionJob(
                service, paymentService);
        ReservationDepositProcessConfig.DispositionScheduledWorker worker =
                new ReservationDepositProcessConfig.DispositionScheduledWorker(job);
        Method scheduledMethod = ReservationDepositProcessConfig
                .DispositionScheduledWorker.class.getMethod("runScheduled");
        Scheduled scheduled = scheduledMethod.getAnnotation(Scheduled.class);

        scheduledMethod.invoke(worker);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler())
                .isEqualTo("reservationDepositDispositionScheduler");
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("#{@reservationDepositDispositionPollDelayMs}");
        assertThat(ReservationDepositDispositionJob.class
                .getMethod("runScheduled")
                .getAnnotation(Scheduled.class)).isNull();
        assertThat(ReservationDepositProcessConfig.class.getDeclaredMethods())
                .anySatisfy(method -> {
                    Bean bean = method.getAnnotation(Bean.class);
                    assertThat(bean).isNotNull();
                    assertThat(List.of(bean.name()))
                            .contains("reservationDepositDispositionScheduler");
                });
    }

    @Test
    void applyCallRunsBetweenClaimAndResultTransactions() throws Exception {
        ReservationDepositDispositionService service =
                mock(ReservationDepositDispositionService.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositDispositionService.Claim claim = claim(
                ReservationDepositDispositionObligation.Operation.APPLY);
        DispositionResult completed = completed();
        ApplyReservationDepositDispositionCommand command = applyCommand();
        given(service.claimDue("worker-a", 10)).willReturn(List.of(claim));
        given(paymentService.applyReservationDepositDisposition(command))
                .willReturn(completed);
        given(service.recordResult(claim, completed, RETRY_DELAY, QUERY_DELAY, 3))
                .willReturn(true);
        ReservationDepositDispositionJob job = new ReservationDepositDispositionJob(
                service, paymentService);

        assertThat(job.runOnce("worker-a", 10)).isOne();

        var order = inOrder(service, paymentService);
        order.verify(service).claimDue("worker-a", 10);
        order.verify(paymentService).applyReservationDepositDisposition(command);
        order.verify(service).recordResult(
                claim, completed, RETRY_DELAY, QUERY_DELAY, 3);
        assertThat(ReservationDepositDispositionJob.class
                .getMethod("runOnce", String.class, int.class)
                .getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void reconciliationClaimUsesReadOnlyPaymentQueryAndNeverAppliesAgain() {
        ReservationDepositDispositionService service =
                mock(ReservationDepositDispositionService.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositDispositionService.Claim claim = claim(
                ReservationDepositDispositionObligation.Operation.QUERY);
        DispositionResult completed = completed();
        GetReservationDepositDispositionQuery query =
                new GetReservationDepositDispositionQuery(
                        claim.paymentId(), claim.sourceEventId());
        given(service.claimDue("worker-a", 10)).willReturn(List.of(claim));
        given(paymentService.getReservationDepositDisposition(query))
                .willReturn(completed);
        given(service.recordResult(claim, completed, RETRY_DELAY, QUERY_DELAY, 3))
                .willReturn(true);
        ReservationDepositDispositionJob job = new ReservationDepositDispositionJob(
                service, paymentService);

        assertThat(job.runOnce("worker-a", 10)).isOne();

        then(paymentService).should().getReservationDepositDisposition(query);
        then(paymentService).should(never())
                .applyReservationDepositDisposition(
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void runtimeFailureRequeuesFiniteClaimAndContinuesBatch() {
        ReservationDepositDispositionService service =
                mock(ReservationDepositDispositionService.class);
        PaymentService paymentService = mock(PaymentService.class);
        ReservationDepositDispositionService.Claim failed = claim(
                ReservationDepositDispositionObligation.Operation.APPLY);
        ReservationDepositDispositionService.Claim completedClaim = new
                ReservationDepositDispositionService.Claim(
                        failed.obligationId() + 1,
                        failed.processId() + 1,
                        failed.reservationId() + 1,
                        "52",
                        failed.sourceEventId() + "-next",
                        failed.sourceEventType(),
                        null,
                        2L,
                        "STORE_RESPONSIBLE",
                        10_000,
                        "550e8400-e29b-41d4-a716-446655440241",
                        ReservationDepositDispositionObligation.Operation.APPLY,
                        "worker-a",
                        1L,
                        1);
        given(service.claimDue("worker-a", 10))
                .willReturn(List.of(failed, completedClaim));
        given(paymentService.applyReservationDepositDisposition(applyCommand()))
                .willThrow(new IllegalStateException("temporary failure"));
        given(paymentService.applyReservationDepositDisposition(
                completedClaim.toApplyCommand())).willReturn(completed());
        given(service.recordResult(
                completedClaim, completed(), RETRY_DELAY, QUERY_DELAY, 3))
                .willReturn(true);
        ReservationDepositDispositionJob job = new ReservationDepositDispositionJob(
                service, paymentService);

        assertThat(job.runOnce("worker-a", 10)).isOne();

        then(service).should().recordRetryableFailure(failed, RETRY_DELAY, 3);
        then(service).should().recordResult(
                completedClaim, completed(), RETRY_DELAY, QUERY_DELAY, 3);
    }

    @Test
    void queryRuntimeFailureRetriesWithQueryAndNeverAppliesDisposition() {
        ReservationDepositDispositionObligationRepository repository = mock(
                ReservationDepositDispositionObligationRepository.class);
        ReservationDepositDispositionObligation obligation =
                reconciliationObligation();
        MutableClock clock = new MutableClock(NOW);
        given(repository.findClaimableForUpdate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .willReturn(List.of(obligation));
        given(repository.findByIdForUpdate(501L))
                .willReturn(Optional.of(obligation));
        ReservationDepositDispositionService service =
                new ReservationDepositDispositionService(
                        repository, clock, Duration.ofSeconds(30));
        PaymentService paymentService = mock(PaymentService.class);
        GetReservationDepositDispositionQuery query =
                new GetReservationDepositDispositionQuery(
                        obligation.getPaymentId(), obligation.getSourceEventId());
        given(paymentService.getReservationDepositDisposition(query))
                .willThrow(new IllegalStateException("temporary query failure"))
                .willReturn(completed());
        given(paymentService.applyReservationDepositDisposition(
                org.mockito.ArgumentMatchers.any())).willReturn(completed());
        ReservationDepositDispositionJob job = new ReservationDepositDispositionJob(
                service, paymentService);

        assertThat(job.runOnce("worker-a", 1)).isZero();
        assertThat(obligation.getStatus()).isEqualTo(
                ReservationDepositDispositionObligation.Status.RECONCILIATION_REQUIRED);
        assertThat(obligation.getNextOperation())
                .isEqualTo(ReservationDepositDispositionObligation.Operation.QUERY);

        clock.advance(Duration.ofSeconds(31));
        assertThat(job.runOnce("worker-a", 1)).isOne();

        then(paymentService).should(org.mockito.Mockito.times(2))
                .getReservationDepositDisposition(query);
        then(paymentService).should(never())
                .applyReservationDepositDisposition(
                        org.mockito.ArgumentMatchers.any());
    }

    private static ReservationDepositDispositionService.Claim claim(
            ReservationDepositDispositionObligation.Operation operation
    ) {
        return new ReservationDepositDispositionService.Claim(
                501L,
                31L,
                41L,
                "51",
                "reservation-cancel:41:550e8400-e29b-41d4-a716-446655440240",
                "RESERVATION_CANCELLED",
                null,
                2L,
                "CONSUMER",
                5_000,
                "550e8400-e29b-41d4-a716-446655440239",
                operation,
                "worker-a",
                1L,
                1);
    }

    private static ApplyReservationDepositDispositionCommand applyCommand() {
        return claim(ReservationDepositDispositionObligation.Operation.APPLY)
                .toApplyCommand();
    }

    private static ReservationDepositDispositionObligation
            reconciliationObligation() {
        ReservationDepositDispositionObligation obligation =
                ReservationDepositDispositionObligation.pending(
                        31L,
                        41L,
                        "51",
                        "reservation-cancel:41:550e8400-e29b-41d4-a716-446655440240",
                        "RESERVATION_CANCELLED",
                        null,
                        2L,
                        "CONSUMER",
                        5_000,
                        "550e8400-e29b-41d4-a716-446655440239",
                        "550e8400-e29b-41d4-a716-446655440240",
                        NOW.minusSeconds(120));
        ReflectionTestUtils.setField(obligation, "id", 501L);
        obligation.claim(
                "seed-worker", NOW.minusSeconds(90), NOW.minusSeconds(30));
        obligation.requireReconciliation(
                "seed-worker",
                1L,
                NOW.minusSeconds(89),
                Duration.ZERO,
                new ReservationDepositDispositionObligation.PaymentSnapshot(
                        "550e8400-e29b-41d4-a716-446655440239",
                        "71",
                        10_001L,
                        5_000L,
                        5_000L,
                        0L,
                        5_001L,
                        "KRW",
                        "RECONCILIATION_REQUIRED",
                        "UNKNOWN",
                        NOW.minusSeconds(100),
                        NOW.minusSeconds(90),
                        null));
        return obligation;
    }

    private static DispositionResult completed() {
        return new DispositionResult(
                "550e8400-e29b-41d4-a716-446655440239",
                "51",
                "reservation-cancel:41:550e8400-e29b-41d4-a716-446655440240",
                "RESERVATION_CANCELLED",
                null,
                2L,
                "CONSUMER",
                5_000,
                10_001L,
                5_000L,
                5_000L,
                5_000L,
                5_001L,
                "KRW",
                "71",
                DispositionStatus.COMPLETED,
                null,
                NOW.minusSeconds(1),
                NOW,
                NOW);
    }


    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
