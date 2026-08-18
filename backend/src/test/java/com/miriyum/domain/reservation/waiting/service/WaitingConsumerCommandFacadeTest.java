package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamTransitionRequest;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class WaitingConsumerCommandFacadeTest {

    @Test
    void createRevalidatesTheConsumerAndFixesThePublicSourceToRemote() {
        ConsumerAccountService accounts = mock(ConsumerAccountService.class);
        WaitingCreationService creation = mock(WaitingCreationService.class);
        WaitingLedgerService ledger = mock(WaitingLedgerService.class);
        IdempotencyKey key = IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440200");
        LocalDate businessDate = LocalDate.of(2026, 8, 17);
        WaitingConsumerCommandResult expected = new WaitingConsumerCommandResult(
                200, mock(WaitingConsumerSnapshot.class));
        when(creation.createForConsumer(
                100L, 200L, businessDate, 2, WaitingSource.REMOTE, key, true))
                .thenReturn(expected);
        WaitingConsumerCommandFacade facade = new WaitingConsumerCommandFacade(
                accounts,
                creation,
                ledger,
                Clock.fixed(Instant.parse("2026-08-17T03:00:00Z"), ZoneOffset.UTC),
                true,
                attempt -> 0L,
                millis -> { });

        WaitingConsumerCommandResult result = facade.create(100L, 200L, businessDate, 2, key);

        assertThat(result).isSameAs(expected);
        InOrder order = inOrder(accounts, creation);
        order.verify(accounts).requireActiveAccount(200L);
        order.verify(creation).createForConsumer(
                100L, 200L, businessDate, 2, WaitingSource.REMOTE, key, true);
    }

    @Test
    void cancelRevalidatesTheConsumerAndBuildsAConsumerScopedCanonicalCommand() {
        ConsumerAccountService accounts = mock(ConsumerAccountService.class);
        WaitingCreationService creation = mock(WaitingCreationService.class);
        WaitingLedgerService ledger = mock(WaitingLedgerService.class);
        Instant now = Instant.parse("2026-08-17T03:00:00Z");
        WaitingConsumerCommandResult expected = new WaitingConsumerCommandResult(
                200, mock(WaitingConsumerSnapshot.class));
        when(ledger.cancelByConsumer(
                eq(200L), eq(300L), eq(4L), any(IdempotencyCommand.class), eq(now)))
                .thenReturn(expected);
        WaitingConsumerCommandFacade facade = new WaitingConsumerCommandFacade(
                accounts,
                creation,
                ledger,
                Clock.fixed(now, ZoneOffset.UTC),
                false,
                attempt -> 0L,
                millis -> { });

        WaitingConsumerCommandResult result = facade.cancel(
                200L,
                300L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440201"),
                new WaitingTeamTransitionRequest(4L));

        assertThat(result).isSameAs(expected);
        ArgumentCaptor<IdempotencyCommand> command =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        verify(ledger).cancelByConsumer(eq(200L), eq(300L), eq(4L), command.capture(), eq(now));
        assertThat(command.getValue().principalNamespace()).isEqualTo("consumer");
        assertThat(command.getValue().principalId()).isEqualTo(200L);
        assertThat(command.getValue().commandType()).isEqualTo("WAITING_TEAM_CANCEL");
        assertThat(command.getValue().requestFingerprint()).isEqualTo(
                com.miriyum.global.idempotency.RequestFingerprint.of(
                        "method=4:POST|"
                                + "route=64:/api/v1/consumers/me/waiting-teams/{waitingTeamId}/cancellations|"
                                + "waitingTeamId=3:300|expectedVersion=1:4|"));
        InOrder order = inOrder(accounts, ledger);
        order.verify(accounts).requireActiveAccount(200L);
        order.verify(ledger).cancelByConsumer(
                eq(200L), eq(300L), eq(4L), any(IdempotencyCommand.class), eq(now));
    }
}
