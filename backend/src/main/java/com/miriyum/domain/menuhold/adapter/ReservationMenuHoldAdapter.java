package com.miriyum.domain.menuhold.adapter;

import com.miriyum.domain.menuhold.dto.MenuHoldCommandResult;
import com.miriyum.domain.menuhold.dto.MenuHoldCreateCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldFulfillCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldItemResult;
import com.miriyum.domain.menuhold.dto.MenuHoldReleaseCommand;
import com.miriyum.domain.menuhold.dto.MenuSelection;
import com.miriyum.domain.menuhold.service.MenuHoldService;
import com.miriyum.domain.menuhold.service.MenuHoldSnapshotQueryService;
import com.miriyum.domain.reservation.port.ReservationMenuHoldPort;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldCreateCommand;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldItemSnapshot;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldResult;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldTerminationPresence;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** MenuHold 공개 계약을 Reservation 소유 포트로 변환하는 adapter다. */
@Component
@RequiredArgsConstructor
public class ReservationMenuHoldAdapter implements ReservationMenuHoldPort {

    private final MenuHoldService menuHoldService;
    private final MenuHoldSnapshotQueryService snapshotQueryService;

    @Override
    public ReservationMenuHoldTerminationPresence lockForTermination(long reservationId) {
        return switch (menuHoldService.lockForTermination(reservationId)) {
            case NO_HOLD -> ReservationMenuHoldTerminationPresence.NO_HOLD;
            case HOLD_PRESENT -> ReservationMenuHoldTerminationPresence.HOLD_PRESENT;
        };
    }

    @Override
    public ReservationMenuHoldResult create(ReservationMenuHoldCreateCommand command) {
        MenuHoldCreateCommand menuHoldCommand = new MenuHoldCreateCommand(
                command.reservationId(),
                command.storeId(),
                command.consumerAccountId(),
                command.serviceDate(),
                command.startTime(),
                command.endDate(),
                command.endTime(),
                command.startAt(),
                command.serviceEndAt(),
                command.operationId(),
                command.menuSelections().stream()
                        .map(selection -> new MenuSelection(
                                selection.menuId(), selection.quantity()))
                        .toList());
        return toResult(menuHoldService.create(menuHoldCommand));
    }

    @Override
    public ReservationMenuHoldResult release(long reservationId, String operationId) {
        return toResult(menuHoldService.release(
                new MenuHoldReleaseCommand(reservationId, operationId)));
    }

    @Override
    public ReservationMenuHoldResult fulfill(long reservationId, String operationId) {
        return toResult(menuHoldService.fulfill(
                new MenuHoldFulfillCommand(reservationId, operationId)));
    }

    @Override
    public List<ReservationMenuHoldItemSnapshot> findSnapshots(long reservationId) {
        return snapshotQueryService.findByReservationId(reservationId).stream()
                .map(ReservationMenuHoldAdapter::toSnapshot)
                .toList();
    }

    private static ReservationMenuHoldResult toResult(MenuHoldCommandResult result) {
        return new ReservationMenuHoldResult(
                result.reservationId(),
                ReservationMenuHoldResult.Outcome.valueOf(result.outcome().name()));
    }

    private static ReservationMenuHoldItemSnapshot toSnapshot(MenuHoldItemResult item) {
        return new ReservationMenuHoldItemSnapshot(
                item.menuId(), item.menuName(), item.unitPrice(), item.quantity());
    }
}
