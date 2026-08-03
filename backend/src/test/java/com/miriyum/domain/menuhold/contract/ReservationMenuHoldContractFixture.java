package com.miriyum.domain.menuhold.contract;

import com.miriyum.domain.menuhold.dto.MenuHoldCommandResult;
import com.miriyum.domain.menuhold.dto.MenuHoldCreateCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldFulfillCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldReleaseCommand;
import com.miriyum.domain.menuhold.service.MenuHoldService;
import com.miriyum.global.exception.ServiceException;
import java.util.ArrayList;
import java.util.List;

/** 예약 도메인이 메뉴홀드 런타임 없이 소비 계약을 테스트할 때 사용하는 테스트 전용 fixture다. */
public final class ReservationMenuHoldContractFixture implements MenuHoldService {

    private final ServiceException failure;
    private final List<MenuHoldCreateCommand> createCommands = new ArrayList<>();
    private final List<MenuHoldReleaseCommand> releaseCommands = new ArrayList<>();
    private final List<MenuHoldFulfillCommand> fulfillCommands = new ArrayList<>();

    private ReservationMenuHoldContractFixture(ServiceException failure) {
        this.failure = failure;
    }

    public static ReservationMenuHoldContractFixture succeeding() {
        return new ReservationMenuHoldContractFixture(null);
    }

    public static ReservationMenuHoldContractFixture failing(ServiceException failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        return new ReservationMenuHoldContractFixture(failure);
    }

    @Override
    public MenuHoldCommandResult create(MenuHoldCreateCommand command) {
        createCommands.add(command);
        throwIfConfigured();
        if (command.menuSelections().isEmpty()) {
            return MenuHoldCommandResult.noHold(command.reservationId());
        }
        return MenuHoldCommandResult.confirmed(command.reservationId());
    }

    @Override
    public MenuHoldCommandResult release(MenuHoldReleaseCommand command) {
        releaseCommands.add(command);
        throwIfConfigured();
        return MenuHoldCommandResult.released(command.reservationId());
    }

    @Override
    public MenuHoldCommandResult fulfill(MenuHoldFulfillCommand command) {
        fulfillCommands.add(command);
        throwIfConfigured();
        return MenuHoldCommandResult.fulfilled(command.reservationId());
    }

    public List<MenuHoldCreateCommand> createCommands() {
        return List.copyOf(createCommands);
    }

    public List<MenuHoldReleaseCommand> releaseCommands() {
        return List.copyOf(releaseCommands);
    }

    public List<MenuHoldFulfillCommand> fulfillCommands() {
        return List.copyOf(fulfillCommands);
    }

    private void throwIfConfigured() {
        if (failure != null) {
            throw failure;
        }
    }
}
