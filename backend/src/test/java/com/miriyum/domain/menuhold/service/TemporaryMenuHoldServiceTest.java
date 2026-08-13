package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

import com.miriyum.domain.menu.dto.contract.MenuTransactionEligibility;
import com.miriyum.domain.menu.service.MenuTransactionFacade;
import com.miriyum.domain.menuhold.dto.MenuSelection;
import com.miriyum.domain.menuhold.dto.TemporaryMenuHoldContracts;
import com.miriyum.domain.menuhold.entity.MenuHold;
import com.miriyum.domain.menuhold.entity.MenuHoldItem;
import com.miriyum.domain.menuhold.entity.MenuHoldItemSnapshot;
import com.miriyum.domain.menuhold.entity.MenuHoldStatus;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.dto.CurrentInventorySelection;
import com.miriyum.domain.menuhold.inventory.dto.InventoryRestoreRequest;
import com.miriyum.domain.menuhold.repository.MenuHoldRepository;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalRequest;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalResult;
import com.miriyum.domain.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TemporaryMenuHoldServiceTest {

    @Mock MenuTransactionFacade menuTransactionFacade;
    @Mock StoreServiceIntervalValidationService intervalService;
    @Mock MenuInventoryService inventoryService;
    @Mock MenuHoldRepository holdRepository;
    @Mock MenuHoldTerminalService terminalService;

    @Test
    void publicOperationsUseMandatoryPropagationAndRejectCallsOutsideATransaction()
            throws Exception {
        assertMandatory("verifyCreationReplay", TemporaryMenuHoldContracts.Replay.class);
        assertMandatory("create", TemporaryMenuHoldContracts.Create.class);
        assertMandatory("lockForTransition", long.class);
        assertMandatory("applyTransition", TemporaryMenuHoldContracts.ApplyTransition.class);

        TemporaryMenuHoldService target = org.mockito.Mockito.mock(
                TemporaryMenuHoldService.class);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new NeverExistingTransactionManager());
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(interceptor);
        TemporaryMenuHoldService proxy = (TemporaryMenuHoldService) factory.getProxy();

        assertThatThrownBy(() -> proxy.create(command(List.of())))
                .isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(target);
    }

    @Test
    void replayTreatsOnlyAbsentAndEmptyAsEqualWithoutMutatingDependencies() {
        given(holdRepository.findByReservationHoldId(11L)).willReturn(Optional.empty());

        TemporaryMenuHoldContracts.Result result = service().verifyCreationReplay(
                new TemporaryMenuHoldContracts.Replay(11L, List.of()));

        assertThat(result).isEqualTo(noHold());
        verifyNoInteractions(menuTransactionFacade, intervalService, inventoryService,
                terminalService);
    }

    @Test
    void replayComparesCanonicalPersistedMenuMeaningWithoutMutation() {
        MenuHold hold = temporaryHold(List.of(
                snapshot(9L, 90L, 4),
                snapshot(3L, 30L, 1)));
        given(holdRepository.findByReservationHoldId(11L)).willReturn(Optional.of(hold));

        TemporaryMenuHoldContracts.Result result = service().verifyCreationReplay(
                new TemporaryMenuHoldContracts.Replay(11L, List.of(
                        new TemporaryMenuHoldContracts.Selection(3L, 1),
                        new TemporaryMenuHoldContracts.Selection(9L, 4))));

        assertThat(result.state()).isEqualTo(TemporaryMenuHoldContracts.State.ACTIVE);
        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.ACTIVE);
        verifyNoInteractions(menuTransactionFacade, intervalService, inventoryService,
                terminalService);
    }

    @Test
    void replayReturnsFinalLinkageAfterConfirmedTemporaryHoldIsReleased() {
        MenuHold hold = temporaryHold(List.of(snapshot(9L, 90L, 4)));
        hold.confirmTemporary(91L);
        hold.release();
        given(holdRepository.findByReservationHoldId(11L)).willReturn(Optional.of(hold));

        TemporaryMenuHoldContracts.Result result = service().verifyCreationReplay(
                new TemporaryMenuHoldContracts.Replay(11L, List.of(
                        new TemporaryMenuHoldContracts.Selection(9L, 4))));

        assertThat(result.state()).isEqualTo(TemporaryMenuHoldContracts.State.RELEASED);
        assertThat(result.finalReservationId()).isEqualTo(91L);
    }

    @Test
    void replayReturnsFinalLinkageAfterConfirmedTemporaryHoldIsFulfilled() {
        MenuHold hold = temporaryHold(List.of(snapshot(9L, 90L, 4)));
        hold.confirmTemporary(91L);
        hold.fulfill();
        given(holdRepository.findByReservationHoldId(11L)).willReturn(Optional.of(hold));

        TemporaryMenuHoldContracts.Result result = service().verifyCreationReplay(
                new TemporaryMenuHoldContracts.Replay(11L, List.of(
                        new TemporaryMenuHoldContracts.Selection(9L, 4))));

        assertThat(result.state()).isEqualTo(TemporaryMenuHoldContracts.State.FULFILLED);
        assertThat(result.finalReservationId()).isEqualTo(91L);
    }

    @Test
    void replayMismatchFailsWithCommon007BeforeAnyMutation() {
        MenuHold hold = temporaryHold(List.of(snapshot(9L, 90L, 4)));
        given(holdRepository.findByReservationHoldId(11L)).willReturn(Optional.of(hold));

        assertThatThrownBy(() -> service().verifyCreationReplay(
                new TemporaryMenuHoldContracts.Replay(11L, List.of(
                        new TemporaryMenuHoldContracts.Selection(9L, 5)))))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.ACTIVE);
        verifyNoInteractions(menuTransactionFacade, intervalService, inventoryService,
                terminalService);
    }

    @Test
    void replayNeverTreatsAPersistedRootAsTheSameMeaningAsNoHold() {
        MenuHold corruptEmptyRoot = temporaryHold(List.of(snapshot(9L, 90L, 1)));
        ReflectionTestUtils.setField(corruptEmptyRoot, "items", List.of());
        given(holdRepository.findByReservationHoldId(11L))
                .willReturn(Optional.of(corruptEmptyRoot));

        assertThatThrownBy(() -> service().verifyCreationReplay(
                new TemporaryMenuHoldContracts.Replay(11L, List.of())))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void emptyCreateReturnsNoHoldWithoutPersistenceOrInventory() {
        TemporaryMenuHoldContracts.Result result = service().create(command(List.of()));

        assertThat(result).isEqualTo(noHold());
        verifyNoInteractions(menuTransactionFacade, intervalService, inventoryService,
                holdRepository, terminalService);
    }

    @Test
    void nonEmptyCreateValidatesMenusResolvesAndAcquiresBeforePersistingExactExpiry() {
        TemporaryMenuHoldContracts.Create command = command(List.of(
                new TemporaryMenuHoldContracts.Selection(9L, 2),
                new TemporaryMenuHoldContracts.Selection(3L, 1)));
        given(menuTransactionFacade.requireTransactionEligibility(12L, 3L))
                .willReturn(eligibility(3L, "샐러드"));
        given(menuTransactionFacade.requireTransactionEligibility(12L, 9L))
                .willReturn(eligibility(9L, "파스타"));
        List<MenuSelection> sorted = List.of(new MenuSelection(3L, 1), new MenuSelection(9L, 2));
        List<CurrentInventorySelection> current = List.of(
                current(3L, 30L, 1), current(9L, 90L, 2));
        given(inventoryService.loadCurrentSelections(
                sorted, command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime())).willReturn(current);
        StoreServiceIntervalRequest intervalRequest = new StoreServiceIntervalRequest(
                12L, command.startAt(), command.serviceEndAt());
        given(intervalService.validateServiceIntervals(
                List.of(intervalRequest, intervalRequest))).willReturn(List.of(
                        StoreServiceIntervalResult.of(intervalRequest, true),
                        StoreServiceIntervalResult.of(intervalRequest, true)));
        given(inventoryService.acquireCurrentInventory(
                "reservation-temp-menu-acquire:11", current)).willReturn(current);

        TemporaryMenuHoldContracts.Result result = service().create(command);

        assertThat(result.state()).isEqualTo(TemporaryMenuHoldContracts.State.ACTIVE);
        InOrder order = org.mockito.Mockito.inOrder(
                menuTransactionFacade, inventoryService, intervalService, holdRepository);
        order.verify(menuTransactionFacade).requireTransactionEligibility(12L, 3L);
        order.verify(menuTransactionFacade).requireTransactionEligibility(12L, 9L);
        order.verify(inventoryService).loadCurrentSelections(
                sorted, command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime());
        order.verify(intervalService).validateServiceIntervals(
                List.of(intervalRequest, intervalRequest));
        order.verify(inventoryService).acquireCurrentInventory(
                "reservation-temp-menu-acquire:11", current);
        ArgumentCaptor<MenuHold> holdCaptor = ArgumentCaptor.forClass(MenuHold.class);
        order.verify(holdRepository).saveAndFlush(holdCaptor.capture());
        assertThat(holdCaptor.getValue().getExpiresAt()).isEqualTo(command.expiresAt());
        assertThat(holdCaptor.getValue().getAcquireOperationId())
                .isEqualTo("reservation-temp-menu-acquire:11");
        assertThat(holdCaptor.getValue().getItems())
                .extracting(MenuHoldItem::getMenuId, MenuHoldItem::getQuantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(3L, 1),
                        org.assertj.core.groups.Tuple.tuple(9L, 2));
    }

    @Test
    void createPropagatesInventoryBusinessFailureWithoutPersistingOrCompensating() {
        TemporaryMenuHoldContracts.Create command = command(List.of(
                new TemporaryMenuHoldContracts.Selection(3L, 1)));
        given(menuTransactionFacade.requireTransactionEligibility(12L, 3L))
                .willReturn(eligibility(3L, "샐러드"));
        List<MenuSelection> selections = List.of(new MenuSelection(3L, 1));
        List<CurrentInventorySelection> current = List.of(current(3L, 30L, 1));
        given(inventoryService.loadCurrentSelections(
                selections, command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime())).willReturn(current);
        StoreServiceIntervalRequest intervalRequest = new StoreServiceIntervalRequest(
                12L, command.startAt(), command.serviceEndAt());
        given(intervalService.validateServiceIntervals(List.of(intervalRequest)))
                .willReturn(List.of(StoreServiceIntervalResult.of(intervalRequest, true)));
        ServiceException failure = new ServiceException(MenuHoldErrorCode.INSUFFICIENT_QUANTITY);
        given(inventoryService.acquireCurrentInventory(
                "reservation-temp-menu-acquire:11", current)).willThrow(failure);

        assertThatThrownBy(() -> service().create(command)).isSameAs(failure);
        then(holdRepository).should(never()).saveAndFlush(
                org.mockito.ArgumentMatchers.any(MenuHold.class));
        then(inventoryService).should(never()).restoreInventory(
                org.mockito.ArgumentMatchers.any(InventoryRestoreRequest.class));
    }

    @Test
    void transitionLockUsesRootOnlyPessimisticQueryWithoutMutation() throws Exception {
        MenuHold hold = temporaryHold(List.of(snapshot(3L, 30L, 1)));
        given(holdRepository.findByReservationHoldIdForUpdate(11L))
                .willReturn(Optional.of(hold));

        TemporaryMenuHoldContracts.Result result = service().lockForTransition(11L);

        assertThat(result.state()).isEqualTo(TemporaryMenuHoldContracts.State.ACTIVE);
        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.ACTIVE);
        verifyNoInteractions(menuTransactionFacade, intervalService, inventoryService,
                terminalService);

        Method queryMethod = MenuHoldRepository.class.getMethod(
                "findByReservationHoldIdForUpdate", Long.class);
        assertThat(queryMethod.getAnnotation(Lock.class).value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(queryMethod.getAnnotation(Query.class).value().toLowerCase())
                .doesNotContain(" join ", "fetch", "item", "bucket");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repeatedRestoringTransitions")
    void repeatedRealTerminalTransitionRestoresOriginalAcquireExactlyOnce(
            String regression,
            TemporaryMenuHoldContracts.Target target,
            TemporaryMenuHoldContracts.State expectedState,
            String operationId,
            String expectedRestoreOperationId
    ) {
        MenuHold hold = temporaryHold(List.of(snapshot(3L, 30L, 1)));
        given(holdRepository.findByReservationHoldId(11L)).willReturn(Optional.of(hold));
        List<InventoryRestoreRequest> restores = new ArrayList<>();
        doAnswer(invocation -> {
            restores.add(invocation.getArgument(0, InventoryRestoreRequest.class));
            return null;
        }).when(inventoryService).restoreInventory(
                org.mockito.ArgumentMatchers.any(InventoryRestoreRequest.class));
        TemporaryMenuHoldServiceRuntime runtime = new TemporaryMenuHoldServiceRuntime(
                menuTransactionFacade, intervalService, inventoryService,
                holdRepository, new MenuHoldTerminalService());
        TemporaryMenuHoldContracts.ApplyTransition command =
                new TemporaryMenuHoldContracts.ApplyTransition(
                        11L, target, operationId, null);

        TemporaryMenuHoldContracts.Result first = runtime.applyTransition(command);
        TemporaryMenuHoldContracts.Result replay = runtime.applyTransition(command);

        assertThat(first.state()).isEqualTo(expectedState);
        assertThat(replay.state()).isEqualTo(expectedState);
        assertThat(restores).singleElement().satisfies(restore -> {
            assertThat(restore.sourceAcquireOperationId())
                .isEqualTo("reservation-temp-menu-acquire:11");
            assertThat(restore.operationId())
                .isEqualTo(expectedRestoreOperationId)
                .hasSizeLessThanOrEqualTo(100);
        });
    }

    @Test
    void transitionedConfirmationAndReconciliationRetainInventory() {
        MenuHold confirmed = temporaryHold(List.of(snapshot(3L, 30L, 1)));
        MenuHold reconciliation = temporaryHold(List.of(snapshot(3L, 30L, 1)));
        given(holdRepository.findByReservationHoldId(11L))
                .willReturn(Optional.of(confirmed))
                .willReturn(Optional.of(reconciliation));
        given(terminalService.apply(
                confirmed, TemporaryMenuHoldContracts.Target.CONFIRM, 91L))
                .willAnswer(invocation -> {
                    confirmed.confirmTemporary(91L);
                    return true;
                });
        given(terminalService.apply(
                reconciliation,
                TemporaryMenuHoldContracts.Target.REQUIRE_RECONCILIATION,
                null)).willAnswer(invocation -> {
                    reconciliation.requireTemporaryReconciliation();
                    return true;
                });

        assertThat(service().applyTransition(
                new TemporaryMenuHoldContracts.ApplyTransition(
                        11L, TemporaryMenuHoldContracts.Target.CONFIRM,
                        "confirm-operation", 91L)).state())
                .isEqualTo(TemporaryMenuHoldContracts.State.CONFIRMED);
        assertThat(service().applyTransition(
                new TemporaryMenuHoldContracts.ApplyTransition(
                        11L, TemporaryMenuHoldContracts.Target.REQUIRE_RECONCILIATION,
                        "reconcile-operation", null)).state())
                .isEqualTo(TemporaryMenuHoldContracts.State.RECONCILIATION_REQUIRED);

        then(inventoryService).should(never()).restoreInventory(
                org.mockito.ArgumentMatchers.any(InventoryRestoreRequest.class));
    }

    @Test
    void confirmationAndReconciliationNeverRestoreInventoryAndReplayDoesNotRestoreAgain() {
        for (TemporaryMenuHoldContracts.Target target : List.of(
                TemporaryMenuHoldContracts.Target.CONFIRM,
                TemporaryMenuHoldContracts.Target.REQUIRE_RECONCILIATION,
                TemporaryMenuHoldContracts.Target.RELEASE,
                TemporaryMenuHoldContracts.Target.EXPIRE)) {
            MenuHold hold = temporaryHold(List.of(snapshot(3L, 30L, 1)));
            Long finalReservationId = target == TemporaryMenuHoldContracts.Target.CONFIRM
                    ? 91L : null;
            given(holdRepository.findByReservationHoldId(11L)).willReturn(Optional.of(hold));
            given(terminalService.apply(hold, target, finalReservationId)).willReturn(false);

            service().applyTransition(new TemporaryMenuHoldContracts.ApplyTransition(
                    11L, target, "replayed-" + target, finalReservationId));
        }

        then(inventoryService).should(never()).restoreInventory(
                org.mockito.ArgumentMatchers.any(InventoryRestoreRequest.class));
    }

    private TemporaryMenuHoldServiceRuntime service() {
        return new TemporaryMenuHoldServiceRuntime(
                menuTransactionFacade, intervalService, inventoryService,
                holdRepository, terminalService);
    }

    private static Stream<Arguments> repeatedRestoringTransitions() {
        return Stream.of(
                Arguments.of(
                        "repeated release",
                        TemporaryMenuHoldContracts.Target.RELEASE,
                        TemporaryMenuHoldContracts.State.RELEASED,
                        "repeat-release-operation",
                        "reservation-temp-menu-restore:"
                                + "8e42e257290a2356bd67f7accafa838582047baae4a6fb59d1413c3561e9f46e"),
                Arguments.of(
                        "repeated expiry",
                        TemporaryMenuHoldContracts.Target.EXPIRE,
                        TemporaryMenuHoldContracts.State.EXPIRED,
                        "repeat-expire-operation",
                        "reservation-temp-menu-restore:"
                                + "bea7f917e5d9f64e7fda5f4d7d5f5168217e9de6fb814bc8626e890d92ee884b")
        );
    }

    private static void assertMandatory(String methodName, Class<?> parameterType)
            throws NoSuchMethodException {
        Transactional transactional = TemporaryMenuHoldService.class
                .getMethod(methodName, parameterType)
                .getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private static TemporaryMenuHoldContracts.Create command(
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

    private static CurrentInventorySelection current(long menuId, long bucketId, int quantity) {
        return new CurrentInventorySelection(
                menuId, bucketId, 7L, "Asia/Seoul",
                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0), quantity);
    }

    private static MenuTransactionEligibility eligibility(long menuId, String name) {
        return new MenuTransactionEligibility(
                12L, menuId, 5, name, 10_000, true, false);
    }

    private static MenuHold temporaryHold(List<MenuHoldItemSnapshot> snapshots) {
        return MenuHold.temporaryActive(
                11L, 12L, 13L,
                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                Instant.parse("2026-08-10T03:10:00Z"),
                "reservation-temp-menu-acquire:11", snapshots);
    }

    private static MenuHoldItemSnapshot snapshot(long menuId, long bucketId, int quantity) {
        return new MenuHoldItemSnapshot(
                menuId, bucketId, 5L, "menu-" + menuId, 10_000, 7L, quantity);
    }

    private static TemporaryMenuHoldContracts.Result noHold() {
        return new TemporaryMenuHoldContracts.Result(
                TemporaryMenuHoldContracts.Presence.NO_HOLD, null, null);
    }

    private static final class NeverExistingTransactionManager
            extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            throw new AssertionError("MANDATORY must not begin a transaction");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            throw new AssertionError("no transaction may commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            throw new AssertionError("no transaction may roll back");
        }
    }
}
