package com.miriyum.domain.menuhold.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.menuhold.dto.MenuHoldCommandResult;
import com.miriyum.domain.menuhold.dto.MenuHoldCreateCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldForfeitCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldItemResult;
import com.miriyum.domain.menuhold.service.MenuHoldService;
import com.miriyum.domain.menuhold.service.MenuHoldSnapshotQueryService;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldCreateCommand;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldResult;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldSelection;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldTerminationPresence;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
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
class ReservationMenuHoldAdapterTest {

    @Mock
    private MenuHoldService menuHoldService;

    @Mock
    private MenuHoldSnapshotQueryService snapshotQueryService;

    @Test
    void convertsReservationCreateCommandAndConfirmedResult() {
        ReservationMenuHoldAdapter adapter = adapter();
        given(menuHoldService.create(org.mockito.ArgumentMatchers.any()))
                .willReturn(MenuHoldCommandResult.confirmed(7L));
        ReservationMenuHoldCreateCommand command = new ReservationMenuHoldCreateCommand(
                7L, 11L, 13L,
                LocalDate.of(2026, 8, 20), LocalTime.of(18, 0),
                LocalDate.of(2026, 8, 20), LocalTime.of(19, 0),
                Instant.parse("2026-08-20T09:00:00Z"),
                Instant.parse("2026-08-20T10:00:00Z"),
                "reservation-create-7",
                List.of(
                        new ReservationMenuHoldSelection(31L, 1),
                        new ReservationMenuHoldSelection(31L, 2)));

        ReservationMenuHoldResult result = adapter.create(command);

        assertThat(result).isEqualTo(new ReservationMenuHoldResult(
                7L, ReservationMenuHoldResult.Outcome.CONFIRMED));
        ArgumentCaptor<MenuHoldCreateCommand> captor =
                ArgumentCaptor.forClass(MenuHoldCreateCommand.class);
        then(menuHoldService).should().create(captor.capture());
        assertThat(captor.getValue().reservationId()).isEqualTo(7L);
        assertThat(captor.getValue().operationId()).isEqualTo("reservation-create-7");
        assertThat(captor.getValue().menuSelections()).singleElement().satisfies(selection -> {
            assertThat(selection.menuId()).isEqualTo(31L);
            assertThat(selection.quantity()).isEqualTo(3);
        });
    }

    @Test
    void convertsTerminationCommandsPresenceAndSnapshots() {
        ReservationMenuHoldAdapter adapter = adapter();
        given(menuHoldService.lockForTermination(7L))
                .willReturn(com.miriyum.domain.menuhold.dto.MenuHoldTerminationPresence.HOLD_PRESENT);
        given(menuHoldService.release(org.mockito.ArgumentMatchers.any()))
                .willReturn(MenuHoldCommandResult.released(7L));
        given(menuHoldService.fulfill(org.mockito.ArgumentMatchers.any()))
                .willReturn(MenuHoldCommandResult.fulfilled(7L));
        given(menuHoldService.forfeit(org.mockito.ArgumentMatchers.any()))
                .willReturn(MenuHoldCommandResult.forfeited(7L));
        given(snapshotQueryService.findByReservationId(7L)).willReturn(List.of(
                new MenuHoldItemResult(31L, "Pasta", 12_000L, 2),
                new MenuHoldItemResult(32L, "Salad", 8_000L, 1)));

        assertThat(adapter.lockForTermination(7L))
                .isEqualTo(ReservationMenuHoldTerminationPresence.HOLD_PRESENT);
        assertThat(adapter.release(7L, "reservation-release-7").outcome())
                .isEqualTo(ReservationMenuHoldResult.Outcome.RELEASED);
        assertThat(adapter.fulfill(7L, "reservation-fulfill-7").outcome())
                .isEqualTo(ReservationMenuHoldResult.Outcome.FULFILLED);
        assertThat(adapter.forfeit(7L, "reservation-forfeit-7").outcome())
                .isEqualTo(ReservationMenuHoldResult.Outcome.FORFEITED);
        ArgumentCaptor<MenuHoldForfeitCommand> forfeitCaptor =
                ArgumentCaptor.forClass(MenuHoldForfeitCommand.class);
        then(menuHoldService).should().forfeit(forfeitCaptor.capture());
        assertThat(forfeitCaptor.getValue()).isEqualTo(
                new MenuHoldForfeitCommand(7L, "reservation-forfeit-7"));
        assertThat(adapter.findSnapshots(7L))
                .extracting("menuId", "menuName", "unitPrice", "quantity")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(31L, "Pasta", 12_000L, 2),
                        org.assertj.core.groups.Tuple.tuple(32L, "Salad", 8_000L, 1));
    }

    @Test
    void propagatesMenuHoldServiceExceptionUnchanged() {
        ReservationMenuHoldAdapter adapter = adapter();
        ServiceException expected = new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        given(menuHoldService.lockForTermination(7L)).willThrow(expected);

        Throwable actual = catchThrowable(() -> adapter.lockForTermination(7L));

        assertThat(actual).isSameAs(expected);
    }

    private ReservationMenuHoldAdapter adapter() {
        return new ReservationMenuHoldAdapter(menuHoldService, snapshotQueryService);
    }
}
