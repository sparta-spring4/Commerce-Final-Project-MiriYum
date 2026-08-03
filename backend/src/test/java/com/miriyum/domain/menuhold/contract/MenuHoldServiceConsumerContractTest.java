package com.miriyum.domain.menuhold.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.menuhold.dto.MenuHoldCommandResult;
import com.miriyum.domain.menuhold.dto.MenuHoldCreateCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldFulfillCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldReleaseCommand;
import com.miriyum.domain.menuhold.dto.MenuSelection;
import com.miriyum.domain.menuhold.service.MenuHoldService;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class MenuHoldServiceConsumerContractTest {

    @Test
    void exposesReservationCommandsThatMustJoinTheCallerTransaction() throws Exception {
        assertMandatory("create", MenuHoldCreateCommand.class);
        assertMandatory("release", MenuHoldReleaseCommand.class);
        assertMandatory("fulfill", MenuHoldFulfillCommand.class);
    }

    @Test
    void carriesTheReservationIdentifierAndMenuSelectionWithoutReservationTypes() {
        MenuHoldCreateCommand command = new MenuHoldCreateCommand(
                "reservation-01",
                "01K1CREATE0000000000000001",
                List.of(new MenuSelection("menu-01", 2)));

        assertThat(command.reservationId()).isEqualTo("reservation-01");
        assertThat(command.operationId()).isEqualTo("01K1CREATE0000000000000001");
        assertThat(command.menuSelections())
                .containsExactly(new MenuSelection("menu-01", 2));
    }

    @Test
    void protectsCommandValuesFromInvalidOrMutableConsumerInput() {
        List<MenuSelection> selections = new java.util.ArrayList<>();
        selections.add(new MenuSelection("menu-01", 1));
        MenuHoldCreateCommand command = new MenuHoldCreateCommand(
                "reservation-01", "01K1CREATE0000000000000001", selections);

        selections.clear();

        assertThat(command.menuSelections()).containsExactly(new MenuSelection("menu-01", 1));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new MenuSelection("menu-01", 0));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new MenuHoldReleaseCommand(
                        "reservation-01",
                        "same-operation",
                        "same-operation"));
    }

    @Test
    void acceptsEmptySelectionsAndRepresentsTheNoHoldResultExplicitly() {
        MenuHoldCreateCommand command = new MenuHoldCreateCommand(
                "reservation-01", "01K1CREATE0000000000000001", List.of());

        assertThat(command.menuSelections()).isEmpty();
        assertThat(MenuHoldCommandResult.noHold(command.reservationId()).outcome())
                .isEqualTo(MenuHoldCommandResult.Outcome.NO_HOLD);
    }

    @Test
    void releaseCarriesItsOwnOperationAndTheOriginalAcquireOperation() {
        MenuHoldReleaseCommand command = new MenuHoldReleaseCommand(
                "reservation-01",
                "01K1RELEASE000000000000001",
                "01K1CREATE0000000000000001");

        assertThat(command.operationId()).isNotEqualTo(command.sourceAcquireOperationId());
        assertThat(command.sourceAcquireOperationId())
                .isEqualTo("01K1CREATE0000000000000001");
    }

    @Test
    void fulfillAlsoRejectsReuseOfTheAcquireOperation() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new MenuHoldFulfillCommand(
                        "reservation-01", "same-operation", "same-operation"));
    }

    @Test
    void reservationConsumerLocksCapacityBeforeMenuHoldAndSkipsReplay() {
        RecordingMenuHoldService service = new RecordingMenuHoldService();
        ReservationConsumer consumer = new ReservationConsumer(service);

        MenuHoldCommandResult first = consumer.create("idempotency-key", "reservation-01");
        MenuHoldCommandResult replay = consumer.create("idempotency-key", "reservation-01");

        assertThat(service.events).containsExactly("capacity-locked", "menu-hold-created");
        assertThat(service.createOperations).hasSize(1);
        assertThat(replay).isSameAs(first);
    }

    @Test
    void reservationConsumerUsesDistinctOperationsAndPreservesMenuHoldError() {
        RecordingMenuHoldService service = new RecordingMenuHoldService();
        ReservationConsumer consumer = new ReservationConsumer(service);

        consumer.create("create-key", "reservation-01");
        consumer.fulfill("reservation-01", service.createOperations.getFirst());

        assertThat(service.fulfillOperations.getFirst())
                .isNotEqualTo(service.createOperations.getFirst());

        ServiceException failure = new ServiceException(MenuHoldErrorCode.INSUFFICIENT_QUANTITY);
        service.failure = failure;
        assertThatThrownBy(() -> consumer.create("other-key", "reservation-02"))
                .isSameAs(failure);
    }

    @Test
    void returnsAnExplicitOutcomeAndSourceAcquireOperation() {
        MenuHoldCommandResult result = MenuHoldCommandResult.confirmed(
                "reservation-01", "01K1CREATE0000000000000001");

        assertThat(result.reservationId()).isEqualTo("reservation-01");
        assertThat(result.outcome()).isEqualTo(MenuHoldCommandResult.Outcome.CONFIRMED);
        assertThat(result.sourceAcquireOperationId())
                .isEqualTo("01K1CREATE0000000000000001");
    }

    private static void assertMandatory(String methodName, Class<?> commandType)
            throws NoSuchMethodException {
        Method method = MenuHoldService.class.getMethod(methodName, commandType);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(method.getReturnType()).isEqualTo(MenuHoldCommandResult.class);
    }

    private static final class ReservationConsumer {
        private final RecordingMenuHoldService menuHoldService;
        private final AtomicInteger operations = new AtomicInteger();
        private final Map<String, MenuHoldCommandResult> replayResults = new HashMap<>();

        private ReservationConsumer(RecordingMenuHoldService menuHoldService) {
            this.menuHoldService = menuHoldService;
        }

        private MenuHoldCommandResult create(String idempotencyKey, String reservationId) {
            MenuHoldCommandResult replay = replayResults.get(idempotencyKey);
            if (replay != null) {
                return replay;
            }
            menuHoldService.events.add("capacity-locked");
            String operationId = "operation-" + operations.incrementAndGet();
            MenuHoldCommandResult result = menuHoldService.create(new MenuHoldCreateCommand(
                    reservationId,
                    operationId,
                    List.of(new MenuSelection("menu-01", 1))));
            replayResults.put(idempotencyKey, result);
            return result;
        }

        private MenuHoldCommandResult fulfill(String reservationId, String sourceOperationId) {
            return menuHoldService.fulfill(new MenuHoldFulfillCommand(
                    reservationId,
                    "operation-" + operations.incrementAndGet(),
                    sourceOperationId));
        }
    }

    private static final class RecordingMenuHoldService implements MenuHoldService {
        private final List<String> events = new ArrayList<>();
        private final List<String> createOperations = new ArrayList<>();
        private final List<String> fulfillOperations = new ArrayList<>();
        private ServiceException failure;

        @Override
        public MenuHoldCommandResult create(MenuHoldCreateCommand command) {
            if (failure != null) {
                throw failure;
            }
            events.add("menu-hold-created");
            createOperations.add(command.operationId());
            return MenuHoldCommandResult.confirmed(
                    command.reservationId(), command.operationId());
        }

        @Override
        public MenuHoldCommandResult release(MenuHoldReleaseCommand command) {
            return MenuHoldCommandResult.released(
                    command.reservationId(), command.sourceAcquireOperationId());
        }

        @Override
        public MenuHoldCommandResult fulfill(MenuHoldFulfillCommand command) {
            fulfillOperations.add(command.operationId());
            return MenuHoldCommandResult.fulfilled(
                    command.reservationId(), command.sourceAcquireOperationId());
        }
    }
}
