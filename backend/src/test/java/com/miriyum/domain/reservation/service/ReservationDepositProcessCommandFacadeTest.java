package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;

class ReservationDepositProcessCommandFacadeTest {

    private static final long CONSUMER_ID = 11L;
    private static final long PROCESS_ID = 99L;
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "123E4567-E89B-12D3-A456-426614174000");

    @Test
    void finalizationAndAbandonmentUseDifferentScopedFingerprints() {
        ReservationDepositProcessService processService =
                mock(ReservationDepositProcessService.class);
        ReservationDepositCommandResult result =
                mock(ReservationDepositCommandResult.class);
        given(processService.finalizeOwnedIdempotent(
                org.mockito.ArgumentMatchers.eq(PROCESS_ID),
                org.mockito.ArgumentMatchers.eq(CONSUMER_ID),
                any())).willReturn(result);
        given(processService.abandonOwnedIdempotent(
                org.mockito.ArgumentMatchers.eq(PROCESS_ID),
                org.mockito.ArgumentMatchers.eq(CONSUMER_ID),
                any())).willReturn(result);
        ReservationDepositProcessCommandFacade facade =
                new ReservationDepositProcessCommandFacade(processService);

        assertThat(facade.finalizeRequest(CONSUMER_ID, PROCESS_ID, KEY)).isSameAs(result);
        assertThat(facade.abandonRequest(CONSUMER_ID, PROCESS_ID, KEY)).isSameAs(result);

        ArgumentCaptor<IdempotencyCommand> finalizeCommand =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        ArgumentCaptor<IdempotencyCommand> abandonCommand =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        then(processService).should().finalizeOwnedIdempotent(
                org.mockito.ArgumentMatchers.eq(PROCESS_ID),
                org.mockito.ArgumentMatchers.eq(CONSUMER_ID),
                finalizeCommand.capture());
        then(processService).should().abandonOwnedIdempotent(
                org.mockito.ArgumentMatchers.eq(PROCESS_ID),
                org.mockito.ArgumentMatchers.eq(CONSUMER_ID),
                abandonCommand.capture());
        assertThat(finalizeCommand.getValue()).isEqualTo(new IdempotencyCommand(
                "consumer",
                CONSUMER_ID,
                "RESERVATION_DEPOSIT_FINALIZE",
                KEY.value(),
                RequestFingerprint.of(
                        "method=4:POST|"
                                + "route=78:/api/v1/consumers/me/reservation-requests/"
                                + "{reservationRequestId}/finalizations|"
                                + "reservationRequestId=2:99|")));
        assertThat(abandonCommand.getValue()).isEqualTo(new IdempotencyCommand(
                "consumer",
                CONSUMER_ID,
                "RESERVATION_DEPOSIT_ABANDON",
                KEY.value(),
                RequestFingerprint.of(
                        "method=4:POST|"
                                + "route=77:/api/v1/consumers/me/reservation-requests/"
                                + "{reservationRequestId}/abandonments|"
                                + "reservationRequestId=2:99|")));
        assertThat(finalizeCommand.getValue().requestFingerprint())
                .isNotEqualTo(abandonCommand.getValue().requestFingerprint());
    }

    @Test
    void retriesMysqlDeadlockWithinThreeAttemptBudget() {
        ReservationDepositProcessService processService =
                mock(ReservationDepositProcessService.class);
        ReservationDepositCommandResult result =
                mock(ReservationDepositCommandResult.class);
        given(processService.finalizeOwnedIdempotent(
                org.mockito.ArgumentMatchers.eq(PROCESS_ID),
                org.mockito.ArgumentMatchers.eq(CONSUMER_ID),
                any()))
                .willThrow(deadlock())
                .willThrow(deadlock())
                .willReturn(result);
        List<Long> delays = new ArrayList<>();
        ReservationDepositProcessCommandFacade facade =
                new ReservationDepositProcessCommandFacade(
                        processService,
                        attempt -> (long) attempt * 10,
                        delays::add);

        assertThat(facade.finalizeRequest(CONSUMER_ID, PROCESS_ID, KEY)).isSameAs(result);

        then(processService).should(times(3)).finalizeOwnedIdempotent(
                org.mockito.ArgumentMatchers.eq(PROCESS_ID),
                org.mockito.ArgumentMatchers.eq(CONSUMER_ID),
                any());
        assertThat(delays).containsExactly(10L, 20L);
    }

    @Test
    void claimedWorkerReconciliationUsesTheSameMysqlLockRetryBoundary() {
        ReservationDepositProcessService processService =
                mock(ReservationDepositProcessService.class);
        ReservationDepositProcessService.Claim claim =
                new ReservationDepositProcessService.Claim(
                        PROCESS_ID,
                        "worker-a",
                        1L);
        given(processService.reconcileClaimed(claim))
                .willThrow(deadlock())
                .willReturn(true);
        List<Long> delays = new ArrayList<>();
        ReservationDepositProcessCommandFacade facade =
                new ReservationDepositProcessCommandFacade(
                        processService,
                        attempt -> (long) attempt * 10,
                        delays::add);

        assertThat(facade.reconcileClaimed(claim)).isTrue();

        then(processService).should(times(2)).reconcileClaimed(claim);
        assertThat(delays).containsExactly(10L);
    }

    private static CannotAcquireLockException deadlock() {
        return new CannotAcquireLockException(
                "deadlock",
                new SQLException("deadlock", "40001", 1213));
    }
}
