package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareWaitingReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.VerifiedWaitingReservationDeposit;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.entity.WaitingActorType;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.entity.WaitingTransitionAudit;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTransitionAuditRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class WaitingReservationConversionServiceTest {
    private static final long TEAM_ID = 41L;
    private static final long CONSUMER_ID = 21L;
    private static final Instant NOW = Instant.parse("2026-08-14T06:00:00Z");

    @Mock WaitingTeamRepository teams;
    @Mock WaitingActiveMembershipRepository memberships;
    @Mock WaitingTransitionAuditRepository audits;
    @Mock WaitingStatusEventAppender eventAppender;
    @Mock PaymentService payments;
    @Mock WaitingConversionCompensationService compensations;

    @Test
    void beginPreflightsOwnerCallsPaymentOutsideTransactionAndRetainsMembership() {
        WaitingTeam team = WaitingTeam.create(
                11L,
                CONSUMER_ID,
                LocalDate.of(2026, 8, 14),
                2,
                WaitingSource.REMOTE,
                1L,
                NOW.minusSeconds(60));
        ReflectionTestUtils.setField(team, "id", TEAM_ID);
        given(teams.findById(TEAM_ID)).willReturn(Optional.of(team));
        given(teams.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(team));
        PaymentPreparation preparation = new PaymentPreparation(
                "101", "portone-101", "Waiting deposit", 12_000L, "KRW",
                NOW.plusSeconds(3_600), PaymentStatus.READY);
        ArgumentCaptor<PrepareWaitingReservationDepositCommand> paymentCommand =
                ArgumentCaptor.forClass(PrepareWaitingReservationDepositCommand.class);
        given(payments.prepareWaitingReservationDeposit(paymentCommand.capture()))
                .willAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return preparation;
                });
        WaitingReservationConversionService service = new WaitingReservationConversionService(
                teams,
                memberships,
                audits,
                eventAppender,
                payments,
                compensations,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new TestTransactionManager());
        var command = new WaitingReservationConversionService.BeginCommand(
                TEAM_ID,
                0L,
                12_000L,
                "KRW",
                NOW.plusSeconds(3_600),
                3L,
                "550e8400-e29b-41d4-a716-446655440501");

        assertThat(service.begin(command)).isEqualTo(preparation);

        assertThat(paymentCommand.getValue().sourceReferenceId()).isEqualTo("41");
        assertThat(paymentCommand.getValue().consumerAccountId()).isEqualTo(CONSUMER_ID);
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.RESERVATION_CONVERTING);
        assertThat(team.getWaitingPaymentId()).isEqualTo("101");
        then(memberships).shouldHaveNoInteractions();
        ArgumentCaptor<WaitingTransitionAudit> audit =
                ArgumentCaptor.forClass(WaitingTransitionAudit.class);
        then(audits).should().save(audit.capture());
        assertThat(ReflectionTestUtils.getField(audit.getValue(), "actorType"))
                .isEqualTo(WaitingActorType.SYSTEM);
        assertThat(ReflectionTestUtils.getField(audit.getValue(), "afterStatus"))
                .isEqualTo(WaitingTeamStatus.RESERVATION_CONVERTING);
        then(eventAppender).should().append(team, NOW);
    }

    @Test
    void beginAcceptsMySqlMicrosecondPrecisionPreparationForNanosecondCommand() {
        Instant commandExpiry = Instant.parse("2026-08-14T07:00:00.123456789Z");
        WaitingTeam team = WaitingTeam.create(
                11L, CONSUMER_ID, LocalDate.of(2026, 8, 14), 2,
                WaitingSource.REMOTE, 1L, NOW.minusSeconds(60));
        ReflectionTestUtils.setField(team, "id", TEAM_ID);
        given(teams.findById(TEAM_ID)).willReturn(Optional.of(team));
        given(teams.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(team));
        PaymentPreparation storedPreparation = new PaymentPreparation(
                "101", "portone-101", "Waiting deposit", 12_000L, "KRW",
                commandExpiry.truncatedTo(ChronoUnit.MICROS), PaymentStatus.READY);
        given(payments.prepareWaitingReservationDeposit(
                org.mockito.ArgumentMatchers.any(PrepareWaitingReservationDepositCommand.class)))
                .willReturn(storedPreparation);
        WaitingReservationConversionService service = newService();

        assertThat(service.begin(new WaitingReservationConversionService.BeginCommand(
                TEAM_ID, 0L, 12_000L, "KRW", commandExpiry, 3L,
                "550e8400-e29b-41d4-a716-446655440502")))
                .isEqualTo(storedPreparation);
        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.RESERVATION_CONVERTING);
    }

    @Test
    void failLocksMatchingConversionReturnsToWaitingAndRetainsMembership() {
        WaitingTeam team = WaitingTeam.create(
                11L,
                CONSUMER_ID,
                LocalDate.of(2026, 8, 14),
                2,
                WaitingSource.REMOTE,
                1L,
                NOW.minusSeconds(60));
        ReflectionTestUtils.setField(team, "id", TEAM_ID);
        team.beginReservationConversion(0L, "101", NOW.minusSeconds(10));
        given(teams.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(team));
        WaitingReservationConversionService service = new WaitingReservationConversionService(
                teams,
                memberships,
                audits,
                eventAppender,
                payments,
                compensations,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new TestTransactionManager());

        assertThat(service.fail(TEAM_ID, 1L, "101")).isTrue();

        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.WAITING);
        assertThat(team.getVersion()).isEqualTo(2L);
        assertThat(team.getWaitingPaymentId()).isNull();
        assertThat(team.getReservationConvertingAt()).isNull();
        then(memberships).shouldHaveNoInteractions();
        ArgumentCaptor<WaitingTransitionAudit> audit =
                ArgumentCaptor.forClass(WaitingTransitionAudit.class);
        then(audits).should().save(audit.capture());
        assertThat(ReflectionTestUtils.getField(audit.getValue(), "actorType"))
                .isEqualTo(WaitingActorType.SYSTEM);
        assertThat(ReflectionTestUtils.getField(audit.getValue(), "beforeStatus"))
                .isEqualTo(WaitingTeamStatus.RESERVATION_CONVERTING);
        assertThat(ReflectionTestUtils.getField(audit.getValue(), "afterStatus"))
                .isEqualTo(WaitingTeamStatus.WAITING);
        then(eventAppender).should().append(team, NOW);
    }

    @Test
    void paidMatchingConversionCompletesAndRemovesExactlyOneMembership() {
        WaitingTeam team = convertingTeam();
        given(teams.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(team));
        given(payments.getCompletableWaitingReservationDeposit("101", TEAM_ID, CONSUMER_ID))
                .willAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    return verified(PaymentStatus.PAID);
                });
        given(memberships.deleteByWaitingTeamId(TEAM_ID)).willReturn(2L);

        assertThat(newService().completeVerified(
                new WaitingReservationConversionService.CompletionCommand(
                        TEAM_ID, "101", 501L))).isTrue();

        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.RESERVATION_CONVERTED);
        assertThat(team.getReservationReferenceId()).isEqualTo(501L);
        then(memberships).should().deleteByWaitingTeamId(TEAM_ID);
        ArgumentCaptor<WaitingTransitionAudit> audit =
                ArgumentCaptor.forClass(WaitingTransitionAudit.class);
        then(audits).should().save(audit.capture());
        assertThat(ReflectionTestUtils.getField(audit.getValue(), "actorType"))
                .isEqualTo(WaitingActorType.SYSTEM);
        assertThat(ReflectionTestUtils.getField(audit.getValue(), "afterStatus"))
                .isEqualTo(WaitingTeamStatus.RESERVATION_CONVERTED);
        then(eventAppender).should().append(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        then(compensations).shouldHaveNoInteractions();
    }

    @Test
    void exactConvertedReplaySkipsPaymentAndAllDuplicateEffects() {
        WaitingTeam team = convertingTeam();
        team.completeReservationConversion(1L, "101", 501L, NOW);
        given(teams.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(team));

        assertThat(newService().completeVerified(
                new WaitingReservationConversionService.CompletionCommand(
                        TEAM_ID, "101", 501L))).isTrue();

        assertThat(team.getVersion()).isEqualTo(2L);
        then(payments).shouldHaveNoInteractions();
        then(memberships).shouldHaveNoInteractions();
        then(audits).shouldHaveNoInteractions();
        then(eventAppender).shouldHaveNoInteractions();
        then(compensations).shouldHaveNoInteractions();
    }

    @Test
    void cancelledWinnerReplaysOneDeterministicCompensationIdentity() {
        WaitingTeam team = convertingTeam();
        team.cancel(1L, NOW);
        long terminalVersion = team.getVersion();
        given(teams.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(team));
        given(payments.getVerifiedWaitingReservationDeposit("101", TEAM_ID, CONSUMER_ID))
                .willAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    return verified(PaymentStatus.REFUNDED);
                });
        given(compensations.recordRequired(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).willReturn(71L);
        var command = new WaitingReservationConversionService.CompletionCommand(
                TEAM_ID, "101", 501L);

        assertThat(newService().completeVerified(command)).isFalse();
        assertThat(newService().completeVerified(command)).isFalse();

        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.CANCELLED);
        assertThat(team.getVersion()).isEqualTo(terminalVersion);
        assertThat(team.getReservationReferenceId()).isNull();
        ArgumentCaptor<String> sources = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        then(compensations).should(org.mockito.Mockito.times(2)).recordRequired(
                org.mockito.ArgumentMatchers.eq(TEAM_ID),
                org.mockito.ArgumentMatchers.eq("101"),
                org.mockito.ArgumentMatchers.eq(12_000L),
                org.mockito.ArgumentMatchers.eq("KRW"),
                org.mockito.ArgumentMatchers.eq(3L),
                sources.capture(),
                keys.capture(),
                org.mockito.ArgumentMatchers.eq("WAITING_CANCELLED"));
        assertThat(sources.getAllValues()).containsOnly(
                "waiting-conversion-terminal:41:CANCELLED:101");
        assertThat(keys.getAllValues()).hasSize(2).allMatch(keys.getAllValues().getFirst()::equals);
        assertThat(keys.getAllValues().getFirst())
                .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        then(memberships).shouldHaveNoInteractions();
        then(audits).shouldHaveNoInteractions();
        then(eventAppender).shouldHaveNoInteractions();
    }

    @Test
    void closedWinnerPreservesTerminalStateAndRecordsDeterministicCompensation() {
        WaitingTeam team = convertingTeam();
        team.closeByStore(1L, NOW);
        long terminalVersion = team.getVersion();
        given(teams.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(team));
        given(payments.getVerifiedWaitingReservationDeposit("101", TEAM_ID, CONSUMER_ID))
                .willReturn(verified(PaymentStatus.RECONCILIATION_REQUIRED));

        assertThat(newService().completeVerified(
                new WaitingReservationConversionService.CompletionCommand(
                        TEAM_ID, "101", 501L))).isFalse();

        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.CLOSED_BY_STORE);
        assertThat(team.getVersion()).isEqualTo(terminalVersion);
        then(compensations).should().recordRequired(
                org.mockito.ArgumentMatchers.eq(TEAM_ID),
                org.mockito.ArgumentMatchers.eq("101"),
                org.mockito.ArgumentMatchers.eq(12_000L),
                org.mockito.ArgumentMatchers.eq("KRW"),
                org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.eq("waiting-conversion-terminal:41:CLOSED_BY_STORE:101"),
                org.mockito.ArgumentMatchers.matches(
                        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"),
                org.mockito.ArgumentMatchers.eq("WAITING_CLOSED_BY_STORE"));
        then(memberships).shouldHaveNoInteractions();
        then(audits).shouldHaveNoInteractions();
        then(eventAppender).shouldHaveNoInteractions();
    }

    @Test
    void mismatchedPaymentAndNonPaidConversionAreRejectedWithoutMutation() {
        WaitingTeam team = convertingTeam();
        given(teams.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(team));

        assertInvalidCompletion(new WaitingReservationConversionService.CompletionCommand(
                TEAM_ID, "102", 501L));

        given(payments.getCompletableWaitingReservationDeposit("101", TEAM_ID, CONSUMER_ID))
                .willReturn(verified(PaymentStatus.PARTIALLY_REFUNDED));
        assertInvalidCompletion(new WaitingReservationConversionService.CompletionCommand(
                TEAM_ID, "101", 501L));

        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.RESERVATION_CONVERTING);
        assertThat(team.getVersion()).isEqualTo(1L);
        then(memberships).shouldHaveNoInteractions();
        then(audits).shouldHaveNoInteractions();
        then(eventAppender).shouldHaveNoInteractions();
        then(compensations).shouldHaveNoInteractions();
    }

    private void assertInvalidCompletion(
            WaitingReservationConversionService.CompletionCommand command
    ) {
        assertThatThrownBy(() -> newService().completeVerified(command))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_INVALID_TRANSITION));
    }

    private WaitingReservationConversionService newService() {
        return new WaitingReservationConversionService(
                teams,
                memberships,
                audits,
                eventAppender,
                payments,
                compensations,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new TestTransactionManager());
    }

    private static WaitingTeam convertingTeam() {
        WaitingTeam team = WaitingTeam.create(
                11L,
                CONSUMER_ID,
                LocalDate.of(2026, 8, 14),
                2,
                WaitingSource.REMOTE,
                1L,
                NOW.minusSeconds(60));
        ReflectionTestUtils.setField(team, "id", TEAM_ID);
        team.beginReservationConversion(0L, "101", NOW.minusSeconds(10));
        return team;
    }

    private static VerifiedWaitingReservationDeposit verified(PaymentStatus status) {
        return new VerifiedWaitingReservationDeposit(
                "101", 12_000L, "KRW", 3L, status, NOW.minusSeconds(30));
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
