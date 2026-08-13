package com.miriyum.domain.menuhold.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationTemporaryMenuHoldAdapterTest {

    @Mock TemporaryMenuHoldService service;

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMenuHoldOwnedContracts")
    void menuHoldOwnedContractsRejectMalformedScalarMeaning(
            String regression,
            ThrowingCallable construction,
            String expectedMessage
    ) {
        assertThatThrownBy(construction)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expectedMessage);
    }

    @Test
    void menuHoldOwnedCommandsCanonicallyCopyAndFreezeSelectionInputs() {
        ArrayList<TemporaryMenuHoldContracts.Selection> mutable = new ArrayList<>(List.of(
                new TemporaryMenuHoldContracts.Selection(9L, 2),
                new TemporaryMenuHoldContracts.Selection(3L, 1),
                new TemporaryMenuHoldContracts.Selection(9L, 4)));

        TemporaryMenuHoldContracts.Replay replay =
                new TemporaryMenuHoldContracts.Replay(11L, mutable);
        TemporaryMenuHoldContracts.Create create = menuHoldCreate(mutable);
        mutable.clear();

        List<TemporaryMenuHoldContracts.Selection> expected = List.of(
                new TemporaryMenuHoldContracts.Selection(3L, 1),
                new TemporaryMenuHoldContracts.Selection(9L, 6));
        assertThat(replay.selections()).containsExactlyElementsOf(expected);
        assertThat(create.selections()).containsExactlyElementsOf(expected);
        assertThatThrownBy(() -> replay.selections().add(
                new TemporaryMenuHoldContracts.Selection(10L, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> create.selections().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overflowingMenuHoldOwnedCommands")
    void menuHoldOwnedCanonicalizationRejectsSummedQuantityOverflow(
            String regression,
            ThrowingCallable construction
    ) {
        assertThatThrownBy(construction)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("selection quantity sum exceeds integer range");
    }

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
                    || state == TemporaryMenuHoldContracts.State.FULFILLED
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

    private static Stream<Arguments> invalidMenuHoldOwnedContracts() {
        return Stream.of(
                invalid("selection menu ID is not positive",
                        () -> new TemporaryMenuHoldContracts.Selection(0L, 1),
                        "menuId must be positive"),
                invalid("selection quantity is not positive",
                        () -> new TemporaryMenuHoldContracts.Selection(1L, 0),
                        "quantity must be positive"),
                invalid("replay hold ID is not positive",
                        () -> new TemporaryMenuHoldContracts.Replay(0L, List.of()),
                        "reservationHoldId must be positive"),
                invalid("replay selections are null",
                        () -> new TemporaryMenuHoldContracts.Replay(11L, null),
                        "selections must not be null"),
                invalid("replay contains a null selection",
                        () -> new TemporaryMenuHoldContracts.Replay(
                                11L, Arrays.asList(
                                        new TemporaryMenuHoldContracts.Selection(1L, 1), null)),
                        "selections must not contain null"),
                invalid("create reservation hold ID is not positive",
                        () -> new TemporaryMenuHoldContracts.Create(
                                0L, 12L, 13L,
                                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                                Instant.parse("2026-08-10T03:00:00Z"),
                                Instant.parse("2026-08-10T04:00:00Z"),
                                Instant.parse("2026-08-10T03:10:00Z"), List.of()),
                        "reservationHoldId must be positive"),
                invalid("create store ID is not positive",
                        () -> new TemporaryMenuHoldContracts.Create(
                                11L, 0L, 13L,
                                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                                Instant.parse("2026-08-10T03:00:00Z"),
                                Instant.parse("2026-08-10T04:00:00Z"),
                                Instant.parse("2026-08-10T03:10:00Z"), List.of()),
                        "storeId must be positive"),
                invalid("create consumer account ID is not positive",
                        () -> new TemporaryMenuHoldContracts.Create(
                                11L, 12L, 0L,
                                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                                Instant.parse("2026-08-10T03:00:00Z"),
                                Instant.parse("2026-08-10T04:00:00Z"),
                                Instant.parse("2026-08-10T03:10:00Z"), List.of()),
                        "consumerAccountId must be positive"),
                invalid("create service date is absent",
                        () -> new TemporaryMenuHoldContracts.Create(
                                11L, 12L, 13L, null, LocalTime.NOON,
                                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                                Instant.parse("2026-08-10T03:00:00Z"),
                                Instant.parse("2026-08-10T04:00:00Z"),
                                Instant.parse("2026-08-10T03:10:00Z"), List.of()),
                        "serviceDate must not be null"),
                invalid("create start time is absent",
                        () -> new TemporaryMenuHoldContracts.Create(
                                11L, 12L, 13L,
                                LocalDate.of(2026, 8, 10), null,
                                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                                Instant.parse("2026-08-10T03:00:00Z"),
                                Instant.parse("2026-08-10T04:00:00Z"),
                                Instant.parse("2026-08-10T03:10:00Z"), List.of()),
                        "service time range must be increasing"),
                invalid("create end date is absent",
                        () -> new TemporaryMenuHoldContracts.Create(
                                11L, 12L, 13L,
                                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                                null, LocalTime.of(13, 0),
                                Instant.parse("2026-08-10T03:00:00Z"),
                                Instant.parse("2026-08-10T04:00:00Z"),
                                Instant.parse("2026-08-10T03:10:00Z"), List.of()),
                        "service time range must be increasing"),
                invalid("create end time is absent",
                        () -> new TemporaryMenuHoldContracts.Create(
                                11L, 12L, 13L,
                                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                                LocalDate.of(2026, 8, 10), null,
                                Instant.parse("2026-08-10T03:00:00Z"),
                                Instant.parse("2026-08-10T04:00:00Z"),
                                Instant.parse("2026-08-10T03:10:00Z"), List.of()),
                        "service time range must be increasing"),
                invalid("create local interval does not increase",
                        () -> new TemporaryMenuHoldContracts.Create(
                                11L, 12L, 13L,
                                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                                Instant.parse("2026-08-10T03:00:00Z"),
                                Instant.parse("2026-08-10T04:00:00Z"),
                                Instant.parse("2026-08-10T03:10:00Z"), List.of()),
                        "service time range must be increasing"),
                invalid("create resolved start is absent",
                        () -> new TemporaryMenuHoldContracts.Create(
                                11L, 12L, 13L,
                                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                                null, Instant.parse("2026-08-10T04:00:00Z"),
                                Instant.parse("2026-08-10T03:10:00Z"), List.of()),
                        "resolved service time range must be increasing"),
                invalid("create resolved end is absent",
                        () -> new TemporaryMenuHoldContracts.Create(
                                11L, 12L, 13L,
                                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                                Instant.parse("2026-08-10T03:00:00Z"), null,
                                Instant.parse("2026-08-10T03:10:00Z"), List.of()),
                        "resolved service time range must be increasing"),
                invalid("create resolved interval does not increase",
                        () -> new TemporaryMenuHoldContracts.Create(
                                11L, 12L, 13L,
                                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                                Instant.parse("2026-08-10T04:00:00Z"),
                                Instant.parse("2026-08-10T03:00:00Z"),
                                Instant.parse("2026-08-10T03:10:00Z"), List.of()),
                        "resolved service time range must be increasing"),
                invalid("create expiry is absent",
                        () -> new TemporaryMenuHoldContracts.Create(
                                11L, 12L, 13L,
                                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                                Instant.parse("2026-08-10T03:00:00Z"),
                                Instant.parse("2026-08-10T04:00:00Z"), null, List.of()),
                        "expiresAt must not be null"),
                invalid("transition reservation hold ID is not positive",
                        () -> new TemporaryMenuHoldContracts.ApplyTransition(
                                0L, TemporaryMenuHoldContracts.Target.RELEASE,
                                "operation", null),
                        "reservationHoldId must be positive"),
                invalid("transition target is absent",
                        () -> new TemporaryMenuHoldContracts.ApplyTransition(
                                11L, null, "operation", null),
                        "target must not be null"),
                invalid("transition operation is absent",
                        () -> new TemporaryMenuHoldContracts.ApplyTransition(
                                11L, TemporaryMenuHoldContracts.Target.RELEASE,
                                null, null),
                        "operationId must not be blank"),
                invalid("transition operation is blank",
                        () -> new TemporaryMenuHoldContracts.ApplyTransition(
                                11L, TemporaryMenuHoldContracts.Target.RELEASE,
                                "   ", null),
                        "operationId must not be blank"),
                invalid("transition operation is over 100 characters",
                        () -> new TemporaryMenuHoldContracts.ApplyTransition(
                                11L, TemporaryMenuHoldContracts.Target.RELEASE,
                                "x".repeat(101), null),
                        "operationId must not exceed 100 characters"),
                invalid("confirm final linkage is absent",
                        () -> new TemporaryMenuHoldContracts.ApplyTransition(
                                11L, TemporaryMenuHoldContracts.Target.CONFIRM,
                                "operation", null),
                        "CONFIRM requires a positive finalReservationId"),
                invalid("confirm final linkage is not positive",
                        () -> new TemporaryMenuHoldContracts.ApplyTransition(
                                11L, TemporaryMenuHoldContracts.Target.CONFIRM,
                                "operation", 0L),
                        "CONFIRM requires a positive finalReservationId"),
                invalid("non-confirm transition has final linkage",
                        () -> new TemporaryMenuHoldContracts.ApplyTransition(
                                11L, TemporaryMenuHoldContracts.Target.EXPIRE,
                                "operation", 91L),
                        "finalReservationId is allowed only for CONFIRM"),
                invalid("result presence is absent",
                        () -> new TemporaryMenuHoldContracts.Result(null, null, null),
                        "presence must not be null"),
                invalid("no-hold result exposes state",
                        () -> new TemporaryMenuHoldContracts.Result(
                                TemporaryMenuHoldContracts.Presence.NO_HOLD,
                                TemporaryMenuHoldContracts.State.ACTIVE, null),
                        "NO_HOLD must not expose state or finalReservationId"),
                invalid("present result omits state",
                        () -> new TemporaryMenuHoldContracts.Result(
                                TemporaryMenuHoldContracts.Presence.HOLD_PRESENT, null, null),
                        "HOLD_PRESENT requires state"),
                invalid("confirmed result omits final linkage",
                        () -> new TemporaryMenuHoldContracts.Result(
                                TemporaryMenuHoldContracts.Presence.HOLD_PRESENT,
                                TemporaryMenuHoldContracts.State.CONFIRMED, null),
                        "CONFIRMED requires a positive finalReservationId"),
                invalid("confirmed result has non-positive final linkage",
                        () -> new TemporaryMenuHoldContracts.Result(
                                TemporaryMenuHoldContracts.Presence.HOLD_PRESENT,
                                TemporaryMenuHoldContracts.State.CONFIRMED, 0L),
                        "CONFIRMED requires a positive finalReservationId"),
                invalid("active result exposes final linkage",
                        () -> new TemporaryMenuHoldContracts.Result(
                                TemporaryMenuHoldContracts.Presence.HOLD_PRESENT,
                                TemporaryMenuHoldContracts.State.ACTIVE, 91L),
                        "finalReservationId is allowed only for final-linked terminal states"),
                invalid("fulfilled result omits final linkage",
                        () -> new TemporaryMenuHoldContracts.Result(
                                TemporaryMenuHoldContracts.Presence.HOLD_PRESENT,
                                TemporaryMenuHoldContracts.State.FULFILLED, null),
                        "FULFILLED requires a positive finalReservationId"),
                invalid("released result has non-positive final linkage",
                        () -> new TemporaryMenuHoldContracts.Result(
                                TemporaryMenuHoldContracts.Presence.HOLD_PRESENT,
                                TemporaryMenuHoldContracts.State.RELEASED, 0L),
                        "RELEASED finalReservationId must be positive when present")
        );
    }

    private static Stream<Arguments> overflowingMenuHoldOwnedCommands() {
        List<TemporaryMenuHoldContracts.Selection> overflowing = List.of(
                new TemporaryMenuHoldContracts.Selection(3L, Integer.MAX_VALUE),
                new TemporaryMenuHoldContracts.Selection(3L, 1));
        return Stream.of(
                Arguments.of("replay summed quantity overflows",
                        (ThrowingCallable) () ->
                                new TemporaryMenuHoldContracts.Replay(11L, overflowing)),
                Arguments.of("create summed quantity overflows",
                        (ThrowingCallable) () -> menuHoldCreate(overflowing))
        );
    }

    private static Arguments invalid(
            String name,
            ThrowingCallable construction,
            String expectedMessage
    ) {
        return Arguments.of(name, construction, expectedMessage);
    }

    private static TemporaryMenuHoldContracts.Create menuHoldCreate(
            List<TemporaryMenuHoldContracts.Selection> selections
    ) {
        return new TemporaryMenuHoldContracts.Create(
                11L, 12L, 13L,
                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                Instant.parse("2026-08-10T03:00:00Z"),
                Instant.parse("2026-08-10T04:00:00Z"),
                Instant.parse("2026-08-10T03:10:00Z"), selections);
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
