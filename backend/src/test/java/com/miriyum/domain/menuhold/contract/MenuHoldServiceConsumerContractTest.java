package com.miriyum.domain.menuhold.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.menuhold.dto.MenuHoldCommandResult;
import com.miriyum.domain.menuhold.dto.MenuHoldCreateCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldFulfillCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldReleaseCommand;
import com.miriyum.domain.menuhold.dto.MenuSelection;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.service.MenuHoldService;
import com.miriyum.global.exception.ServiceException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class MenuHoldServiceConsumerContractTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 10);
    private static final LocalTime START_TIME = LocalTime.of(12, 0);
    private static final LocalTime END_TIME = LocalTime.of(13, 0);

    @Test
    void exposesReservationCommandsThatMustJoinTheCallerTransaction() throws Exception {
        assertMandatory("create", MenuHoldCreateCommand.class);
        assertMandatory("release", MenuHoldReleaseCommand.class);
        assertMandatory("fulfill", MenuHoldFulfillCommand.class);
    }

    @Test
    void createCommandCarriesAllContextNeededWithoutReservationTypes() {
        MenuHoldCreateCommand command = createCommand(
                "reservation-01", "operation-create-01", List.of(selection()));

        assertThat(command.reservationId()).isEqualTo("reservation-01");
        assertThat(command.storeId()).isEqualTo("store-01");
        assertThat(command.consumerAccountId()).isEqualTo("consumer-01");
        assertThat(command.serviceDate()).isEqualTo(SERVICE_DATE);
        assertThat(command.startTime()).isEqualTo(START_TIME);
        assertThat(command.endDate()).isEqualTo(SERVICE_DATE);
        assertThat(command.endTime()).isEqualTo(END_TIME);
        assertThat(command.operationId()).isEqualTo("operation-create-01");
        assertThat(command.menuSelections()).containsExactly(selection());
    }

    @Test
    void createCommandAcceptsAnOvernightServiceWindow() {
        MenuHoldCreateCommand command = new MenuHoldCreateCommand(
                "reservation-01",
                "store-01",
                "consumer-01",
                SERVICE_DATE,
                LocalTime.of(23, 30),
                SERVICE_DATE.plusDays(1),
                LocalTime.of(0, 30),
                "operation-create-01",
                List.of(selection()));

        assertThat(command.endDate()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(command.endTime()).isEqualTo(LocalTime.of(0, 30));
    }

    @Test
    void createCommandRejectsAServiceWindowThatDoesNotIncrease() {
        assertThatThrownBy(() -> new MenuHoldCreateCommand(
                "reservation-01",
                "store-01",
                "consumer-01",
                SERVICE_DATE,
                LocalTime.of(23, 30),
                SERVICE_DATE,
                LocalTime.of(0, 30),
                "operation-create-01",
                List.of(selection())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("service time range must be increasing");
    }

    @Test
    void releaseAndFulfillHideTheSourceAcquireOperationFromReservation() {
        MenuHoldReleaseCommand release =
                new MenuHoldReleaseCommand("reservation-01", "operation-release-01");
        MenuHoldFulfillCommand fulfill =
                new MenuHoldFulfillCommand("reservation-01", "operation-fulfill-01");

        assertThat(release.reservationId()).isEqualTo("reservation-01");
        assertThat(release.operationId()).isEqualTo("operation-release-01");
        assertThat(fulfill.reservationId()).isEqualTo("reservation-01");
        assertThat(fulfill.operationId()).isEqualTo("operation-fulfill-01");
    }

    @Test
    void commandResultExposesOnlyReservationOutcome() {
        MenuHoldCommandResult result =
                MenuHoldCommandResult.confirmed("reservation-01");

        assertThat(result.reservationId()).isEqualTo("reservation-01");
        assertThat(result.outcome()).isEqualTo(MenuHoldCommandResult.Outcome.CONFIRMED);
    }

    @Test
    void reservationConsumerSkipsMenuHoldForEmptySelections() {
        ReservationMenuHoldContractFixture fixture =
                ReservationMenuHoldContractFixture.succeeding();
        ReservationConsumer consumer = new ReservationConsumer(fixture);

        MenuHoldCommandResult result = consumer.create(
                "idempotency-key", "reservation-01", List.of());

        assertThat(result).isEqualTo(MenuHoldCommandResult.noHold("reservation-01"));
        assertThat(fixture.createCommands()).isEmpty();
    }

    @Test
    void reservationConsumerLocksCapacityBeforeCreateAndSkipsReplay() {
        ReservationMenuHoldContractFixture fixture =
                ReservationMenuHoldContractFixture.succeeding();
        ReservationConsumer consumer = new ReservationConsumer(fixture);

        MenuHoldCommandResult first = consumer.create(
                "idempotency-key", "reservation-01", List.of(selection()));
        MenuHoldCommandResult replay = consumer.create(
                "idempotency-key", "reservation-01", List.of(selection()));

        assertThat(consumer.events).containsExactly("capacity-locked", "menu-hold-created");
        assertThat(fixture.createCommands()).hasSize(1);
        assertThat(replay).isSameAs(first);
    }

    @Test
    void reservationConsumerUsesNewOperationForEveryLogicalCommand() {
        ReservationMenuHoldContractFixture fixture =
                ReservationMenuHoldContractFixture.succeeding();
        ReservationConsumer consumer = new ReservationConsumer(fixture);

        consumer.create("create-key", "reservation-01", List.of(selection()));
        consumer.release("reservation-01");
        consumer.fulfill("reservation-02");

        assertThat(List.of(
                fixture.createCommands().getFirst().operationId(),
                fixture.releaseCommands().getFirst().operationId(),
                fixture.fulfillCommands().getFirst().operationId()))
                .doesNotHaveDuplicates();
    }

    @Test
    void fixturePreservesMenuEligibilityAndQuantityErrors() {
        ServiceException ineligible =
                new ServiceException(MenuHoldErrorCode.INELIGIBLE_MENU);
        ReservationMenuHoldContractFixture fixture =
                ReservationMenuHoldContractFixture.failing(ineligible);
        ReservationConsumer consumer = new ReservationConsumer(fixture);

        assertThatThrownBy(() -> consumer.create(
                "create-key", "reservation-01", List.of(selection())))
                .isSameAs(ineligible);
        assertThat(MenuHoldErrorCode.INELIGIBLE_MENU.getCode()).isEqualTo("MENU_HOLD_001");
        assertThat(MenuHoldErrorCode.INSUFFICIENT_QUANTITY.getCode()).isEqualTo("MENU_HOLD_002");
    }

    private static void assertMandatory(String methodName, Class<?> commandType)
            throws NoSuchMethodException {
        Method method = MenuHoldService.class.getMethod(methodName, commandType);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(method.getReturnType()).isEqualTo(MenuHoldCommandResult.class);
    }

    private static MenuHoldCreateCommand createCommand(
            String reservationId,
            String operationId,
            List<MenuSelection> selections
    ) {
        return new MenuHoldCreateCommand(
                reservationId,
                "store-01",
                "consumer-01",
                SERVICE_DATE,
                START_TIME,
                SERVICE_DATE,
                END_TIME,
                operationId,
                selections);
    }

    private static MenuSelection selection() {
        return new MenuSelection("menu-01", 2);
    }

    private static final class ReservationConsumer {
        private final ReservationMenuHoldContractFixture menuHoldService;
        private final AtomicInteger operations = new AtomicInteger();
        private final Map<String, MenuHoldCommandResult> replayResults = new HashMap<>();
        private final java.util.ArrayList<String> events = new java.util.ArrayList<>();

        private ReservationConsumer(ReservationMenuHoldContractFixture menuHoldService) {
            this.menuHoldService = menuHoldService;
        }

        private MenuHoldCommandResult create(
                String idempotencyKey,
                String reservationId,
                List<MenuSelection> selections
        ) {
            MenuHoldCommandResult replay = replayResults.get(idempotencyKey);
            if (replay != null) {
                return replay;
            }
            if (selections.isEmpty()) {
                return MenuHoldCommandResult.noHold(reservationId);
            }
            events.add("capacity-locked");
            MenuHoldCommandResult result = menuHoldService.create(createCommand(
                    reservationId, nextOperationId(), selections));
            events.add("menu-hold-created");
            replayResults.put(idempotencyKey, result);
            return result;
        }

        private MenuHoldCommandResult release(String reservationId) {
            return menuHoldService.release(
                    new MenuHoldReleaseCommand(reservationId, nextOperationId()));
        }

        private MenuHoldCommandResult fulfill(String reservationId) {
            return menuHoldService.fulfill(
                    new MenuHoldFulfillCommand(reservationId, nextOperationId()));
        }

        private String nextOperationId() {
            return "operation-" + operations.incrementAndGet();
        }
    }
}
