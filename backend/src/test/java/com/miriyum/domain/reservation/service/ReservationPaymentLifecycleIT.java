package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.payment.dto.PaymentContracts.ConfirmPaymentCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareWaitingReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.port.PaymentProviderClient;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderCancellation;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderPayment;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderStatus;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.dto.request.ReservationCreateRequest;
import com.miriyum.domain.reservation.dto.request.ReservationFulfillmentRequest;
import com.miriyum.domain.reservation.dto.request.ReservationNoShowRequest;
import com.miriyum.domain.reservation.dto.request.ReservationPartyRequest;
import com.miriyum.domain.reservation.dto.request.StoreCancellationRequest;
import com.miriyum.domain.reservation.dto.response.ReservationRequestResponse;
import com.miriyum.domain.reservation.dto.response.StoreReservationPaymentStatusResponse;
import com.miriyum.domain.reservation.dto.response.StoreReservationPaymentStatusResponse.StorePaymentResult;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationNoShowReason;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.schedule.dto.contract.StoreReservationWindowResult;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalRequest;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalResult;
import com.miriyum.domain.schedule.service.StoreScheduleService;
import com.miriyum.domain.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-c")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.task.scheduling.enabled=false",
            "miriyum.reservation.deposit-worker.enabled=false",
            "miriyum.reservation.hold-expiration.enabled=false",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.payment.cursor-secret=test-history-cursor-secret-with-enough-entropy",
            "miriyum.payment.portone.api-secret=test-api-secret",
            "miriyum.payment.portone.webhook-secret=whsec_dGVzdC1zZWNyZXQ=",
            "miriyum.payment.portone.store-id=store-1"
        }
)
@Import(ReservationPaymentLifecycleIT.TestClockConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReservationPaymentLifecycleIT {

    private static final Instant PAYMENT_AT = Instant.parse("2026-08-10T00:00:00Z");
    private static final Instant START_AT = Instant.parse("2026-08-16T01:00:00Z");
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 16);
    private static final LocalTime START_TIME = LocalTime.of(10, 0);
    private static final long AMOUNT_MINOR = 30_000L;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"))
                    .withCommand("--log-bin-trust-function-creators=1");

    @Autowired
    private ReservationFulfillmentCommandFacade fulfillmentFacade;

    @Autowired
    private ReservationVisitCommandFacade visitFacade;

    @Autowired
    private ReservationCancellationCommandFacade cancellationFacade;

    @Autowired
    private ReservationDepositDispositionJob dispositionJob;

    @Autowired
    private ReservationCreationCommandFacade creationFacade;

    @Autowired
    private ReservationDepositProcessCommandFacade processCommandFacade;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private StoreReservationPaymentStatusQueryService paymentStatusQueryService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private ConsumerAccountRepository consumerRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationCapacityBucketRepository capacityBucketRepository;

    @Autowired
    private ReservationCapacityAllocationRepository allocationRepository;

    @MockitoBean
    private PaymentProviderClient providerClient;

    @MockitoBean
    private StoreScheduleService storeScheduleService;

    @MockitoBean
    private StoreServiceIntervalValidationService intervalValidationService;

    @BeforeEach
    void resetExternalBoundaryAndClock() {
        reset(providerClient);
        clock.set(PAYMENT_AT);
    }

    @Test
    @DisplayName("운영자 결제 상태 조회는 V1·완료·환불·대사 상태를 저장 원장만으로 조합한다")
    void operatorPaymentStatusReadComposesPersistedLifecycleWithoutProviderCalls() {
        Scenario v1 = scenario(false);

        clearInvocations(providerClient);
        StoreReservationPaymentStatusResponse notApplicable = paymentStatusQueryService.get(
                v1.operatorId(), v1.storeId(), v1.reservationId());

        assertThat(notApplicable.result()).isEqualTo(StorePaymentResult.NOT_APPLICABLE);
        assertThat(notApplicable.payment()).isNull();
        verifyNoInteractions(providerClient);

        FinancialScenario paid = paidV2ScenarioFromPublicCommands();
        clearInvocations(providerClient);
        StoreReservationPaymentStatusResponse completed = paymentStatusQueryService.get(
                paid.operatorId(), paid.storeId(), paid.reservationId());

        assertThat(completed.result()).isEqualTo(StorePaymentResult.COMPLETED);
        assertThat(completed.reconciliationRequired()).isFalse();
        assertThat(completed.payment().paymentId()).isEqualTo(paid.paymentId());
        assertThat(completed.payment().amountMinor()).isEqualTo(AMOUNT_MINOR);
        assertThat(completed.payment().refundedAmountMinor()).isZero();
        assertThat(completed.payment().refundableAmountMinor()).isEqualTo(AMOUNT_MINOR);
        assertThat(completed.payment().currency()).isEqualTo("KRW");
        assertThat(completed.payment().status()).isEqualTo(PaymentStatus.PAID);
        assertThat(completed.payment().refunds()).isEmpty();
        verifyNoInteractions(providerClient);

        FinancialScenario refunded = paidV2ScenarioFromPublicCommands();
        when(providerClient.cancelPayment(
                eq(refunded.portOnePaymentId()), anyString(), eq(AMOUNT_MINOR),
                eq("KRW"), eq("STORE_STATUS_READ_TEST")))
                .thenAnswer(invocation -> new ProviderCancellation(
                        "completed-" + invocation.getArgument(1, String.class),
                        ProviderStatus.CANCELLED,
                        AMOUNT_MINOR,
                        "KRW"));
        assertThat(paymentService.requestRefund(refundCommand(refunded, "completed"))
                .status()).isEqualTo(RefundStatus.COMPLETED);
        clearInvocations(providerClient);

        StoreReservationPaymentStatusResponse refundCompleted = paymentStatusQueryService.get(
                refunded.operatorId(), refunded.storeId(), refunded.reservationId());

        assertThat(refundCompleted.result()).isEqualTo(StorePaymentResult.COMPLETED);
        assertThat(refundCompleted.payment().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refundCompleted.payment().refundedAmountMinor()).isEqualTo(AMOUNT_MINOR);
        assertThat(refundCompleted.payment().refundableAmountMinor()).isZero();
        assertThat(refundCompleted.payment().refunds()).singleElement()
                .satisfies(refund -> {
                    assertThat(refund.amountMinor()).isEqualTo(AMOUNT_MINOR);
                    assertThat(refund.status()).isEqualTo(RefundStatus.COMPLETED);
                    assertThat(refund.completedAt()).isNotNull();
                });
        verifyNoInteractions(providerClient);

        FinancialScenario unknown = paidV2ScenarioFromPublicCommands();
        when(providerClient.cancelPayment(
                eq(unknown.portOnePaymentId()), anyString(), eq(AMOUNT_MINOR),
                eq("KRW"), eq("STORE_STATUS_READ_TEST")))
                .thenThrow(new PaymentProviderClient.ProviderUnavailableException("timeout"));
        assertThat(paymentService.requestRefund(refundCommand(unknown, "unknown"))
                .status()).isEqualTo(RefundStatus.RECONCILIATION_REQUIRED);
        clearInvocations(providerClient);

        StoreReservationPaymentStatusResponse reconciliation = paymentStatusQueryService.get(
                unknown.operatorId(), unknown.storeId(), unknown.reservationId());

        assertThat(reconciliation.result()).isEqualTo(StorePaymentResult.UNKNOWN);
        assertThat(reconciliation.reconciliationRequired()).isTrue();
        assertThat(reconciliation.payment().status())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(reconciliation.payment().refunds()).singleElement()
                .extracting(StoreReservationPaymentStatusResponse.StoreReservationRefund::status)
                .isEqualTo(RefundStatus.RECONCILIATION_REQUIRED);
        verifyNoInteractions(providerClient);

        Scenario otherReservation = scenario(false);
        FinancialScenario otherPayment = paidLinkedScenario(otherReservation, true);
        jdbcTemplate.update(
                "UPDATE reservation_deposit_processes SET payment_id = ? "
                        + "WHERE final_reservation_id = ?",
                otherPayment.paymentId(),
                paid.reservationId());
        clearInvocations(providerClient);

        assertThatThrownBy(() -> paymentStatusQueryService.get(
                paid.operatorId(), paid.storeId(), paid.reservationId()))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
        verifyNoInteractions(providerClient);

        assertThatThrownBy(() -> paymentStatusQueryService.get(
                v1.operatorId(), paid.storeId(), paid.reservationId()))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.STORE_NOT_FOUND));
        verifyNoInteractions(providerClient);
    }

    @Test
    @DisplayName("방문 완료 obligation 누락이나 replay 중복은 실제 환불 수렴에서 검출한다")
    void fulfillmentLifecycleRejectsMissingOrDuplicateRefundConvergence() {
        FinancialScenario scenario = paidV2ScenarioFromPublicCommands();
        stubSuccessfulFullRefund();
        clock.set(START_AT.plusSeconds(60));
        IdempotencyKey key = key("fulfill", scenario.reservationId());

        ReservationFulfillmentCommandResult first = fulfillmentFacade.fulfill(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(), key,
                new ReservationFulfillmentRequest());
        ReservationFulfillmentCommandResult replay = fulfillmentFacade.fulfill(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(), key,
                new ReservationFulfillmentRequest());

        assertThat(first.data().status()).isEqualTo("FULFILLED");
        assertThat(first.data().depositDisposition().status()).isEqualTo("PENDING");
        assertThat(replay).isEqualTo(first);
        assertThat(dispositionJob.runOnce("lifecycle-fulfillment", 10)).isOne();
        assertThat(dispositionJob.runOnce("lifecycle-fulfillment-replay", 10)).isZero();

        assertCompletedFinancialResult(
                scenario,
                "FULFILLED",
                "RESERVATION_FULFILLED",
                "CONSUMER",
                10_000,
                PaymentStatus.REFUNDED,
                1L);
        assertThat(paymentLedgerCount(scenario.paymentId(), "PAYMENT_CONFIRMED")).isOne();
        assertThat(paymentLedgerCount(scenario.paymentId(), "REFUND_COMPLETED")).isOne();
    }

    @ParameterizedTest(name = "{0} -> {1}/{2}")
    @MethodSource("noShowMappings")
    @DisplayName("노쇼 귀책 fallback이나 UNCLEAR 선처분은 실제 원장 매핑에서 검출한다")
    void noShowResponsibilityMappingRejectsFallbackOrPrematureDisposition(
            ReservationNoShowReason reason,
            String expectedResponsibility,
            int expectedRate,
            PaymentStatus expectedPaymentStatus,
            long expectedRefundCount
    ) {
        FinancialScenario scenario = paidV2Scenario(false);
        stubSuccessfulFullRefund();
        clock.set(START_AT.plusSeconds(300));
        IdempotencyKey key = key("no-show-" + reason, scenario.reservationId());

        ReservationVisitCommandResult first = visitFacade.markNoShow(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(), key,
                new ReservationNoShowRequest(reason));
        ReservationVisitCommandResult replay = visitFacade.markNoShow(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(), key,
                new ReservationNoShowRequest(reason));

        assertThat(first.data().status()).isEqualTo("NO_SHOW");
        assertThat(replay).isEqualTo(first);
        if (expectedResponsibility == null) {
            assertThat(first.data().depositDisposition()).isNull();
            assertNoFinancialDisposition(scenario);
            return;
        }

        assertThat(first.data().depositDisposition().responsibilityCode())
                .isEqualTo(expectedResponsibility);
        assertThat(first.data().depositDisposition().targetRefundRateBasisPoints())
                .isEqualTo(expectedRate);
        assertThat(dispositionJob.runOnce(
                "lifecycle-no-show-" + scenario.reservationId(), 10)).isOne();
        assertCompletedFinancialResult(
                scenario,
                "NO_SHOW",
                "RESERVATION_NO_SHOW",
                expectedResponsibility,
                expectedRate,
                expectedPaymentStatus,
                expectedRefundCount);
    }

    @Test
    @DisplayName("취소·방문·노쇼·worker 경합의 다중 terminal과 다중 금전 결과를 검출한다")
    void terminalReplayAndWorkerRaceRejectsMultipleFinancialWinners() throws Exception {
        FinancialScenario scenario = paidV2Scenario(true);
        stubSuccessfulFullRefund();
        clock.set(START_AT.plusSeconds(300));
        IdempotencyKey cancellationKey = key("race-cancel", scenario.reservationId());
        IdempotencyKey fulfillmentKey = key("race-fulfill", scenario.reservationId());
        IdempotencyKey noShowKey = key("race-no-show", scenario.reservationId());
        AtomicReference<Terminal> winner = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(7);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(7);
        try {
            Future<TerminalAttempt> cancellation = executor.submit(() -> attemptTerminal(
                    ready, start, winner, Terminal.CANCELLED,
                    () -> cancellationFacade.cancelByStoreOperator(
                            scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                            cancellationKey, new StoreCancellationRequest("race"))));
            Future<TerminalAttempt> cancellationReplay = executor.submit(() -> attemptTerminal(
                    ready, start, winner, Terminal.CANCELLED,
                    () -> cancellationFacade.cancelByStoreOperator(
                            scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                            cancellationKey, new StoreCancellationRequest("race"))));
            Future<TerminalAttempt> fulfillment = executor.submit(() -> attemptTerminal(
                    ready, start, winner, Terminal.FULFILLED,
                    () -> fulfillmentFacade.fulfill(
                            scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                            fulfillmentKey, new ReservationFulfillmentRequest())));
            Future<TerminalAttempt> fulfillmentReplay = executor.submit(() -> attemptTerminal(
                    ready, start, winner, Terminal.FULFILLED,
                    () -> fulfillmentFacade.fulfill(
                            scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                            fulfillmentKey, new ReservationFulfillmentRequest())));
            Future<TerminalAttempt> noShow = executor.submit(() -> attemptTerminal(
                    ready, start, winner, Terminal.NO_SHOW,
                    () -> visitFacade.markNoShow(
                            scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                            noShowKey,
                            new ReservationNoShowRequest(
                                    ReservationNoShowReason.USER_CAUSE_CANDIDATE))));
            Future<TerminalAttempt> noShowReplay = executor.submit(() -> attemptTerminal(
                    ready, start, winner, Terminal.NO_SHOW,
                    () -> visitFacade.markNoShow(
                            scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                            noShowKey,
                            new ReservationNoShowRequest(
                                    ReservationNoShowReason.USER_CAUSE_CANDIDATE))));
            Future<Integer> worker = executor.submit(() -> runWorkerAfterObligation(
                    ready, start, scenario.reservationId()));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<TerminalAttempt> attempts = List.of(
                    cancellation.get(15, TimeUnit.SECONDS),
                    cancellationReplay.get(15, TimeUnit.SECONDS),
                    fulfillment.get(15, TimeUnit.SECONDS),
                    fulfillmentReplay.get(15, TimeUnit.SECONDS),
                    noShow.get(15, TimeUnit.SECONDS),
                    noShowReplay.get(15, TimeUnit.SECONDS));
            assertThat(winner.get()).isNotNull();
            assertThat(attempts)
                    .filteredOn(TerminalAttempt::succeeded)
                    .isNotEmpty()
                    .allSatisfy(attempt -> assertThat(attempt.candidate()).isEqualTo(winner.get()));
            assertThat(attempts)
                    .filteredOn(attempt -> !attempt.succeeded())
                    .allSatisfy(attempt -> assertThat(attempt.errorCode()).isIn(
                            ReservationErrorCode.INVALID_STATE_TRANSITION,
                            CommonErrorCode.CONCURRENT_MODIFICATION));
            assertThat(attempts).filteredOn(attempt -> attempt.candidate() == Terminal.CANCELLED)
                    .hasSize(2);
            assertThat(attempts).filteredOn(attempt -> attempt.candidate() == Terminal.FULFILLED)
                    .hasSize(2);
            assertThat(attempts).filteredOn(attempt -> attempt.candidate() == Terminal.NO_SHOW)
                    .hasSize(2);
            assertThat(worker.get(15, TimeUnit.SECONDS)).isOne();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        replayWinner(scenario, winner.get(), cancellationKey, fulfillmentKey, noShowKey);
        ExpectedTerminal expected = ExpectedTerminal.from(winner.get());
        assertCompletedFinancialResult(
                scenario,
                expected.reservationStatus(),
                expected.sourceEventType(),
                expected.responsibilityCode(),
                expected.targetRate(),
                expected.paymentStatus(),
                expected.refundCount());
        assertThat(dispositionJob.runOnce("lifecycle-race-replay", 10)).isZero();
    }

    @Test
    @DisplayName("provider 응답 유실 뒤 QUERY 대사가 취소 POST나 환불을 재생성하면 검출한다")
    void unknownProviderResultRejectsCancellationPostReplayDuringReconciliation() {
        FinancialScenario scenario = paidV2Scenario(false);
        reset(providerClient);
        when(providerClient.cancelPayment(
                eq(scenario.portOnePaymentId()), anyString(), eq(AMOUNT_MINOR),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenThrow(new PaymentProviderClient.ProviderUnavailableException("timeout"));
        clock.set(START_AT.plusSeconds(60));

        fulfillmentFacade.fulfill(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                key("unknown-fulfill", scenario.reservationId()),
                new ReservationFulfillmentRequest());

        assertThat(dispositionJob.runOnce("lifecycle-unknown-apply", 10)).isZero();
        assertThat(obligationStatus(scenario.reservationId()))
                .isEqualTo("RECONCILIATION_REQUIRED");
        String refundId = jdbcTemplate.queryForObject("""
                SELECT d.refund_id
                  FROM reservation_deposit_dispositions d
                  JOIN payments p ON p.payment_pk = d.payment_pk
                 WHERE p.payment_id = ?
                """, String.class, scenario.paymentId());
        String reason = PaymentProviderClient.cancellationReason(
                "RESERVATION_DEPOSIT_DISPOSITION", refundId);
        when(providerClient.getPayment(scenario.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        scenario.portOnePaymentId(),
                        "transaction-" + scenario.reservationId(),
                        ProviderStatus.CANCELLED,
                        AMOUNT_MINOR,
                        "KRW",
                        List.of(new ProviderCancellation(
                                "reconciled-" + refundId,
                                ProviderStatus.CANCELLED,
                                AMOUNT_MINOR,
                                "KRW",
                                reason))));
        clock.advance(Duration.ofSeconds(31));

        assertThat(dispositionJob.runOnce("lifecycle-unknown-query", 10)).isOne();
        assertThat(dispositionJob.runOnce("lifecycle-unknown-query-replay", 10)).isZero();

        assertCompletedFinancialResult(
                scenario,
                "FULFILLED",
                "RESERVATION_FULFILLED",
                "CONSUMER",
                10_000,
                PaymentStatus.REFUNDED,
                1L);
        assertThat(paymentLedgerCount(scenario.paymentId(), "REFUND_COMPLETED")).isOne();
        verify(providerClient, times(1)).cancelPayment(
                eq(scenario.portOnePaymentId()), eq(refundId), eq(AMOUNT_MINOR),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION"));
        verify(providerClient, times(1)).getPayment(scenario.portOnePaymentId());
    }

    @Test
    @DisplayName("V1·null·unknown·비예약금 terminal의 V2 소급 처분 생성을 검출한다")
    void legacyAndNonDepositTerminalsRejectRetroactiveDispositionCreation() {
        Scenario v1Fulfillment = scenario(false);
        Scenario nullNoShow = scenario(false);
        Scenario unknownNoShow = scenario(false);
        Scenario nonDepositCancellation = scenario(true);
        FinancialScenario v1Payment = paidLinkedScenario(v1Fulfillment, true);
        FinancialScenario nullPayment = paidLinkedScenario(nullNoShow, true);
        FinancialScenario unknownPayment = paidLinkedScenario(unknownNoShow, true);
        FinancialScenario nonDepositPayment = paidLinkedScenario(nonDepositCancellation, false);
        jdbcTemplate.update(
                "UPDATE reservations SET cancellation_policy_version = NULL "
                        + "WHERE reservation_id = ?",
                nullNoShow.reservationId());
        jdbcTemplate.update(
                "UPDATE reservations SET cancellation_policy_version = 99 "
                        + "WHERE reservation_id = ?",
                unknownNoShow.reservationId());
        clock.set(START_AT.plusSeconds(300));

        fulfillmentFacade.fulfill(
                v1Fulfillment.operatorId(), v1Fulfillment.storeId(),
                v1Fulfillment.reservationId(), key("legacy-fulfill", v1Fulfillment.reservationId()),
                new ReservationFulfillmentRequest());
        visitFacade.markNoShow(
                nullNoShow.operatorId(), nullNoShow.storeId(), nullNoShow.reservationId(),
                key("null-no-show", nullNoShow.reservationId()),
                new ReservationNoShowRequest(ReservationNoShowReason.STORE_CAUSE_CANDIDATE));
        visitFacade.markNoShow(
                unknownNoShow.operatorId(), unknownNoShow.storeId(),
                unknownNoShow.reservationId(),
                key("unknown-no-show", unknownNoShow.reservationId()),
                new ReservationNoShowRequest(
                        ReservationNoShowReason.PLATFORM_EXTERNAL_CAUSE_CANDIDATE));
        cancellationFacade.cancelByStoreOperator(
                nonDepositCancellation.operatorId(), nonDepositCancellation.storeId(),
                nonDepositCancellation.reservationId(),
                key("non-deposit-cancel", nonDepositCancellation.reservationId()),
                new StoreCancellationRequest("non-deposit"));

        List<Long> reservationIds = List.of(
                v1Fulfillment.reservationId(),
                nullNoShow.reservationId(),
                unknownNoShow.reservationId(),
                nonDepositCancellation.reservationId());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_deposit_disposition_obligations
                 WHERE reservation_id IN (?, ?, ?, ?)
                """, Long.class, reservationIds.toArray())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM payments
                 WHERE source_reference_id IN (?, ?, ?, ?)
                """, Long.class, reservationIds.stream().map(String::valueOf).toArray()))
                .isEqualTo(4L);
        assertThat(paymentSourceType(v1Payment.paymentId())).isEqualTo("RESERVATION_DEPOSIT");
        assertThat(paymentSourceType(nullPayment.paymentId())).isEqualTo("RESERVATION_DEPOSIT");
        assertThat(paymentSourceType(unknownPayment.paymentId())).isEqualTo("RESERVATION_DEPOSIT");
        assertThat(paymentSourceType(nonDepositPayment.paymentId()))
                .isEqualTo("WAITING_RESERVATION_DEPOSIT");
        assertNoFinancialDisposition(v1Payment);
        assertNoFinancialDisposition(nullPayment);
        assertNoFinancialDisposition(unknownPayment);
        assertNoFinancialDisposition(nonDepositPayment);
        verify(providerClient, never()).cancelPayment(
                anyString(), anyString(), anyLong(), anyString(), anyString());
    }

    private Stream<Arguments> noShowMappings() {
        return Stream.of(
                Arguments.of(
                        ReservationNoShowReason.USER_CAUSE_CANDIDATE,
                        "CONSUMER", 0, PaymentStatus.PAID, 0L),
                Arguments.of(
                        ReservationNoShowReason.STORE_CAUSE_CANDIDATE,
                        "STORE_RESPONSIBLE", 10_000, PaymentStatus.REFUNDED, 1L),
                Arguments.of(
                        ReservationNoShowReason.PLATFORM_EXTERNAL_CAUSE_CANDIDATE,
                        "PLATFORM_RESPONSIBLE", 10_000, PaymentStatus.REFUNDED, 1L),
                Arguments.of(
                        ReservationNoShowReason.UNCLEAR,
                        null, -1, PaymentStatus.PAID, 0L));
    }

    private FinancialScenario paidV2ScenarioFromPublicCommands() {
        DepositCreationInputs inputs = seedDepositCreationInputs();
        LocalDateTime localStart = LocalDateTime.of(SERVICE_DATE, START_TIME);
        when(storeScheduleService.resolveReservationWindows(
                List.of(inputs.storeId()), SERVICE_DATE, START_TIME)).thenReturn(List.of(
                        StoreReservationWindowResult.accepting(
                                inputs.storeId(),
                                "Asia/Seoul",
                                localStart.minusHours(1),
                                localStart.plusHours(8))));
        StoreServiceIntervalRequest interval = new StoreServiceIntervalRequest(
                inputs.storeId(), START_AT, START_AT.plusSeconds(3_600));
        when(intervalValidationService.validateServiceIntervals(List.of(interval)))
                .thenReturn(List.of(StoreServiceIntervalResult.of(interval, true)));

        IdempotencyKey creationKey = key("deposit-create", inputs.consumerId());
        ReservationCreationCommandResult creation = creationFacade.create(
                inputs.consumerId(),
                creationKey,
                new ReservationCreateRequest(
                        String.valueOf(inputs.storeId()),
                        SERVICE_DATE,
                        START_TIME,
                        null,
                        new ReservationPartyRequest(2, 0, 0),
                        List.of()));

        assertThat(creation.httpStatus()).isEqualTo(202);
        assertThat(creation.responseData()).isInstanceOf(ReservationRequestResponse.class);
        ReservationRequestResponse request =
                (ReservationRequestResponse) creation.responseData();
        assertThat(request.status().name()).isEqualTo("AWAITING_PAYMENT");
        assertThat(request.reservation()).isNull();
        assertThat(request.paymentPreparation().amountMinor()).isEqualTo(AMOUNT_MINOR);
        assertThat(request.paymentPreparation().currency()).isEqualTo("KRW");
        long processId = Long.parseLong(request.reservationRequestId());
        String paymentId = request.paymentPreparation().paymentId();
        String portOnePaymentId = request.paymentPreparation().portOnePaymentId();

        when(providerClient.getPayment(portOnePaymentId)).thenReturn(new ProviderPayment(
                portOnePaymentId,
                "transaction-public-path-" + processId,
                ProviderStatus.PAID,
                AMOUNT_MINOR,
                "KRW"));
        assertThat(paymentService.confirmPayment(new ConfirmPaymentCommand(
                paymentId,
                inputs.consumerId(),
                portOnePaymentId,
                uuid("confirm-public-path:" + processId))).status())
                .isEqualTo(PaymentStatus.PAID);
        clearInvocations(providerClient);

        ReservationDepositCommandResult finalization = processCommandFacade.finalizeRequest(
                inputs.consumerId(),
                processId,
                key("deposit-finalize", processId));
        assertThat(finalization.httpStatus()).isEqualTo(200);
        assertThat(finalization.reservation().status()).isEqualTo("CONFIRMED");
        long reservationId = Long.parseLong(finalization.reservation().reservationId());
        long holdId = jdbcTemplate.queryForObject(
                "SELECT reservation_hold_id FROM reservation_deposit_processes "
                        + "WHERE reservation_deposit_process_id = ?",
                Long.class,
                processId);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, reservation_hold_id, payment_id, final_reservation_id
                  FROM reservation_deposit_processes
                 WHERE reservation_deposit_process_id = ?
                """, processId))
                .containsEntry("status", "COMPLETED")
                .containsEntry("reservation_hold_id", holdId)
                .containsEntry("payment_id", paymentId)
                .containsEntry("final_reservation_id", reservationId);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, cancellation_policy_version, creation_command_id
                  FROM reservation_holds
                 WHERE reservation_hold_id = ?
                """, holdId))
                .containsEntry("status", "CONFIRMED")
                .containsEntry("cancellation_policy_version", 2L)
                .containsEntry(
                        "creation_command_id",
                        "reservation-deposit-create:" + creationKey.value());
        assertThat(jdbcTemplate.queryForMap("""
                SELECT source_type, source_reference_id, status
                  FROM payments
                 WHERE payment_id = ?
                """, paymentId))
                .containsEntry("source_type", "RESERVATION_DEPOSIT")
                .containsEntry("source_reference_id", String.valueOf(holdId))
                .containsEntry("status", "PAID");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM idempotency_commands
                 WHERE principal_id = ?
                   AND command_type = 'RESERVATION_CREATE'
                   AND processing_status = 'SUCCEEDED'
                   AND result_http_status = 202
                """, Long.class, inputs.consumerId())).isOne();

        return new FinancialScenario(
                inputs.operatorId(),
                inputs.storeId(),
                inputs.consumerId(),
                reservationId,
                paymentId,
                portOnePaymentId);
    }

    private DepositCreationInputs seedDepositCreationInputs() {
        return transactions.execute(status -> {
            int sequence = SEQUENCE.incrementAndGet();
            StoreOperatorAccount operator = operatorRepository.saveAndFlush(
                    StoreOperatorAccount.create(
                            "lifecycle-public-owner-" + sequence + "@example.com",
                            "hashed-password",
                            "owner"));
            Store store = storeRepository.saveAndFlush(Store.create(
                    operator.getId(),
                    Long.toString(8_200_000_000L + sequence),
                    BusinessType.CAFE,
                    "Lifecycle Public Store " + sequence,
                    "",
                    Region.SEOUL,
                    "fixture-address",
                    "CAFE_BAKERY",
                    Set.of(),
                    true,
                    true,
                    false,
                    "Asia/Seoul",
                    LocalDateTime.of(2026, 8, 1, 9, 0),
                    "STORE_ONBOARDING_REQUIRED_TERMS_V1"));
            ConsumerAccount consumer = consumerRepository.saveAndFlush(
                    ConsumerAccount.createWithContact(
                            "lifecycle-public-consumer-" + sequence + "@example.com",
                            "hashed-password",
                            "consumer",
                            String.format(Locale.ROOT, "011%08d", sequence),
                            "opaque-lifecycle-public-contact-" + sequence));

            long menuId = 9_100_000L + sequence;
            long menuVersionId = 9_200_000L + sequence;
            long timePolicyId = 9_300_000L + sequence;
            long capacityBucketId = 9_400_000L + sequence;
            jdbcTemplate.update(
                    "UPDATE stores SET verification_status = 'APPROVED', "
                            + "operation_status = 'OPEN' WHERE store_id = ?",
                    store.getId());
            jdbcTemplate.update("""
                    INSERT INTO store_reservation_deposit_policies (
                        store_id, enabled, rate_percent, policy_version, lock_version,
                        created_at, updated_at
                    ) VALUES (?, TRUE, 20, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, store.getId());
            jdbcTemplate.update("""
                    INSERT INTO menus (
                        menu_id, store_id, next_version_number, published_version_number,
                        visibility, selling_status, retired, lock_version,
                        created_at, updated_at
                    ) VALUES (?, ?, 2, 1, 'VISIBLE', 'SELLING', FALSE, 0,
                              UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, menuId, store.getId());
            jdbcTemplate.update("""
                    INSERT INTO menu_versions (
                        menu_version_id, menu_id, version_number, status, name,
                        description, price, representative, primary_category_code,
                        hold_selection_allowed, pickup_selection_allowed,
                        allergen_information_status, origin_information_status,
                        alcoholic, created_by_operator_id, created_at, effective_at
                    ) VALUES (?, ?, 1, 'PUBLISHED', '대표 메뉴', '', 75000, TRUE,
                              'BEVERAGE', TRUE, TRUE, 'NOT_REGISTERED',
                              'NOT_APPLICABLE', FALSE, ?,
                              '2026-08-01 00:00:00.000000',
                              '2026-08-01 00:00:00.000000')
                    """, menuVersionId, menuId, operator.getId());
            jdbcTemplate.update("""
                    INSERT INTO representative_menu_settings (
                        store_id, version, status, lock_version, created_at, updated_at
                    ) VALUES (?, 1, 'CONFIGURED', 0,
                              UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, store.getId());
            jdbcTemplate.update("""
                    INSERT INTO representative_menu_entries (store_id, display_order, menu_id)
                    VALUES (?, 1, ?)
                    """, store.getId(), menuId);
            jdbcTemplate.update("""
                    INSERT INTO reservation_time_policy_versions (
                        reservation_time_policy_version_id, store_id, version_number,
                        slot_interval_minutes, service_duration_minutes,
                        turnover_duration_minutes, status, effective_at, activated_at,
                        publication_requested_at, change_reason, created_at, updated_at
                    ) VALUES (?, ?, 1, 10, 60, 0, 'ACTIVE',
                              '2026-08-01 00:00:00.000000',
                              '2026-08-01 00:00:00.000000',
                              '2026-08-01 00:00:00.000000',
                              'lifecycle public path',
                              '2026-08-01 00:00:00.000000',
                              '2026-08-01 00:00:00.000000')
                    """, timePolicyId, store.getId());
            jdbcTemplate.update("""
                    INSERT INTO reservation_capacity_buckets (
                        reservation_capacity_bucket_id, store_id, service_date,
                        start_time, end_time, max_people, max_teams,
                        occupied_people, occupied_teams, min_party_size,
                        max_party_size, infants_allowed, policy_version
                    ) VALUES (?, ?, '2026-08-16', '10:00:00.000000',
                              '11:00:00.000000', 10, 5, 0, 0, 1, 10, TRUE, 1)
                    """, capacityBucketId, store.getId());
            return new DepositCreationInputs(
                    operator.getId(), store.getId(), consumer.getId());
        });
    }

    private FinancialScenario paidLinkedScenario(Scenario scenario, boolean reservationDeposit) {
        PaymentPreparation preparation = reservationDeposit
                ? paymentService.prepareReservationDeposit(
                        new PrepareReservationDepositCommand(
                                String.valueOf(scenario.reservationId()),
                                scenario.consumerId(),
                                AMOUNT_MINOR,
                                "KRW",
                                PAYMENT_AT.plusSeconds(3_600),
                                1L,
                                uuid("prepare-boundary:" + scenario.reservationId())))
                : paymentService.prepareWaitingReservationDeposit(
                        new PrepareWaitingReservationDepositCommand(
                                String.valueOf(scenario.reservationId()),
                                scenario.consumerId(),
                                AMOUNT_MINOR,
                                "KRW",
                                PAYMENT_AT.plusSeconds(3_600),
                                1L,
                                uuid("prepare-boundary:" + scenario.reservationId())));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(),
                        "transaction-boundary-" + scenario.reservationId(),
                        ProviderStatus.PAID,
                        AMOUNT_MINOR,
                        "KRW"));
        assertThat(paymentService.confirmPayment(new ConfirmPaymentCommand(
                preparation.paymentId(),
                scenario.consumerId(),
                preparation.portOnePaymentId(),
                uuid("confirm-boundary:" + scenario.reservationId()))).status())
                .isEqualTo(PaymentStatus.PAID);
        return new FinancialScenario(
                scenario.operatorId(),
                scenario.storeId(),
                scenario.consumerId(),
                scenario.reservationId(),
                preparation.paymentId(),
                preparation.portOnePaymentId());
    }

    private FinancialScenario paidV2Scenario(boolean withCapacity) {
        Scenario scenario = scenario(withCapacity);
        PaymentPreparation preparation = paymentService.prepareReservationDeposit(
                new PrepareReservationDepositCommand(
                        String.valueOf(scenario.reservationId()),
                        scenario.consumerId(),
                        AMOUNT_MINOR,
                        "KRW",
                        PAYMENT_AT.plusSeconds(3_600),
                        1L,
                        uuid("prepare:" + scenario.reservationId())));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(),
                        "transaction-" + scenario.reservationId(),
                        ProviderStatus.PAID,
                        AMOUNT_MINOR,
                        "KRW"));
        assertThat(paymentService.confirmPayment(new ConfirmPaymentCommand(
                preparation.paymentId(),
                scenario.consumerId(),
                preparation.portOnePaymentId(),
                uuid("confirm:" + scenario.reservationId()))).status())
                .isEqualTo(PaymentStatus.PAID);
        clearInvocations(providerClient);
        seedCompletedDepositProcess(scenario, preparation);
        return new FinancialScenario(
                scenario.operatorId(),
                scenario.storeId(),
                scenario.consumerId(),
                scenario.reservationId(),
                preparation.paymentId(),
                preparation.portOnePaymentId());
    }

    private Scenario scenario(boolean withCapacity) {
        return transactions.execute(status -> {
            int sequence = SEQUENCE.incrementAndGet();
            StoreOperatorAccount operator = operatorRepository.saveAndFlush(
                    StoreOperatorAccount.create(
                            "lifecycle-owner-" + sequence + "@example.com",
                            "hashed-password",
                            "owner"));
            Store store = storeRepository.saveAndFlush(Store.create(
                    operator.getId(),
                    Long.toString(8_100_000_000L + sequence),
                    BusinessType.CAFE,
                    "Lifecycle Store " + sequence,
                    "",
                    Region.SEOUL,
                    "fixture-address",
                    "CAFE_BAKERY",
                    Set.of(),
                    true,
                    true,
                    false,
                    "Asia/Seoul",
                    LocalDateTime.of(2026, 8, 1, 9, 0),
                    "STORE_ONBOARDING_REQUIRED_TERMS_V1"));
            ConsumerAccount consumer = consumerRepository.saveAndFlush(
                    ConsumerAccount.createWithContact(
                            "lifecycle-consumer-" + sequence + "@example.com",
                            "hashed-password",
                            "consumer",
                            String.format(Locale.ROOT, "010%08d", sequence),
                            "opaque-lifecycle-contact-" + sequence));
            ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                    store.getId(), 1L, 60, 60, 0);
            policy.activate(Instant.parse("2026-08-01T00:00:00Z"), "lifecycle fixture");
            ReservationTimeSnapshot time = ReservationTimeSnapshot.calculate(
                    policy,
                    LocalDateTime.of(SERVICE_DATE, START_TIME),
                    ZoneId.of("Asia/Seoul"),
                    null);
            Reservation reservation = reservationRepository.saveAndFlush(Reservation.confirm(
                    consumer.getId(),
                    store.getId(),
                    store.getName(),
                    time,
                    PartyComposition.of(2, 0, 0),
                    ReservationContactSnapshot.contactable(
                            "opaque-lifecycle-target-" + sequence),
                    1L,
                    new ReservationCancellationPolicyVersion(1L),
                    PAYMENT_AT));
            if (withCapacity) {
                ReservationCapacityBucket bucket = capacityBucketRepository.saveAndFlush(
                        ReservationCapacityBucket.create(
                                store.getId(),
                                SERVICE_DATE,
                                START_TIME,
                                START_TIME.plusHours(1),
                                10,
                                5,
                                2,
                                1,
                                1,
                                10,
                                true,
                                1L));
                allocationRepository.saveAndFlush(ReservationCapacityAllocation.allocate(
                        reservation.getId(), bucket.getId(), 2, 1L));
            }
            return new Scenario(
                    operator.getId(), store.getId(), consumer.getId(), reservation.getId());
        });
    }

    private void seedCompletedDepositProcess(
            Scenario scenario,
            PaymentPreparation preparation
    ) {
        long holdId = 1_000_000L + scenario.reservationId();
        jdbcTemplate.update(
                "UPDATE reservations SET cancellation_policy_version = 2 "
                        + "WHERE reservation_id = ?",
                scenario.reservationId());
        jdbcTemplate.update("""
                INSERT INTO reservation_holds (
                    reservation_hold_id, consumer_account_id, store_id,
                    store_name_snapshot, service_date, start_at, service_end_at,
                    occupancy_end_at, time_zone_id_snapshot, start_offset_seconds,
                    service_end_offset_seconds, occupancy_end_offset_seconds,
                    slot_interval_minutes, service_duration_minutes,
                    turnover_duration_minutes, reservation_time_policy_store_id,
                    reservation_policy_version, adult_count, child_count, infant_count,
                    notification_target_reference, contact_available_at_confirmation,
                    capacity_policy_version, cancellation_policy_version, status,
                    status_version, creation_command_id, created_at, expires_at
                )
                SELECT ?, consumer_account_id, store_id, store_name_snapshot,
                       service_date, start_at, service_end_at, occupancy_end_at,
                       time_zone_id_snapshot, start_offset_seconds,
                       service_end_offset_seconds, occupancy_end_offset_seconds,
                       slot_interval_minutes, service_duration_minutes,
                       turnover_duration_minutes, reservation_time_policy_store_id,
                       reservation_policy_version, adult_count, child_count, infant_count,
                       notification_target_reference, contact_available_at_confirmation,
                       capacity_policy_version, 2, 'CONFIRMED', 0,
                       CONCAT('deposit-lifecycle-it:', reservation_id),
                       created_at, DATE_ADD(created_at, INTERVAL 10 MINUTE)
                  FROM reservations
                 WHERE reservation_id = ?
                """, holdId, scenario.reservationId());
        jdbcTemplate.update("""
                INSERT INTO reservation_deposit_processes (
                    reservation_hold_id, consumer_account_id, status, expires_at,
                    payment_id, portone_payment_id, payment_order_name,
                    payment_amount_minor, payment_currency, payment_source_expires_at,
                    payment_preparation_status, store_deposit_policy_version,
                    deposit_rate_percent, deposit_algorithm_version, deposit_party_size,
                    deposit_amount_minor, deposit_currency, representative_menu_version,
                    representative_menu_price_total, representative_menu_count,
                    abandonment_requested, resources_protected, resources_protected_at,
                    final_reservation_id, requested_at, completed_at
                ) VALUES (
                    ?, ?, 'COMPLETED', ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    'READY', 1,
                    20, 1, 2,
                    ?, ?, 1,
                    150000, 1,
                    FALSE, TRUE, ?,
                    ?, ?, ?
                )
                """,
                holdId,
                scenario.consumerId(),
                Timestamp.from(preparation.sourceExpiresAt()),
                preparation.paymentId(),
                preparation.portOnePaymentId(),
                preparation.orderName(),
                preparation.amountMinor(),
                preparation.currency(),
                Timestamp.from(preparation.sourceExpiresAt()),
                preparation.amountMinor(),
                preparation.currency(),
                Timestamp.from(PAYMENT_AT.plusSeconds(60)),
                scenario.reservationId(),
                Timestamp.from(PAYMENT_AT),
                Timestamp.from(PAYMENT_AT.plusSeconds(120)));
    }

    private void stubSuccessfulFullRefund() {
        when(providerClient.cancelPayment(
                anyString(), anyString(), anyLong(), eq("KRW"),
                eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenAnswer(invocation -> new ProviderCancellation(
                        "cancel-" + invocation.getArgument(1, String.class),
                        ProviderStatus.CANCELLED,
                        invocation.getArgument(2, Long.class),
                        invocation.getArgument(3, String.class)));
    }

    private RequestRefundCommand refundCommand(
            FinancialScenario scenario,
            String suffix
    ) {
        return new RequestRefundCommand(
                scenario.paymentId(),
                "store-status-read:" + scenario.reservationId() + ":" + suffix,
                AMOUNT_MINOR,
                "STORE_STATUS_READ_TEST",
                2L,
                uuid("store-status-read:" + scenario.reservationId() + ":" + suffix));
    }

    private void assertCompletedFinancialResult(
            FinancialScenario scenario,
            String reservationStatus,
            String sourceEventType,
            String responsibilityCode,
            int targetRate,
            PaymentStatus paymentStatus,
            long refundCount
    ) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE reservation_id = ?",
                String.class,
                scenario.reservationId())).isEqualTo(reservationStatus);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT source_event_type, responsibility_code,
                       target_refund_rate_basis_points, status,
                       payment_disposition_status
                  FROM reservation_deposit_disposition_obligations
                 WHERE reservation_id = ?
                """, scenario.reservationId()))
                .containsEntry("source_event_type", sourceEventType)
                .containsEntry("responsibility_code", responsibilityCode)
                .containsEntry("target_refund_rate_basis_points", targetRate)
                .containsEntry("status", "COMPLETED")
                .containsEntry("payment_disposition_status", "COMPLETED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_deposit_disposition_obligations
                 WHERE reservation_id = ?
                """, Long.class, scenario.reservationId())).isOne();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT d.source_event_type, d.responsibility_code,
                       d.target_refund_rate_basis_points, d.status,
                       d.completed_refund_amount_minor
                  FROM reservation_deposit_dispositions d
                  JOIN payments p ON p.payment_pk = d.payment_pk
                 WHERE p.payment_id = ?
                """, scenario.paymentId()))
                .containsEntry("source_event_type", sourceEventType)
                .containsEntry("responsibility_code", responsibilityCode)
                .containsEntry("target_refund_rate_basis_points", targetRate)
                .containsEntry("status", "COMPLETED")
                .containsEntry(
                        "completed_refund_amount_minor",
                        targetRate == 0 ? 0L : AMOUNT_MINOR);
        assertThat(paymentStatus(scenario.paymentId())).isEqualTo(paymentStatus.name());
        assertThat(paymentRefundCount(scenario.paymentId())).isEqualTo(refundCount);
    }

    private void assertNoFinancialDisposition(FinancialScenario scenario) {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_deposit_disposition_obligations
                 WHERE reservation_id = ?
                """, Long.class, scenario.reservationId())).isZero();
        assertThat(paymentDispositionCount(scenario.paymentId())).isZero();
        assertThat(paymentRefundCount(scenario.paymentId())).isZero();
        assertThat(paymentStatus(scenario.paymentId())).isEqualTo("PAID");
        verify(providerClient, never()).cancelPayment(
                anyString(), anyString(), anyLong(), anyString(), anyString());
    }

    private int runWorkerAfterObligation(
            CountDownLatch ready,
            CountDownLatch start,
            long reservationId
    ) {
        ready.countDown();
        await(start, "race start");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                      FROM reservation_deposit_disposition_obligations
                     WHERE reservation_id = ?
                    """, Long.class, reservationId);
            if (count != null && count == 1L) {
                return dispositionJob.runOnce("lifecycle-race-worker", 10);
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("terminal obligation was not committed within 10 seconds");
    }

    private static TerminalAttempt attemptTerminal(
            CountDownLatch ready,
            CountDownLatch start,
            AtomicReference<Terminal> winner,
            Terminal candidate,
            ThrowingCommand command
    ) {
        ready.countDown();
        await(start, "race start");
        try {
            command.run();
            winner.compareAndSet(null, candidate);
            return TerminalAttempt.success(candidate);
        } catch (ServiceException expectedLoser) {
            return TerminalAttempt.failure(candidate, expectedLoser.getErrorCode());
        }
    }

    private void replayWinner(
            FinancialScenario scenario,
            Terminal winner,
            IdempotencyKey cancellationKey,
            IdempotencyKey fulfillmentKey,
            IdempotencyKey noShowKey
    ) {
        assertThat(winner).isNotNull();
        switch (winner) {
            case CANCELLED -> assertThat(cancellationFacade.cancelByStoreOperator(
                    scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                    cancellationKey, new StoreCancellationRequest("race")).data().status())
                    .isEqualTo("CANCELLED");
            case FULFILLED -> assertThat(fulfillmentFacade.fulfill(
                    scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                    fulfillmentKey, new ReservationFulfillmentRequest()).data().status())
                    .isEqualTo("FULFILLED");
            case NO_SHOW -> assertThat(visitFacade.markNoShow(
                    scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                    noShowKey,
                    new ReservationNoShowRequest(
                            ReservationNoShowReason.USER_CAUSE_CANDIDATE)).data().status())
                    .isEqualTo("NO_SHOW");
        }
    }

    private String obligationStatus(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM reservation_deposit_disposition_obligations "
                        + "WHERE reservation_id = ?",
                String.class,
                reservationId);
    }

    private String paymentStatus(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE payment_id = ?",
                String.class,
                paymentId);
    }

    private String paymentSourceType(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT source_type FROM payments WHERE payment_id = ?",
                String.class,
                paymentId);
    }

    private long paymentDispositionCount(String paymentId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_deposit_dispositions d
                  JOIN payments p ON p.payment_pk = d.payment_pk
                 WHERE p.payment_id = ?
                """, Long.class, paymentId);
        return count == null ? 0L : count;
    }

    private long paymentRefundCount(String paymentId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM payment_refunds r
                  JOIN payments p ON p.payment_pk = r.payment_pk
                 WHERE p.payment_id = ?
                """, Long.class, paymentId);
        return count == null ? 0L : count;
    }

    private long paymentLedgerCount(String paymentId, String entryType) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM payment_ledger_entries l
                  JOIN payments p ON p.payment_pk = l.payment_pk
                 WHERE p.payment_id = ?
                   AND l.entry_type = ?
                """, Long.class, paymentId, entryType);
        return count == null ? 0L : count;
    }

    private static void await(CountDownLatch latch, String boundary) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError(boundary + " was not reached within 10 seconds");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting " + boundary, exception);
        }
    }

    private static IdempotencyKey key(String namespace, long reservationId) {
        return IdempotencyKey.parse(uuid(namespace + ":" + reservationId));
    }

    private static String uuid(String source) {
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private enum Terminal {
        CANCELLED,
        FULFILLED,
        NO_SHOW
    }

    private record TerminalAttempt(Terminal candidate, boolean succeeded, ErrorCode errorCode) {

        private static TerminalAttempt success(Terminal candidate) {
            return new TerminalAttempt(candidate, true, null);
        }

        private static TerminalAttempt failure(Terminal candidate, ErrorCode errorCode) {
            return new TerminalAttempt(candidate, false, errorCode);
        }
    }

    private record ExpectedTerminal(
            String reservationStatus,
            String sourceEventType,
            String responsibilityCode,
            int targetRate,
            PaymentStatus paymentStatus,
            long refundCount
    ) {
        private static ExpectedTerminal from(Terminal terminal) {
            return switch (terminal) {
                case CANCELLED -> new ExpectedTerminal(
                        "CANCELLED", "RESERVATION_CANCELLED", "STORE_RESPONSIBLE",
                        10_000, PaymentStatus.REFUNDED, 1L);
                case FULFILLED -> new ExpectedTerminal(
                        "FULFILLED", "RESERVATION_FULFILLED", "CONSUMER",
                        10_000, PaymentStatus.REFUNDED, 1L);
                case NO_SHOW -> new ExpectedTerminal(
                        "NO_SHOW", "RESERVATION_NO_SHOW", "CONSUMER",
                        0, PaymentStatus.PAID, 0L);
            };
        }
    }

    private record Scenario(
            long operatorId,
            long storeId,
            long consumerId,
            long reservationId
    ) {
    }

    private record FinancialScenario(
            long operatorId,
            long storeId,
            long consumerId,
            long reservationId,
            String paymentId,
            String portOnePaymentId
    ) {
    }

    private record DepositCreationInputs(
            long operatorId,
            long storeId,
            long consumerId
    ) {
    }

    @FunctionalInterface
    private interface ThrowingCommand {
        void run();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {

        @Bean
        @Primary
        MutableClock lifecycleClock() {
            return new MutableClock(PAYMENT_AT);
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        void set(Instant value) {
            current.set(value);
        }

        void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
