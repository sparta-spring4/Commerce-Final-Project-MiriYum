package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.payment.dto.PaymentContracts.ConfirmPaymentCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.port.PaymentProviderClient;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderCancellation;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderPayment;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderStatus;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingClosureCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamTransitionRequest;
import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.service.WaitingReservationConversionService.BeginCommand;
import com.miriyum.domain.reservation.waiting.service.WaitingReservationConversionService.CompletionCommand;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-b")
@Timeout(30)
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false",
            "miriyum.waiting.closure.initial-delay-ms=600000",
            "miriyum.waiting.compensation.initial-delay-ms=600000",
            "miriyum.payment.cursor-secret=test-history-cursor-secret-with-enough-entropy",
            "miriyum.payment.portone.api-secret=test-api-secret",
            "miriyum.payment.portone.webhook-secret=whsec_dGVzdC1zZWNyZXQ=",
            "miriyum.payment.portone.store-id=store-1"
        })
class WaitingLedgerConcurrencyIT {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 13);

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired WaitingCreationService creationService;
    @Autowired WaitingCommandFacade commandFacade;
    @Autowired WaitingClosureService closureService;
    @Autowired WaitingReservationConversionService conversionService;
    @Autowired WaitingConversionCompensationService compensationService;
    @Autowired PaymentService paymentService;
    @Autowired WaitingTeamRepository teams;
    @Autowired WaitingActiveMembershipRepository memberships;
    @Autowired StoreRepository stores;
    @Autowired StoreOperatorAccountRepository operators;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean PaymentProviderClient providerClient;
    @MockitoBean WaitingOperatingIntervalPort intervalPort;

    @BeforeEach
    void clean() {
        when(intervalPort.lockCurrent(anyLong(), any(LocalDate.class), any(Instant.class)))
                .thenAnswer(invocation -> List.of(openInterval(
                        invocation.getArgument(0), invocation.getArgument(1))));
        for (String table : new String[]{"waiting_conversion_compensations",
                "payment_webhook_receipts", "payment_ledger_entries", "payment_refunds",
                "payment_attempts", "payments",
                "waiting_status_events", "waiting_transition_audits",
                "waiting_closure_job_items", "waiting_closure_jobs", "waiting_active_memberships",
                "waiting_teams", "waiting_queue_sequences", "idempotency_commands",
                "waiting_setting_audits", "waiting_settings",
                "store_tag_assignment", "stores", "store_operator_accounts", "consumer_accounts"}) {
            jdbc.execute("DELETE FROM " + table);
        }
    }

    @Test
    void parallelCreationAllocatesUniqueMonotonicSequencesAndMemberships() throws Exception {
        Fixture fixture = fixture(8);
        List<Attempt<WaitingCommandResult>> attempts = runTogether(8, index -> creationService.create(
                fixture.storeId(), fixture.consumerIds().get(index), BUSINESS_DATE, 2,
                WaitingSource.REMOTE, key(100 + index)));

        assertThat(attempts).allMatch(Attempt::succeeded);
        assertThat(jdbc.queryForList(
                "SELECT queue_sequence FROM waiting_teams ORDER BY queue_sequence", Long.class))
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(count("waiting_active_memberships")).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT next_sequence FROM waiting_queue_sequences", Long.class))
                .isEqualTo(9L);
        assertThat(count("waiting_transition_audits")).isEqualTo(8);
        assertThat(count("waiting_status_events")).isEqualTo(8);
    }

    @Test
    void missingOrPausedSettingRejectsNewWaitingWithoutCreatingLedgerRows() {
        Fixture fixture = fixture(2);
        jdbc.update("DELETE FROM waiting_settings WHERE store_id=?", fixture.storeId());

        assertThatThrownBy(() -> creationService.create(
                fixture.storeId(), fixture.consumerIds().getFirst(), BUSINESS_DATE, 2,
                WaitingSource.REMOTE, key(90)))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_RECEPTION_CLOSED));

        insertWaitingSetting(fixture.storeId(), true, "PAUSED", 1L);
        assertThatThrownBy(() -> creationService.create(
                fixture.storeId(), fixture.consumerIds().get(1), BUSINESS_DATE, 2,
                WaitingSource.REMOTE, key(91)))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_RECEPTION_CLOSED));

        assertThat(count("waiting_teams")).isZero();
        assertThat(count("waiting_active_memberships")).isZero();
        assertThat(count("waiting_transition_audits")).isZero();
        assertThat(count("waiting_status_events")).isZero();
    }

    @Test
    void crossStoreDuplicateActiveMembershipRacePreservesWinnerThenAllowsCreationAfterCancellation() throws Exception {
        Fixture fixture = fixture(1);
        long consumerId = fixture.consumerIds().getFirst();
        long otherStoreId = createStore(fixture.operatorId());
        List<Attempt<WaitingCommandResult>> attempts = runTogether(2, index -> creationService.create(
                index == 0 ? fixture.storeId() : otherStoreId,
                consumerId, BUSINESS_DATE, 2, WaitingSource.REMOTE, key(200 + index)));

        assertThat(attempts.stream().filter(Attempt::succeeded)).hasSize(1);
        assertThat(attempts.stream().filter(attempt -> !attempt.succeeded()).map(Attempt::failure))
                .singleElement()
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.ACCOUNT_ACTIVE_WAITING_EXISTS));
        WaitingTeam winner = teams.findById(Long.parseLong(attempts.stream()
                .filter(Attempt::succeeded)
                .findFirst()
                .orElseThrow()
                .result()
                .data()
                .waitingTeamId())).orElseThrow();
        long winnerStoreId = winner.getStoreId();
        long losingStoreId = winnerStoreId == fixture.storeId() ? otherStoreId : fixture.storeId();
        assertThat(winner.getStatus()).isEqualTo(WaitingTeamStatus.WAITING);
        assertThat(winner.getVersion()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT store_id FROM waiting_active_memberships WHERE consumer_account_id=?", Long.class, consumerId))
                .isEqualTo(winnerStoreId);
        assertThat(count("waiting_teams")).isOne();
        assertThat(count("waiting_active_memberships")).isOne();
        assertThat(count("waiting_transition_audits")).isOne();
        assertThat(count("waiting_status_events")).isOne();
        assertThat(count("idempotency_commands")).isOne();

        commandFacade.cancel(fixture.operatorId(), winnerStoreId, winner.getId(), key(202),
                new WaitingTeamTransitionRequest(winner.getVersion()));

        assertThat(teams.findById(winner.getId()).orElseThrow().getStatus()).isEqualTo(WaitingTeamStatus.CANCELLED);
        assertThat(count("waiting_active_memberships")).isZero();

        WaitingCommandResult retry = creationService.create(losingStoreId, consumerId, BUSINESS_DATE, 2,
                WaitingSource.REMOTE, key(203));

        assertThat(retry.data().storeId()).isEqualTo(Long.toString(losingStoreId));
        assertThat(teams.findById(Long.parseLong(retry.data().waitingTeamId())).orElseThrow().getStatus())
                .isEqualTo(WaitingTeamStatus.WAITING);
        assertThat(count("waiting_teams")).isEqualTo(2);
        assertThat(count("waiting_active_memberships")).isOne();
        assertThat(count("waiting_transition_audits")).isEqualTo(3);
        assertThat(count("waiting_status_events")).isEqualTo(3);
        assertThat(count("idempotency_commands")).isEqualTo(3);
    }

    @Test
    void sameVersionCallCancelRaceCommitsExactlyOneTransition() throws Exception {
        Fixture fixture = fixture(1);
        WaitingCommandResult created = creationService.create(fixture.storeId(), fixture.consumerIds().getFirst(),
                BUSINESS_DATE, 2, WaitingSource.REMOTE, key(300));
        long teamId = Long.parseLong(created.data().waitingTeamId());

        List<Attempt<WaitingCommandResult>> attempts = runTogether(2, index -> index == 0
                ? commandFacade.call(fixture.operatorId(), fixture.storeId(), teamId, key(301),
                        new WaitingTeamTransitionRequest(0L))
                : commandFacade.cancel(fixture.operatorId(), fixture.storeId(), teamId, key(302),
                        new WaitingTeamTransitionRequest(0L)));

        assertThat(attempts.stream().filter(Attempt::succeeded)).hasSize(1);
        assertThat(attempts.stream().filter(attempt -> !attempt.succeeded()).map(Attempt::failure))
                .singleElement()
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_VERSION_CONFLICT));
        WaitingTeam stored = teams.findById(teamId).orElseThrow();
        assertThat(stored.getVersion()).isOne();
        assertThat(stored.getStatus()).isIn(WaitingTeamStatus.CALLED, WaitingTeamStatus.CANCELLED);
        assertThat(count("waiting_transition_audits")).isEqualTo(2);
        assertThat(count("waiting_status_events")).isEqualTo(2);
        assertThat(count("waiting_active_memberships"))
                .isEqualTo(stored.getStatus() == WaitingTeamStatus.CALLED ? 1 : 0);
    }

    @Test
    void concurrentCallsRemainBlockedWhileEarlierCallIsUnresolved() throws Exception {
        Fixture fixture = fixture(3);
        createTeams(fixture, 350);
        List<Long> teamIds = jdbc.queryForList(
                "SELECT waiting_team_id FROM waiting_teams ORDER BY queue_sequence", Long.class);
        commandFacade.call(
                fixture.operatorId(), fixture.storeId(), teamIds.getFirst(), key(360),
                new WaitingTeamTransitionRequest(0L));

        List<Attempt<WaitingCommandResult>> attempts = runTogether(2, index ->
                commandFacade.call(
                        fixture.operatorId(),
                        fixture.storeId(),
                        teamIds.get(index + 1),
                        key(361 + index),
                        new WaitingTeamTransitionRequest(0L)));

        assertThat(attempts).allSatisfy(attempt -> {
            assertThat(attempt.succeeded()).isFalse();
            assertThat(attempt.failure()).isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_NOT_FIFO_HEAD));
        });
        assertThat(jdbc.queryForList(
                "SELECT status FROM waiting_teams ORDER BY queue_sequence", String.class))
                .containsExactly("CALLED", "WAITING", "WAITING");
        assertThat(count("waiting_transition_audits")).isEqualTo(4);
        assertThat(count("waiting_status_events")).isEqualTo(4);
    }

    @Test
    void concurrentSameClosureKeyProducesOneImmutableJobAndSnapshot() throws Exception {
        Fixture fixture = fixture(2);
        createTeams(fixture, 400);
        setWaitingSettingDisabledVersion(fixture.storeId(), 7L);
        IdempotencyKey closureKey = key(410);

        List<Attempt<WaitingClosureCommandResult>> attempts = runTogether(2, ignored ->
                new TransactionTemplate(transactionManager).execute(status ->
                        closureService.startClosure(fixture.operatorId(), fixture.storeId(), closureKey, 7L)));

        assertThat(attempts).allMatch(Attempt::succeeded);
        assertThat(attempts.get(0).result()).isEqualTo(attempts.get(1).result());
        assertThat(count("waiting_closure_jobs")).isOne();
        assertThat(count("waiting_closure_job_items")).isEqualTo(2);
        assertThat(count("idempotency_commands")).isEqualTo(3);
    }

    @Test
    void convertingTeamCancelAndClosureRaceProducesOneTerminalEffectAndRemovesMembership() throws Exception {
        Fixture fixture = fixture(1);
        WaitingCommandResult created = creationService.create(
                fixture.storeId(), fixture.consumerIds().getFirst(), BUSINESS_DATE, 2,
                WaitingSource.REMOTE, key(450));
        long teamId = Long.parseLong(created.data().waitingTeamId());
        jdbc.update("UPDATE waiting_teams SET status='RESERVATION_CONVERTING' WHERE waiting_team_id=?", teamId);
        setWaitingSettingDisabledVersion(fixture.storeId(), 7L);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                closureService.startClosure(fixture.operatorId(), fixture.storeId(), key(451), 7L));
        WaitingClosureClaim claim = closureService.claimPendingItems(
                "converting-race", 1, Duration.ofSeconds(30), 0L).getFirst();

        List<Attempt<Object>> attempts = runTogether(2, index -> index == 0
                ? commandFacade.cancel(fixture.operatorId(), fixture.storeId(), teamId, key(452),
                        new WaitingTeamTransitionRequest(0L))
                : closureService.processClaimedItem(claim));

        assertThat(attempts.get(1).succeeded()).isTrue();
        assertThat(attempts.get(1).result()).isEqualTo(Boolean.TRUE);
        if (!attempts.getFirst().succeeded()) {
            assertThat(attempts.getFirst().failure()).isInstanceOfSatisfying(ServiceException.class,
                    failure -> assertThat(failure.getErrorCode())
                            .isEqualTo(ReservationErrorCode.WAITING_VERSION_CONFLICT));
        }
        WaitingTeam stored = teams.findById(teamId).orElseThrow();
        assertThat(stored.getStatus()).isIn(WaitingTeamStatus.CANCELLED, WaitingTeamStatus.CLOSED_BY_STORE);
        assertThat(stored.getVersion()).isOne();
        assertThat(count("waiting_active_memberships")).isZero();
        assertThat(count("waiting_transition_audits")).isEqualTo(2);
        assertThat(count("waiting_status_events")).isEqualTo(2);
    }

    @Test
    void paidConversionAndOperatorCancelRaceCommitExactlyOneTerminalTransition() throws Exception {
        Fixture fixture = fixture(1);
        WaitingCommandResult created = creationService.create(
                fixture.storeId(), fixture.consumerIds().getFirst(), BUSINESS_DATE, 2,
                WaitingSource.REMOTE, key(460));
        long teamId = Long.parseLong(created.data().waitingTeamId());
        PaymentPreparation preparation = conversionService.begin(new BeginCommand(
                teamId, 0L, 12_000L, "KRW", Instant.now().plusSeconds(3_600), 3L,
                key(461).value()));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(), "waiting-transaction-" + teamId,
                        ProviderStatus.PAID, 12_000L, "KRW"));
        paymentService.confirmPayment(new ConfirmPaymentCommand(
                preparation.paymentId(), fixture.consumerIds().getFirst(),
                preparation.portOnePaymentId(), key(462).value()));

        WaitingTeam converting = teams.findById(teamId).orElseThrow();
        assertThat(converting.getStatus()).isEqualTo(WaitingTeamStatus.RESERVATION_CONVERTING);
        assertThat(converting.getVersion()).isEqualTo(1L);

        List<Attempt<Object>> attempts = runTogether(2, index -> index == 0
                ? conversionService.completeVerified(
                        new CompletionCommand(teamId, preparation.paymentId(), 9_001L))
                : commandFacade.cancel(
                        fixture.operatorId(), fixture.storeId(), teamId, key(463),
                        new WaitingTeamTransitionRequest(1L)));

        assertThat(attempts.stream().filter(attempt -> !attempt.succeeded()))
                .allSatisfy(attempt -> assertThat(attempt.failure())
                        .isInstanceOfSatisfying(ServiceException.class, failure ->
                                assertThat(failure.getErrorCode())
                                        .isEqualTo(ReservationErrorCode.WAITING_VERSION_CONFLICT)));
        WaitingTeam stored = teams.findById(teamId).orElseThrow();
        assertThat(stored.getStatus()).isIn(
                WaitingTeamStatus.RESERVATION_CONVERTED, WaitingTeamStatus.CANCELLED);
        assertThat(stored.getVersion()).isEqualTo(2L);
        assertThat(count("waiting_active_memberships")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM waiting_transition_audits
                 WHERE waiting_team_id = ?
                   AND after_status IN ('RESERVATION_CONVERTED', 'CANCELLED')
                """, Long.class, teamId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM waiting_status_events
                 WHERE waiting_team_id = ?
                   AND public_status IN ('RESERVATION_CONVERTED', 'CANCELLED')
                """, Long.class, teamId)).isEqualTo(1L);
        if (stored.getStatus() == WaitingTeamStatus.RESERVATION_CONVERTED) {
            assertThat(stored.getReservationReferenceId()).isEqualTo(9_001L);
            assertThat(count("waiting_conversion_compensations")).isZero();
        } else {
            assertThat(stored.getReservationReferenceId()).isNull();
            assertThat(count("waiting_conversion_compensations")).isOne();
        }
    }

    @Test
    void cancelledPaidConversionCallbackConvergesOneCompensationAndRealRefundLedger() {
        Fixture fixture = fixture(1);
        WaitingCommandResult created = creationService.create(
                fixture.storeId(), fixture.consumerIds().getFirst(), BUSINESS_DATE, 2,
                WaitingSource.REMOTE, key(470));
        long teamId = Long.parseLong(created.data().waitingTeamId());
        PaymentPreparation preparation = conversionService.begin(new BeginCommand(
                teamId, 0L, 12_000L, "KRW", Instant.now().plusSeconds(3_600), 3L,
                key(471).value()));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(), "waiting-terminal-" + teamId,
                        ProviderStatus.PAID, 12_000L, "KRW"));
        paymentService.confirmPayment(new ConfirmPaymentCommand(
                preparation.paymentId(), fixture.consumerIds().getFirst(),
                preparation.portOnePaymentId(), key(472).value()));
        commandFacade.cancel(
                fixture.operatorId(), fixture.storeId(), teamId, key(473),
                new WaitingTeamTransitionRequest(1L));

        CompletionCommand callback = new CompletionCommand(teamId, preparation.paymentId(), 9_002L);
        assertThat(conversionService.completeVerified(callback)).isFalse();
        assertThat(conversionService.completeVerified(callback)).isFalse();

        WaitingTeam cancelled = teams.findById(teamId).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(WaitingTeamStatus.CANCELLED);
        assertThat(cancelled.getReservationReferenceId()).isNull();
        assertThat(count("waiting_active_memberships")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM waiting_transition_audits
                 WHERE waiting_team_id = ? AND after_status = 'CANCELLED'
                """, Long.class, teamId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM waiting_status_events
                 WHERE waiting_team_id = ? AND public_status = 'CANCELLED'
                """, Long.class, teamId)).isEqualTo(1L);
        assertThat(count("waiting_conversion_compensations")).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT source_event_id, idempotency_key, payment_id,
                       refund_amount_minor, currency, refund_policy_version, reason_code, status
                  FROM waiting_conversion_compensations
                 WHERE waiting_team_id = ?
                """, teamId))
                .containsEntry("source_event_id",
                        "waiting-conversion-terminal:" + teamId + ":CANCELLED:"
                                + preparation.paymentId())
                .containsEntry("payment_id", preparation.paymentId())
                .containsEntry("refund_amount_minor", 12_000L)
                .containsEntry("currency", "KRW")
                .containsEntry("refund_policy_version", 3L)
                .containsEntry("reason_code", "WAITING_CANCELLED")
                .containsEntry("status", "PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT idempotency_key FROM waiting_conversion_compensations WHERE waiting_team_id=?",
                String.class, teamId))
                .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(12_000L),
                eq("KRW"), eq("WAITING_CANCELLED")))
                .thenReturn(new ProviderCancellation(
                        "waiting-cancellation-" + teamId, ProviderStatus.CANCELLED,
                        12_000L, "KRW"));
        List<WaitingCompensationClaim> claims = compensationService.claimPending(
                "cancelled-callback-it", 1, Duration.ofSeconds(30), 0L);

        assertThat(claims).hasSize(1);
        assertThat(compensationService.processClaim(claims.getFirst())).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM waiting_conversion_compensations WHERE waiting_team_id=?",
                String.class, teamId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM payments WHERE payment_id=?",
                String.class, preparation.paymentId())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_refunds WHERE status='COMPLETED'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                 WHERE entry_type = 'REFUND_COMPLETED'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void paidTerminalWithoutCallbackIsRecoveredAndRefundedByDurableReconciliation() {
        Fixture fixture = fixture(1);
        PaidConversion paid = paidConversion(fixture, 540);
        commandFacade.cancel(
                fixture.operatorId(), fixture.storeId(), paid.teamId(), key(543),
                new WaitingTeamTransitionRequest(1L));
        WaitingConversionCompensationRunner recoveryRunner =
                new WaitingConversionCompensationRunner(
                        compensationService, "missing-callback-it", Duration.ofSeconds(30));

        assertThat(count("waiting_conversion_compensations")).isZero();
        assertThat(recoveryRunner.recoverMissingTerminalCompensations()).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT payment_id, refund_amount_minor, currency,
                       refund_policy_version, reason_code, status
                  FROM waiting_conversion_compensations
                 WHERE waiting_team_id = ?
                """, paid.teamId()))
                .containsEntry("payment_id", paid.preparation().paymentId())
                .containsEntry("refund_amount_minor", 12_000L)
                .containsEntry("currency", "KRW")
                .containsEntry("refund_policy_version", 3L)
                .containsEntry("reason_code", "WAITING_CANCELLED")
                .containsEntry("status", "PENDING");

        when(providerClient.cancelPayment(
                eq(paid.preparation().portOnePaymentId()), anyString(), eq(12_000L),
                eq("KRW"), eq("WAITING_CANCELLED")))
                .thenReturn(new ProviderCancellation(
                        "waiting-recovered-cancellation-" + paid.teamId(),
                        ProviderStatus.CANCELLED, 12_000L, "KRW"));
        WaitingCompensationClaim claim = compensationService.claimPending(
                "missing-callback-it", 1, Duration.ofSeconds(30), 0L).getFirst();

        assertThat(compensationService.processClaim(claim)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM waiting_conversion_compensations WHERE waiting_team_id=?",
                String.class, paid.teamId())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM payments WHERE payment_id=?",
                String.class, paid.preparation().paymentId())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_refunds WHERE status='COMPLETED'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void terminalCallbackAndDurableReconciliationConvergeOnOneCompensation() throws Exception {
        Fixture fixture = fixture(1);
        PaidConversion paid = paidConversion(fixture, 550);
        commandFacade.cancel(
                fixture.operatorId(), fixture.storeId(), paid.teamId(), key(553),
                new WaitingTeamTransitionRequest(1L));
        CompletionCommand callback = new CompletionCommand(
                paid.teamId(), paid.preparation().paymentId(), 9_010L);
        long recoveryUpperBound =
                compensationService.findTerminalCompensationScanUpperBoundId();
        assertThat(compensationService.findTerminalCompensationScanIds(
                Long.MAX_VALUE, recoveryUpperBound, 100)).contains(paid.teamId());

        List<Attempt<Object>> attempts = runTogether(2, index -> index == 0
                ? conversionService.completeVerified(callback)
                : compensationService.reconcileMissingTerminalCompensation(paid.teamId()));

        assertThat(attempts).allMatch(Attempt::succeeded);
        assertThat(conversionService.completeVerified(callback)).isFalse();
        assertThat(count("waiting_conversion_compensations")).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM waiting_conversion_compensations
                 WHERE waiting_team_id = ? AND payment_id = ?
                   AND reason_code = 'WAITING_CANCELLED'
                """, Long.class, paid.teamId(), paid.preparation().paymentId())).isOne();
    }

    @Test
    void paidConversionAndClaimedClosureRaceCommitExactlyOneTerminalTransition() throws Exception {
        Fixture fixture = fixture(1);
        PaidConversion paid = paidConversion(fixture, 480);
        setWaitingSettingDisabledVersion(fixture.storeId(), 7L);
        new TransactionTemplate(transactionManager).execute(status ->
                closureService.startClosure(
                        fixture.operatorId(), fixture.storeId(), key(483), 7L));
        WaitingClosureClaim closureClaim = closureService.claimPendingItems(
                "paid-conversion-closure-race", 1, Duration.ofSeconds(30), 0L).getFirst();

        List<Attempt<Boolean>> attempts = runTogether(2, index -> index == 0
                ? conversionService.completeVerified(new CompletionCommand(
                        paid.teamId(), paid.preparation().paymentId(), 9_003L))
                : closureService.processClaimedItem(closureClaim));

        assertThat(attempts).allMatch(Attempt::succeeded);
        WaitingTeam stored = teams.findById(paid.teamId()).orElseThrow();
        assertThat(stored.getStatus()).isIn(
                WaitingTeamStatus.RESERVATION_CONVERTED, WaitingTeamStatus.CLOSED_BY_STORE);
        assertThat(stored.getVersion()).isEqualTo(2L);
        assertThat(count("waiting_active_memberships")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM waiting_transition_audits
                 WHERE waiting_team_id = ?
                   AND after_status IN ('RESERVATION_CONVERTED', 'CLOSED_BY_STORE')
                """, Long.class, paid.teamId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM waiting_status_events
                 WHERE waiting_team_id = ?
                   AND public_status IN ('RESERVATION_CONVERTED', 'CLOSED_BY_STORE')
                """, Long.class, paid.teamId())).isEqualTo(1L);
        if (stored.getStatus() == WaitingTeamStatus.RESERVATION_CONVERTED) {
            assertThat(stored.getReservationReferenceId()).isEqualTo(9_003L);
            assertThat(count("waiting_conversion_compensations")).isZero();
        } else {
            assertThat(stored.getReservationReferenceId()).isNull();
            assertThat(count("waiting_conversion_compensations")).isOne();
        }
    }

    @Test
    void closedPaidConversionCallbackReplayPreservesTerminalAndOneCompensation() {
        Fixture fixture = fixture(1);
        PaidConversion paid = paidConversion(fixture, 490);
        setWaitingSettingDisabledVersion(fixture.storeId(), 7L);
        new TransactionTemplate(transactionManager).execute(status ->
                closureService.startClosure(
                        fixture.operatorId(), fixture.storeId(), key(493), 7L));
        WaitingClosureClaim closureClaim = closureService.claimPendingItems(
                "paid-conversion-closed-first", 1, Duration.ofSeconds(30), 0L).getFirst();
        assertThat(closureService.processClaimedItem(closureClaim)).isTrue();

        CompletionCommand callback = new CompletionCommand(
                paid.teamId(), paid.preparation().paymentId(), 9_004L);
        assertThat(conversionService.completeVerified(callback)).isFalse();
        assertThat(conversionService.completeVerified(callback)).isFalse();

        WaitingTeam closed = teams.findById(paid.teamId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(WaitingTeamStatus.CLOSED_BY_STORE);
        assertThat(closed.getReservationReferenceId()).isNull();
        assertThat(closed.getVersion()).isEqualTo(2L);
        assertThat(count("waiting_active_memberships")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM waiting_transition_audits
                 WHERE waiting_team_id = ? AND after_status = 'CLOSED_BY_STORE'
                """, Long.class, paid.teamId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM waiting_status_events
                 WHERE waiting_team_id = ? AND public_status = 'CLOSED_BY_STORE'
                """, Long.class, paid.teamId())).isEqualTo(1L);
        assertThat(count("waiting_conversion_compensations")).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT source_event_id, idempotency_key, reason_code, status
                  FROM waiting_conversion_compensations
                 WHERE waiting_team_id = ?
                """, paid.teamId()))
                .containsEntry("source_event_id",
                        "waiting-conversion-terminal:" + paid.teamId()
                                + ":CLOSED_BY_STORE:" + paid.preparation().paymentId())
                .containsEntry("reason_code", "WAITING_CLOSED_BY_STORE")
                .containsEntry("status", "PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT idempotency_key FROM waiting_conversion_compensations WHERE waiting_team_id=?",
                String.class, paid.teamId()))
                .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    void ambientRollbackDoesNotOrphanCommittedWaitingConversionPayment() {
        Fixture fixture = fixture(1);
        WaitingCommandResult created = creationService.create(
                fixture.storeId(), fixture.consumerIds().getFirst(), BUSINESS_DATE, 2,
                WaitingSource.REMOTE, key(520));
        long teamId = Long.parseLong(created.data().waitingTeamId());
        AtomicReference<PaymentPreparation> captured = new AtomicReference<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            captured.set(conversionService.begin(new BeginCommand(
                    teamId, 0L, 12_000L, "KRW", Instant.now().plusSeconds(3_600), 3L,
                    key(521).value())));
            status.setRollbackOnly();
        });

        PaymentPreparation preparation = captured.get();
        WaitingTeam converting = teams.findById(teamId).orElseThrow();
        assertThat(converting.getStatus()).isEqualTo(WaitingTeamStatus.RESERVATION_CONVERTING);
        assertThat(converting.getWaitingPaymentId()).isEqualTo(preparation.paymentId());
        assertThat(jdbc.queryForMap("""
                SELECT payment_id, consumer_account_id, source_type, source_reference_id
                  FROM payments
                 WHERE payment_id = ?
                """, preparation.paymentId()))
                .containsEntry("payment_id", preparation.paymentId())
                .containsEntry("consumer_account_id", fixture.consumerIds().getFirst())
                .containsEntry("source_type", "WAITING_RESERVATION_DEPOSIT")
                .containsEntry("source_reference_id", Long.toString(teamId));
    }

    @Test
    void processingRefundBlocksStalePaidSnapshotFromCompletingConversion() throws Exception {
        Fixture fixture = fixture(1);
        PaidConversion paid = paidConversion(fixture, 530);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(providerClient.cancelPayment(
                eq(paid.preparation().portOnePaymentId()), anyString(), eq(12_000L),
                eq("KRW"), eq("WAITING_REVIEW_REFUND")))
                .thenAnswer(invocation -> {
                    providerEntered.countDown();
                    if (!releaseProvider.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("provider release timed out");
                    }
                    return new ProviderCancellation(
                            "waiting-review-cancellation-" + paid.teamId(),
                            ProviderStatus.CANCELLED, 12_000L, "KRW");
                });
        RequestRefundCommand refundCommand = new RequestRefundCommand(
                paid.preparation().paymentId(),
                "waiting-review-refund:" + paid.teamId(),
                12_000L,
                "WAITING_REVIEW_REFUND",
                3L,
                key(533).value());

        Throwable completionFailure = null;
        RefundResult refundResult;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<RefundResult> refund = executor.submit(
                    () -> paymentService.requestRefund(refundCommand));
            assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM payment_refunds WHERE status='PROCESSING'",
                    Long.class)).isEqualTo(1L);
            try {
                conversionService.completeVerified(new CompletionCommand(
                        paid.teamId(), paid.preparation().paymentId(), 9_005L));
            } catch (Throwable failure) {
                completionFailure = failure;
            } finally {
                releaseProvider.countDown();
            }
            refundResult = refund.get(10, TimeUnit.SECONDS);
        }

        assertThat(completionFailure).isInstanceOf(ServiceException.class);
        WaitingTeam converting = teams.findById(paid.teamId()).orElseThrow();
        assertThat(converting.getStatus()).isEqualTo(WaitingTeamStatus.RESERVATION_CONVERTING);
        assertThat(converting.getReservationReferenceId()).isNull();
        assertThat(count("waiting_active_memberships")).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM waiting_transition_audits
                 WHERE waiting_team_id = ? AND after_status = 'RESERVATION_CONVERTED'
                """, Long.class, paid.teamId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM waiting_status_events
                 WHERE waiting_team_id = ? AND public_status = 'RESERVATION_CONVERTED'
                """, Long.class, paid.teamId())).isZero();
        assertThat(count("waiting_conversion_compensations")).isZero();
        assertThat(refundResult.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM payments WHERE payment_id=?",
                String.class, paid.preparation().paymentId())).isEqualTo("REFUNDED");
    }

    @Test
    void committedPartialBatchRetryResumesWithoutDuplicateEffects() {
        Fixture fixture = fixture(2);
        createTeams(fixture, 500);
        setWaitingSettingDisabledVersion(fixture.storeId(), 8L);
        new TransactionTemplate(transactionManager).execute(status ->
                closureService.startClosure(fixture.operatorId(), fixture.storeId(), key(510), 8L));
        List<WaitingClosureClaim> claimed = closureService.claimPendingItems(
                "concurrency", 100, Duration.ofSeconds(30), 0L);
        assertThat(claimed).hasSize(2);

        closureService.processClaimedItem(claimed.getFirst());
        long secondTeamId = jdbc.queryForObject(
                "SELECT waiting_team_id FROM waiting_closure_job_items WHERE waiting_closure_job_item_id=?",
                Long.class, claimed.get(1).itemId());
        long secondConsumerId = jdbc.queryForObject(
                "SELECT consumer_account_id FROM waiting_teams WHERE waiting_team_id=?", Long.class, secondTeamId);
        jdbc.update("DELETE FROM waiting_active_memberships WHERE waiting_team_id=?", secondTeamId);

        assertThatThrownBy(() -> closureService.processClaimedItem(claimed.get(1)))
                .isInstanceOf(ServiceException.class);
        closureService.recordFailure(claimed.get(1), true);
        memberships.saveAndFlush(WaitingActiveMembership.create(
                fixture.storeId(), secondConsumerId, secondTeamId, Instant.now()));
        WaitingClosureClaim retried = closureService.claimPendingItems(
                "concurrency", 100, Duration.ofSeconds(30), 0L).getFirst();
        closureService.processClaimedItem(retried);

        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_jobs", String.class))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT completed_team_count FROM waiting_closure_jobs", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForList("SELECT status FROM waiting_closure_job_items ORDER BY waiting_team_id",
                String.class)).containsExactly("COMPLETED", "COMPLETED");
        assertThat(count("waiting_active_memberships")).isZero();
        assertThat(count("waiting_transition_audits")).isEqualTo(4);
        assertThat(count("waiting_status_events")).isEqualTo(4);
    }

    private PaidConversion paidConversion(Fixture fixture, int keyBase) {
        WaitingCommandResult created = creationService.create(
                fixture.storeId(), fixture.consumerIds().getFirst(), BUSINESS_DATE, 2,
                WaitingSource.REMOTE, key(keyBase));
        long teamId = Long.parseLong(created.data().waitingTeamId());
        PaymentPreparation preparation = conversionService.begin(new BeginCommand(
                teamId, 0L, 12_000L, "KRW", Instant.now().plusSeconds(3_600), 3L,
                key(keyBase + 1).value()));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(), "waiting-transaction-" + teamId,
                        ProviderStatus.PAID, 12_000L, "KRW"));
        paymentService.confirmPayment(new ConfirmPaymentCommand(
                preparation.paymentId(), fixture.consumerIds().getFirst(),
                preparation.portOnePaymentId(), key(keyBase + 2).value()));
        return new PaidConversion(teamId, preparation);
    }

    private void createTeams(Fixture fixture, int keyBase) {
        for (int index = 0; index < fixture.consumerIds().size(); index++) {
            creationService.create(fixture.storeId(), fixture.consumerIds().get(index), BUSINESS_DATE,
                    2, WaitingSource.REMOTE, key(keyBase + index));
        }
    }

    private Fixture fixture(int consumerCount) {
        long operatorId = operators.saveAndFlush(
                StoreOperatorAccount.create("concurrency-" + UUID.randomUUID() + "@example.com", "hashed", "owner"))
                .getId();
        long storeId = createStore(operatorId);
        List<Long> consumerIds = new ArrayList<>();
        for (int index = 0; index < consumerCount; index++) {
            String email = "waiting-concurrency-" + UUID.randomUUID() + "@example.com";
            jdbc.update("INSERT INTO consumer_accounts (email,password_hash,name,status,created_at,updated_at) "
                    + "VALUES (?,'hashed','consumer','ACTIVE',NOW(6),NOW(6))", email);
            consumerIds.add(jdbc.queryForObject(
                    "SELECT consumer_account_id FROM consumer_accounts WHERE email=?", Long.class, email));
        }
        return new Fixture(operatorId, storeId, consumerIds);
    }

    private long createStore(long operatorId) {
        long storeId = stores.saveAndFlush(Store.create(operatorId, registrationNumber(), BusinessType.CAFE,
                "Concurrency Store", "", Region.SEOUL, "Seoul", "CAFE_BAKERY", Set.of(),
                true, true, true, "Asia/Seoul", LocalDateTime.of(2026, 8, 1, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
        insertWaitingSetting(storeId, true, "MANUAL", 1L);
        return storeId;
    }

    private WaitingOperatingInterval openInterval(long storeId, LocalDate businessDate) {
        return new WaitingOperatingInterval(
                storeId,
                "ledger-concurrency-" + storeId,
                1L,
                businessDate,
                businessDate.atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant(),
                Instant.now().plus(Duration.ofHours(1)),
                "Asia/Seoul");
    }

    private void insertWaitingSetting(long storeId, boolean enabled, String mode, long version) {
        jdbc.update("""
                INSERT INTO waiting_settings (
                    store_id, enabled, reception_mode, advance_open_minutes, version,
                    lock_version, created_at, updated_at
                ) VALUES (?, ?, ?, 60, ?, 0, NOW(6), NOW(6))
                """, storeId, enabled, mode, version);
    }

    private void setWaitingSettingDisabledVersion(long storeId, long version) {
        jdbc.update("""
                UPDATE waiting_settings
                   SET enabled=FALSE, reception_mode='PAUSED', version=?, updated_at=NOW(6)
                 WHERE store_id=?
                """, version, storeId);
    }

    private <T> List<Attempt<T>> runTogether(int participants, ConcurrentWork<T> work) throws Exception {
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(participants)) {
            List<Future<Attempt<T>>> futures = new ArrayList<>();
            for (int index = 0; index < participants; index++) {
                int taskIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrency start barrier timed out");
                    }
                    try {
                        return Attempt.success(work.run(taskIndex));
                    } catch (RuntimeException failure) {
                        return Attempt.failure(failure);
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Attempt<T>> results = new ArrayList<>();
            for (Future<Attempt<T>> future : futures) {
                try {
                    results.add(future.get(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS));
                } catch (ExecutionException failure) {
                    throw new AssertionError("concurrent participant failed outside captured boundary", failure.getCause());
                }
            }
            return results;
        }
    }

    private IdempotencyKey key(int suffix) {
        return IdempotencyKey.parse(String.format("550e8400-e29b-41d4-a716-%012d", suffix));
    }

    private String registrationNumber() {
        return Long.toString(1000000000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 8999999999L));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @FunctionalInterface
    private interface ConcurrentWork<T> {
        T run(int index);
    }

    private record Fixture(long operatorId, long storeId, List<Long> consumerIds) {}

    private record PaidConversion(long teamId, PaymentPreparation preparation) {}

    private record Attempt<T>(T result, RuntimeException failure) {
        static <T> Attempt<T> success(T result) { return new Attempt<>(result, null); }
        static <T> Attempt<T> failure(RuntimeException failure) { return new Attempt<>(null, failure); }
        boolean succeeded() { return failure == null; }
    }
}
