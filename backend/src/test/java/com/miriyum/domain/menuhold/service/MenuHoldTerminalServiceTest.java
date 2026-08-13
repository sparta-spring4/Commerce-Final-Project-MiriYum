package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.miriyum.domain.menuhold.dto.MenuHoldCommandResult;
import com.miriyum.domain.menuhold.dto.MenuHoldFulfillCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldReleaseCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldTerminationPresence;
import com.miriyum.domain.menuhold.dto.TemporaryMenuHoldContracts;
import com.miriyum.domain.menuhold.entity.MenuHold;
import com.miriyum.domain.menuhold.entity.MenuHoldItemSnapshot;
import com.miriyum.domain.menuhold.entity.MenuHoldStatus;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.dto.InventoryRestoreRequest;
import com.miriyum.domain.menuhold.repository.MenuHoldRepository;
import com.miriyum.domain.menu.service.MenuTransactionFacade;
import com.miriyum.domain.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.exception.CommonErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MenuHoldTerminalServiceTest {

    @Mock MenuTransactionFacade menuTransactionFacade;
    @Mock StoreServiceIntervalValidationService intervalService;
    @Mock MenuInventoryService inventoryService;
    @Mock MenuHoldRepository holdRepository;

    @Test
    void runtimeImplementsThePublicMenuHoldService() {
        assertThat(MenuHoldService.class).isAssignableFrom(MenuHoldServiceRuntime.class);
    }

    @Test
    void prelocksPresentHoldWithoutInterpretingStateOrCallingInventory() {
        MenuHold hold = confirmedHold();
        given(holdRepository.findByReservationIdForUpdate(10L))
                .willReturn(Optional.of(hold));

        MenuHoldTerminationPresence result = service().lockForTermination(10L);

        assertThat(result).isEqualTo(MenuHoldTerminationPresence.HOLD_PRESENT);
        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.CONFIRMED);
        verify(holdRepository).findByReservationIdForUpdate(10L);
        verifyNoInteractions(menuTransactionFacade, intervalService, inventoryService);
    }

    @Test
    void reportsNoHoldWithoutCreatingOneOrCallingInventory() {
        given(holdRepository.findByReservationIdForUpdate(10L))
                .willReturn(Optional.empty());

        MenuHoldTerminationPresence result = service().lockForTermination(10L);

        assertThat(result).isEqualTo(MenuHoldTerminationPresence.NO_HOLD);
        verify(holdRepository).findByReservationIdForUpdate(10L);
        verifyNoInteractions(menuTransactionFacade, intervalService, inventoryService);
    }

    @Test
    void releasesLockedConfirmedHoldAndRestoresItsAcquireOperation() {
        MenuHold hold = confirmedHold();
        given(holdRepository.findByReservationIdForUpdate(10L))
                .willReturn(Optional.of(hold));

        MenuHoldCommandResult result = service().release(
                new MenuHoldReleaseCommand(10L, "release-operation"));

        assertThat(result).isEqualTo(MenuHoldCommandResult.released(10L));
        verify(inventoryService).restoreInventory(new InventoryRestoreRequest(
                "release-operation", "acquire-operation"));
    }

    @Test
    void repeatedReleaseReturnsSuccessWithoutRestoringAgain() {
        MenuHold hold = confirmedHold();
        hold.release();
        given(holdRepository.findByReservationIdForUpdate(10L))
                .willReturn(Optional.of(hold));

        MenuHoldCommandResult result = service().release(
                new MenuHoldReleaseCommand(10L, "repeated-release"));

        assertThat(result).isEqualTo(MenuHoldCommandResult.released(10L));
        verify(inventoryService, never()).restoreInventory(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void fulfillsLockedConfirmedHoldWithoutRestoringInventory() {
        MenuHold hold = confirmedHold();
        given(holdRepository.findByReservationIdForUpdate(10L))
                .willReturn(Optional.of(hold));

        MenuHoldCommandResult result = service().fulfill(
                new MenuHoldFulfillCommand(10L, "fulfill-operation"));

        assertThat(result).isEqualTo(MenuHoldCommandResult.fulfilled(10L));
        verify(inventoryService, never()).restoreInventory(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void repeatedFulfillReturnsSuccessWithoutRestoringInventory() {
        MenuHold hold = confirmedHold();
        hold.fulfill();
        given(holdRepository.findByReservationIdForUpdate(10L))
                .willReturn(Optional.of(hold));

        MenuHoldCommandResult result = service().fulfill(
                new MenuHoldFulfillCommand(10L, "repeated-fulfill"));

        assertThat(result).isEqualTo(MenuHoldCommandResult.fulfilled(10L));
        verify(inventoryService, never()).restoreInventory(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMissingHoldAndConflictingTerminalCommands() {
        given(holdRepository.findByReservationIdForUpdate(10L))
                .willReturn(Optional.empty());
        MenuHold fulfilled = confirmedHold();
        fulfilled.fulfill();
        given(holdRepository.findByReservationIdForUpdate(11L))
                .willReturn(Optional.of(fulfilled));

        assertStateConflict(() -> service().release(
                new MenuHoldReleaseCommand(10L, "missing-release")));
        assertStateConflict(() -> service().fulfill(
                new MenuHoldFulfillCommand(10L, "missing-fulfill")));
        assertStateConflict(() -> service().release(
                new MenuHoldReleaseCommand(11L, "conflicting-release")));

        MenuHold released = confirmedHold();
        released.release();
        given(holdRepository.findByReservationIdForUpdate(12L))
                .willReturn(Optional.of(released));
        assertStateConflict(() -> service().fulfill(
                new MenuHoldFulfillCommand(12L, "conflicting-fulfill")));
    }

    @Test
    void temporaryPolicyAppliesEveryLegalTransitionWithoutInventoryDependency() {
        MenuHold confirm = temporaryHold();
        MenuHold reconcile = temporaryHold();
        MenuHold release = temporaryHold();
        MenuHold expire = temporaryHold();
        MenuHoldTerminalService terminal = new MenuHoldTerminalService();

        assertThat(terminal.apply(
                confirm, TemporaryMenuHoldContracts.Target.CONFIRM, 91L)).isTrue();
        assertThat(terminal.apply(
                reconcile, TemporaryMenuHoldContracts.Target.REQUIRE_RECONCILIATION,
                null)).isTrue();
        assertThat(terminal.apply(
                release, TemporaryMenuHoldContracts.Target.RELEASE, null)).isTrue();
        assertThat(terminal.apply(
                expire, TemporaryMenuHoldContracts.Target.EXPIRE, null)).isTrue();

        assertThat(confirm.getStatus()).isEqualTo(MenuHoldStatus.CONFIRMED);
        assertThat(confirm.getReservationId()).isEqualTo(91L);
        assertThat(reconcile.getStatus()).isEqualTo(MenuHoldStatus.RECONCILIATION_REQUIRED);
        assertThat(release.getStatus()).isEqualTo(MenuHoldStatus.RELEASED);
        assertThat(expire.getStatus()).isEqualTo(MenuHoldStatus.EXPIRED);
    }

    @Test
    void temporaryPolicyReplaysMatchingTerminalMeaningWithoutMutation() {
        MenuHold confirmed = temporaryHold();
        confirmed.confirmTemporary(91L);
        MenuHold released = temporaryHold();
        released.releaseTemporary();
        MenuHold expired = temporaryHold();
        expired.expireTemporary();
        MenuHold reconciliation = temporaryHold();
        reconciliation.requireTemporaryReconciliation();
        MenuHoldTerminalService terminal = new MenuHoldTerminalService();

        assertThat(terminal.apply(
                confirmed, TemporaryMenuHoldContracts.Target.CONFIRM, 91L)).isFalse();
        assertThat(terminal.apply(
                released, TemporaryMenuHoldContracts.Target.RELEASE, null)).isFalse();
        assertThat(terminal.apply(
                expired, TemporaryMenuHoldContracts.Target.EXPIRE, null)).isFalse();
        assertThat(terminal.apply(
                reconciliation,
                TemporaryMenuHoldContracts.Target.REQUIRE_RECONCILIATION,
                null)).isFalse();
    }

    @Test
    void temporaryPolicyRejectsConflictingFinalLinkageWithCommon007() {
        MenuHold hold = temporaryHold();
        hold.confirmTemporary(91L);

        assertThatThrownBy(() -> new MenuHoldTerminalService().apply(
                hold, TemporaryMenuHoldContracts.Target.CONFIRM, 92L))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(hold.getReservationId()).isEqualTo(91L);
        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.CONFIRMED);
    }

    @Test
    void temporaryPolicyValidatesArgumentsBeforeMutatingTheEntity() {
        MenuHold release = temporaryHold();
        MenuHold confirm = temporaryHold();
        MenuHoldTerminalService terminal = new MenuHoldTerminalService();

        assertThatThrownBy(() -> terminal.apply(
                release, TemporaryMenuHoldContracts.Target.RELEASE, 91L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> terminal.apply(
                confirm, TemporaryMenuHoldContracts.Target.CONFIRM, 0L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(release.getStatus()).isEqualTo(MenuHoldStatus.ACTIVE);
        assertThat(confirm.getStatus()).isEqualTo(MenuHoldStatus.ACTIVE);
        assertThat(confirm.getReservationId()).isNull();
    }

    @Test
    void temporaryPolicyRejectsIllegalAndLegacyTransitionsWithoutMutation() {
        MenuHold reconciliation = temporaryHold();
        reconciliation.requireTemporaryReconciliation();
        MenuHold legacy = confirmedHold();
        MenuHoldTerminalService terminal = new MenuHoldTerminalService();

        assertTemporaryStateConflict(() -> terminal.apply(
                reconciliation, TemporaryMenuHoldContracts.Target.EXPIRE, null));
        assertTemporaryStateConflict(() -> terminal.apply(
                legacy, TemporaryMenuHoldContracts.Target.RELEASE, null));

        assertThat(reconciliation.getStatus())
                .isEqualTo(MenuHoldStatus.RECONCILIATION_REQUIRED);
        assertThat(legacy.getStatus()).isEqualTo(MenuHoldStatus.CONFIRMED);
    }

    private void assertStateConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
    }

    private void assertTemporaryStateConflict(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call
    ) {
        assertThatThrownBy(call)
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
    }

    private MenuHoldServiceRuntime service() {
        return new MenuHoldServiceRuntime(
                menuTransactionFacade, intervalService, inventoryService, holdRepository);
    }

    private static MenuHold confirmedHold() {
        return MenuHold.confirmed(
                10L, 20L, 30L, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0), "acquire-operation",
                List.of(new MenuHoldItemSnapshot(
                        40L, 50L, 2L, "아메리카노", 5_000, 3L, 1)));
    }

    private static MenuHold temporaryHold() {
        return MenuHold.temporaryActive(
                11L, 20L, 30L, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                Instant.parse("2026-08-10T03:10:00Z"),
                "reservation-temp-menu-acquire:11",
                List.of(new MenuHoldItemSnapshot(
                        40L, 50L, 2L, "아메리카노", 5_000, 3L, 1)));
    }
}
