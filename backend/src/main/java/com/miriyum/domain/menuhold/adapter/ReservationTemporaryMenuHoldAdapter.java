package com.miriyum.domain.menuhold.adapter;

import com.miriyum.domain.menuhold.dto.TemporaryMenuHoldContracts;
import com.miriyum.domain.menuhold.service.TemporaryMenuHoldService;
import com.miriyum.domain.reservation.port.ReservationTemporaryMenuHoldPort;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldCommand;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Reservation 소유 scalar 포트를 MenuHold 소유 임시 선점 계약으로 변환한다. */
@Component
@RequiredArgsConstructor
public class ReservationTemporaryMenuHoldAdapter implements ReservationTemporaryMenuHoldPort {

    private final TemporaryMenuHoldService service;

    @Override
    public ReservationTemporaryMenuHoldResult verifyCreationReplay(
            ReservationTemporaryMenuHoldCommand.Replay command
    ) {
        return toReservationResult(service.verifyCreationReplay(
                new TemporaryMenuHoldContracts.Replay(
                        command.reservationHoldId(),
                        command.selections().stream()
                                .map(selection -> new TemporaryMenuHoldContracts.Selection(
                                        selection.menuId(), selection.quantity()))
                                .toList())));
    }

    @Override
    public ReservationTemporaryMenuHoldResult create(
            ReservationTemporaryMenuHoldCommand.Create command
    ) {
        return toReservationResult(service.create(new TemporaryMenuHoldContracts.Create(
                command.reservationHoldId(), command.storeId(), command.consumerAccountId(),
                command.serviceDate(), command.startTime(), command.endDate(), command.endTime(),
                command.startAt(), command.serviceEndAt(), command.expiresAt(),
                command.selections().stream()
                        .map(selection -> new TemporaryMenuHoldContracts.Selection(
                                selection.menuId(), selection.quantity()))
                        .toList())));
    }

    @Override
    public ReservationTemporaryMenuHoldResult lockForTransition(long reservationHoldId) {
        return toReservationResult(service.lockForTransition(reservationHoldId));
    }

    @Override
    public ReservationTemporaryMenuHoldResult applyTransition(
            ReservationTemporaryMenuHoldCommand.ApplyTransition command
    ) {
        return toReservationResult(service.applyTransition(
                new TemporaryMenuHoldContracts.ApplyTransition(
                        command.reservationHoldId(), toMenuHoldTarget(command.target()),
                        command.operationId(), command.finalReservationId())));
    }

    private static TemporaryMenuHoldContracts.Target toMenuHoldTarget(
            ReservationTemporaryMenuHoldCommand.Target target
    ) {
        return switch (target) {
            case CONFIRM -> TemporaryMenuHoldContracts.Target.CONFIRM;
            case RELEASE -> TemporaryMenuHoldContracts.Target.RELEASE;
            case EXPIRE -> TemporaryMenuHoldContracts.Target.EXPIRE;
            case REQUIRE_RECONCILIATION ->
                    TemporaryMenuHoldContracts.Target.REQUIRE_RECONCILIATION;
        };
    }

    private static ReservationTemporaryMenuHoldResult toReservationResult(
            TemporaryMenuHoldContracts.Result result
    ) {
        ReservationTemporaryMenuHoldResult.Presence presence = switch (result.presence()) {
            case NO_HOLD -> ReservationTemporaryMenuHoldResult.Presence.NO_HOLD;
            case HOLD_PRESENT -> ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT;
        };
        ReservationTemporaryMenuHoldResult.State state = result.state() == null
                ? null : switch (result.state()) {
                    case ACTIVE -> ReservationTemporaryMenuHoldResult.State.ACTIVE;
                    case RECONCILIATION_REQUIRED ->
                            ReservationTemporaryMenuHoldResult.State.RECONCILIATION_REQUIRED;
                    case CONFIRMED -> ReservationTemporaryMenuHoldResult.State.CONFIRMED;
                    case RELEASED -> ReservationTemporaryMenuHoldResult.State.RELEASED;
                    case EXPIRED -> ReservationTemporaryMenuHoldResult.State.EXPIRED;
                    case FULFILLED -> ReservationTemporaryMenuHoldResult.State.FULFILLED;
                };
        return new ReservationTemporaryMenuHoldResult(
                presence, state, result.finalReservationId());
    }
}
