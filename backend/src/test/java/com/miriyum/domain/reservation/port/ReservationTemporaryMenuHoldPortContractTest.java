package com.miriyum.domain.reservation.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldCommand;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldResult;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldSelection;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReservationTemporaryMenuHoldPortContractTest {

    @Test
    void exposesOnlyTheFourApprovedScalarOperations() throws NoSuchMethodException {
        assertMethod("verifyCreationReplay", ReservationTemporaryMenuHoldCommand.Replay.class);
        assertMethod("create", ReservationTemporaryMenuHoldCommand.Create.class);
        assertMethod("lockForTransition", long.class);
        assertMethod("applyTransition", ReservationTemporaryMenuHoldCommand.ApplyTransition.class);

        assertThat(ReservationTemporaryMenuHoldPort.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactlyInAnyOrder(
                        "verifyCreationReplay", "create",
                        "lockForTransition", "applyTransition");
    }

    @Test
    void selectionRejectsNonPositiveScalarValues() {
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldSelection(0L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("menuId must be positive");
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldSelection(1L, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quantity must be positive");
    }

    @Test
    void replayCanonicalizesDuplicateSelectionsInStableMenuOrderAndCopiesInput() {
        ArrayList<ReservationTemporaryMenuHoldSelection> mutable = new ArrayList<>(List.of(
                new ReservationTemporaryMenuHoldSelection(9L, 2),
                new ReservationTemporaryMenuHoldSelection(3L, 1),
                new ReservationTemporaryMenuHoldSelection(9L, 4)));

        ReservationTemporaryMenuHoldCommand.Replay replay =
                new ReservationTemporaryMenuHoldCommand.Replay(11L, mutable);
        mutable.clear();

        assertThat(replay.selections()).containsExactly(
                new ReservationTemporaryMenuHoldSelection(3L, 1),
                new ReservationTemporaryMenuHoldSelection(9L, 6));
        assertThatThrownBy(() -> replay.selections().add(
                new ReservationTemporaryMenuHoldSelection(10L, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void replayRejectsNullSelectionsAndDuplicateQuantityOverflow() {
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldCommand.Replay(11L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("selections must not be null");
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldCommand.Replay(
                11L, java.util.Arrays.asList(
                        new ReservationTemporaryMenuHoldSelection(3L, 1), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("selections must not contain null");
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldCommand.Replay(
                11L,
                List.of(
                        new ReservationTemporaryMenuHoldSelection(3L, Integer.MAX_VALUE),
                        new ReservationTemporaryMenuHoldSelection(3L, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("selection quantity sum exceeds integer range");
    }

    @Test
    void createCarriesExactExpiryAndCanonicalSelections() {
        ReservationTemporaryMenuHoldCommand.Create command = createCommand();

        assertThat(command.reservationHoldId()).isEqualTo(11L);
        assertThat(command.storeId()).isEqualTo(12L);
        assertThat(command.consumerAccountId()).isEqualTo(13L);
        assertThat(command.expiresAt()).isEqualTo(Instant.parse("2026-08-10T03:10:00Z"));
        assertThat(command.selections()).containsExactly(
                new ReservationTemporaryMenuHoldSelection(3L, 1),
                new ReservationTemporaryMenuHoldSelection(9L, 2));
    }

    @Test
    void createRejectsInvalidIdsRangesAndMissingExpiryBeforeRuntime() {
        ReservationTemporaryMenuHoldCommand.Create valid = createCommand();

        assertThatThrownBy(() -> copyCreate(valid, 0L, valid.storeId(),
                valid.consumerAccountId(), valid.expiresAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reservationHoldId must be positive");
        assertThatThrownBy(() -> copyCreate(valid, valid.reservationHoldId(), 0L,
                valid.consumerAccountId(), valid.expiresAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("storeId must be positive");
        assertThatThrownBy(() -> copyCreate(valid, valid.reservationHoldId(), valid.storeId(),
                0L, valid.expiresAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("consumerAccountId must be positive");
        assertThatThrownBy(() -> copyCreate(valid, valid.reservationHoldId(), valid.storeId(),
                valid.consumerAccountId(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expiresAt must not be null");
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldCommand.Create(
                11L, 12L, 13L,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                LocalDate.of(2026, 8, 10), LocalTime.of(12, 0),
                Instant.parse("2026-08-10T03:00:00Z"),
                Instant.parse("2026-08-10T04:00:00Z"),
                Instant.parse("2026-08-10T03:10:00Z"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("service time range must be increasing");
    }

    @Test
    void applyTransitionRejectsUnknownTargetsOperationsAndInvalidFinalLinkage() {
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldCommand.ApplyTransition(
                11L, null, "operation", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("target must not be null");
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldCommand.ApplyTransition(
                11L, ReservationTemporaryMenuHoldCommand.Target.RELEASE, " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("operationId must not be blank");
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldCommand.ApplyTransition(
                11L, ReservationTemporaryMenuHoldCommand.Target.CONFIRM, "operation", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CONFIRM requires a positive finalReservationId");
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldCommand.ApplyTransition(
                11L, ReservationTemporaryMenuHoldCommand.Target.EXPIRE, "operation", 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("finalReservationId is allowed only for CONFIRM");
    }

    @Test
    void resultRejectsImpossiblePresenceStateAndFinalLinkageCombinations() {
        assertThat(new ReservationTemporaryMenuHoldResult(
                ReservationTemporaryMenuHoldResult.Presence.NO_HOLD, null, null))
                .isEqualTo(new ReservationTemporaryMenuHoldResult(
                        ReservationTemporaryMenuHoldResult.Presence.NO_HOLD, null, null));
        assertThat(new ReservationTemporaryMenuHoldResult(
                ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                ReservationTemporaryMenuHoldResult.State.RELEASED, 99L)
                .finalReservationId()).isEqualTo(99L);
        assertThat(new ReservationTemporaryMenuHoldResult(
                ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                ReservationTemporaryMenuHoldResult.State.FULFILLED, 99L)
                .finalReservationId()).isEqualTo(99L);
        assertThat(new ReservationTemporaryMenuHoldResult(
                ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                ReservationTemporaryMenuHoldResult.State.FORFEITED, 99L)
                .finalReservationId()).isEqualTo(99L);
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldResult(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("presence must not be null");
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldResult(
                ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("HOLD_PRESENT requires state");
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldResult(
                ReservationTemporaryMenuHoldResult.Presence.NO_HOLD,
                ReservationTemporaryMenuHoldResult.State.ACTIVE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NO_HOLD must not expose state or finalReservationId");
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldResult(
                ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                ReservationTemporaryMenuHoldResult.State.CONFIRMED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CONFIRMED requires a positive finalReservationId");
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldResult(
                ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                ReservationTemporaryMenuHoldResult.State.ACTIVE, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("finalReservationId is allowed only for final-linked terminal states");
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldResult(
                ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                ReservationTemporaryMenuHoldResult.State.FULFILLED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("FULFILLED requires a positive finalReservationId");
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldResult(
                ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                ReservationTemporaryMenuHoldResult.State.FORFEITED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("FORFEITED requires a positive finalReservationId");
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldResult(
                ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                ReservationTemporaryMenuHoldResult.State.RELEASED, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RELEASED finalReservationId must be positive when present");
    }

    private static void assertMethod(String name, Class<?> parameter)
            throws NoSuchMethodException {
        assertThat(ReservationTemporaryMenuHoldPort.class.getMethod(name, parameter)
                .getReturnType()).isEqualTo(ReservationTemporaryMenuHoldResult.class);
    }

    private static ReservationTemporaryMenuHoldCommand.Create createCommand() {
        return new ReservationTemporaryMenuHoldCommand.Create(
                11L, 12L, 13L,
                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                Instant.parse("2026-08-10T03:00:00Z"),
                Instant.parse("2026-08-10T04:00:00Z"),
                Instant.parse("2026-08-10T03:10:00Z"),
                List.of(
                        new ReservationTemporaryMenuHoldSelection(9L, 2),
                        new ReservationTemporaryMenuHoldSelection(3L, 1)));
    }

    private static ReservationTemporaryMenuHoldCommand.Create copyCreate(
            ReservationTemporaryMenuHoldCommand.Create source,
            long reservationHoldId,
            long storeId,
            long consumerAccountId,
            Instant expiresAt
    ) {
        return new ReservationTemporaryMenuHoldCommand.Create(
                reservationHoldId, storeId, consumerAccountId,
                source.serviceDate(), source.startTime(), source.endDate(), source.endTime(),
                source.startAt(), source.serviceEndAt(), expiresAt, source.selections());
    }
}
