package com.miriyum.domain.menuhold.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.clearInvocations;

import com.miriyum.domain.menuhold.dto.TemporaryMenuHoldContracts;
import com.miriyum.domain.menuhold.service.TemporaryMenuHoldService;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldCommand;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldResult;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldSelection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationTemporaryMenuHoldAdapterTest {

    @Mock TemporaryMenuHoldService service;

    @Test
    void mapsReplaySelectionsAndNoHoldResultWithoutLeakingReservationDtos() {
        given(service.verifyCreationReplay(org.mockito.ArgumentMatchers.any()))
                .willReturn(noHold());
        ReservationTemporaryMenuHoldCommand.Replay command =
                new ReservationTemporaryMenuHoldCommand.Replay(
                        11L, List.of(new ReservationTemporaryMenuHoldSelection(31L, 2)));

        ReservationTemporaryMenuHoldResult result = adapter().verifyCreationReplay(command);

        assertThat(result).isEqualTo(new ReservationTemporaryMenuHoldResult(
                ReservationTemporaryMenuHoldResult.Presence.NO_HOLD, null, null));
        ArgumentCaptor<TemporaryMenuHoldContracts.Replay> captor =
                ArgumentCaptor.forClass(TemporaryMenuHoldContracts.Replay.class);
        then(service).should().verifyCreationReplay(captor.capture());
        assertThat(captor.getValue().reservationHoldId()).isEqualTo(11L);
        assertThat(captor.getValue().selections()).containsExactly(
                new TemporaryMenuHoldContracts.Selection(31L, 2));
    }

    @Test
    void mapsEveryCreateScalarAndActiveResult() {
        given(service.create(org.mockito.ArgumentMatchers.any()))
                .willReturn(new TemporaryMenuHoldContracts.Result(
                        TemporaryMenuHoldContracts.Presence.HOLD_PRESENT,
                        TemporaryMenuHoldContracts.State.ACTIVE, null));
        ReservationTemporaryMenuHoldCommand.Create command = createCommand();

        ReservationTemporaryMenuHoldResult result = adapter().create(command);

        assertThat(result.state()).isEqualTo(ReservationTemporaryMenuHoldResult.State.ACTIVE);
        ArgumentCaptor<TemporaryMenuHoldContracts.Create> captor =
                ArgumentCaptor.forClass(TemporaryMenuHoldContracts.Create.class);
        then(service).should().create(captor.capture());
        assertThat(captor.getValue()).satisfies(mapped -> {
            assertThat(mapped.reservationHoldId()).isEqualTo(command.reservationHoldId());
            assertThat(mapped.storeId()).isEqualTo(command.storeId());
            assertThat(mapped.consumerAccountId()).isEqualTo(command.consumerAccountId());
            assertThat(mapped.serviceDate()).isEqualTo(command.serviceDate());
            assertThat(mapped.startTime()).isEqualTo(command.startTime());
            assertThat(mapped.endDate()).isEqualTo(command.endDate());
            assertThat(mapped.endTime()).isEqualTo(command.endTime());
            assertThat(mapped.startAt()).isEqualTo(command.startAt());
            assertThat(mapped.serviceEndAt()).isEqualTo(command.serviceEndAt());
            assertThat(mapped.expiresAt()).isEqualTo(command.expiresAt());
            assertThat(mapped.selections()).containsExactly(
                    new TemporaryMenuHoldContracts.Selection(31L, 2));
        });
    }

    @Test
    void mapsLockPresenceAndEveryServiceResultStateExhaustively() {
        ReservationTemporaryMenuHoldAdapter adapter = adapter();
        for (TemporaryMenuHoldContracts.State state
                : TemporaryMenuHoldContracts.State.values()) {
            Long finalReservationId = state == TemporaryMenuHoldContracts.State.CONFIRMED
                    ? 91L : null;
            given(service.lockForTransition(11L)).willReturn(
                    new TemporaryMenuHoldContracts.Result(
                            TemporaryMenuHoldContracts.Presence.HOLD_PRESENT,
                            state, finalReservationId));

            ReservationTemporaryMenuHoldResult result = adapter.lockForTransition(11L);

            assertThat(result.presence())
                    .isEqualTo(ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT);
            assertThat(result.state().name()).isEqualTo(state.name());
            assertThat(result.finalReservationId()).isEqualTo(finalReservationId);
        }
    }

    @Test
    void mapsEveryTransitionTargetAndFinalLinkageExhaustively() {
        ReservationTemporaryMenuHoldAdapter adapter = adapter();
        for (ReservationTemporaryMenuHoldCommand.Target target
                : ReservationTemporaryMenuHoldCommand.Target.values()) {
            Long finalReservationId = target == ReservationTemporaryMenuHoldCommand.Target.CONFIRM
                    ? 91L : null;
            ReservationTemporaryMenuHoldCommand.ApplyTransition command =
                    new ReservationTemporaryMenuHoldCommand.ApplyTransition(
                            11L, target, "terminal-operation-" + target, finalReservationId);
            TemporaryMenuHoldContracts.State resultingState = switch (target) {
                case CONFIRM -> TemporaryMenuHoldContracts.State.CONFIRMED;
                case RELEASE -> TemporaryMenuHoldContracts.State.RELEASED;
                case EXPIRE -> TemporaryMenuHoldContracts.State.EXPIRED;
                case REQUIRE_RECONCILIATION ->
                        TemporaryMenuHoldContracts.State.RECONCILIATION_REQUIRED;
            };
            given(service.applyTransition(org.mockito.ArgumentMatchers.any()))
                    .willReturn(new TemporaryMenuHoldContracts.Result(
                            TemporaryMenuHoldContracts.Presence.HOLD_PRESENT,
                            resultingState, finalReservationId));

            ReservationTemporaryMenuHoldResult result = adapter.applyTransition(command);

            assertThat(result.state().name()).isEqualTo(resultingState.name());
            ArgumentCaptor<TemporaryMenuHoldContracts.ApplyTransition> captor =
                    ArgumentCaptor.forClass(TemporaryMenuHoldContracts.ApplyTransition.class);
            then(service).should().applyTransition(captor.capture());
            assertThat(captor.getValue().target().name()).isEqualTo(target.name());
            assertThat(captor.getValue().operationId()).isEqualTo(command.operationId());
            assertThat(captor.getValue().finalReservationId()).isEqualTo(finalReservationId);
            clearInvocations(service);
        }
    }

    private ReservationTemporaryMenuHoldAdapter adapter() {
        return new ReservationTemporaryMenuHoldAdapter(service);
    }

    private static TemporaryMenuHoldContracts.Result noHold() {
        return new TemporaryMenuHoldContracts.Result(
                TemporaryMenuHoldContracts.Presence.NO_HOLD, null, null);
    }

    private static ReservationTemporaryMenuHoldCommand.Create createCommand() {
        return new ReservationTemporaryMenuHoldCommand.Create(
                11L, 12L, 13L,
                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                Instant.parse("2026-08-10T03:00:00Z"),
                Instant.parse("2026-08-10T04:00:00Z"),
                Instant.parse("2026-08-10T03:10:00Z"),
                List.of(new ReservationTemporaryMenuHoldSelection(31L, 2)));
    }
}
