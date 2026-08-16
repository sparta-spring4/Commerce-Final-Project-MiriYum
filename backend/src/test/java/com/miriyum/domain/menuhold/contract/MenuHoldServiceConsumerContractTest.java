package com.miriyum.domain.menuhold.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.menuhold.dto.MenuHoldCommandResult;
import com.miriyum.domain.menuhold.dto.MenuHoldCreateCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldForfeitCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldFulfillCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldReleaseCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldTerminationPresence;
import com.miriyum.domain.menuhold.dto.MenuSelection;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.service.MenuHoldService;
import com.miriyum.global.exception.ServiceException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
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
        assertMandatory("forfeit", MenuHoldForfeitCommand.class);
        Method terminationLock = MenuHoldService.class.getMethod(
                "lockForTermination", long.class);
        Transactional transactional = terminationLock.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(terminationLock.getReturnType())
                .isEqualTo(MenuHoldTerminationPresence.class);
    }

    @Test
    void terminationPresenceExposesOnlyPersistentHoldPresence() {
        assertThat(MenuHoldTerminationPresence.values()).containsExactly(
                MenuHoldTerminationPresence.HOLD_PRESENT,
                MenuHoldTerminationPresence.NO_HOLD);
    }

    @Test
    void createCommandCarriesAllContextNeededWithoutReservationTypes() {
        MenuHoldCreateCommand command = createCommand(
                1L, "operation-create-01", List.of(selection()));

        assertThat(command.reservationId()).isEqualTo(1L);
        assertThat(command.storeId()).isEqualTo(2L);
        assertThat(command.consumerAccountId()).isEqualTo(3L);
        assertThat(command.serviceDate()).isEqualTo(SERVICE_DATE);
        assertThat(command.startTime()).isEqualTo(START_TIME);
        assertThat(command.endDate()).isEqualTo(SERVICE_DATE);
        assertThat(command.endTime()).isEqualTo(END_TIME);
        assertThat(command.operationId()).isEqualTo("operation-create-01");
        assertThat(command.menuSelections()).containsExactly(selection());
    }

    @Test
    void internalCommandsRejectNonPositiveIds() {
        assertThatThrownBy(() -> createCommandWithIds(0L, 2L, 3L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reservationId must be positive");
        assertThatThrownBy(() -> createCommandWithIds(1L, 0L, 3L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("storeId must be positive");
        assertThatThrownBy(() -> createCommandWithIds(1L, 2L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("consumerAccountId must be positive");
        assertThatThrownBy(() -> new MenuSelection(0L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("menuId must be positive");
        assertThatThrownBy(() -> new MenuHoldForfeitCommand(0L, "operation-forfeit-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reservationId must be positive");
        assertThatThrownBy(() -> new MenuHoldForfeitCommand(1L, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("operationId must not be blank");
    }

    @Test
    void createCommandNormalizesDuplicateMenusBeforeTheFixtureRecordsIt() {
        ReservationMenuHoldContractFixture fixture =
                ReservationMenuHoldContractFixture.succeeding();
        MenuHoldCreateCommand command = createCommand(
                1L,
                "operation-create-01",
                List.of(
                        new MenuSelection(4L, 2),
                        new MenuSelection(5L, 1),
                        new MenuSelection(4L, 3)));

        fixture.create(command);

        assertThat(fixture.createCommands().getFirst().menuSelections()).containsExactly(
                new MenuSelection(4L, 5),
                new MenuSelection(5L, 1));
    }

    @Test
    void createCommandRejectsAnOverflowingDuplicateMenuQuantity() {
        assertThatThrownBy(() -> createCommand(
                1L,
                "operation-create-01",
                List.of(
                        new MenuSelection(4L, Integer.MAX_VALUE),
                        new MenuSelection(4L, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("menu selection quantity sum exceeds integer range");
    }

    @Test
    void createCommandAcceptsAnOvernightServiceWindow() {
        MenuHoldCreateCommand command = new MenuHoldCreateCommand(
                1L,
                2L,
                3L,
                SERVICE_DATE,
                LocalTime.of(23, 30),
                SERVICE_DATE.plusDays(1),
                LocalTime.of(0, 30),
                Instant.parse("2026-08-10T14:30:00Z"),
                Instant.parse("2026-08-10T15:30:00Z"),
                "operation-create-01",
                List.of(selection()));

        assertThat(command.endDate()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(command.endTime()).isEqualTo(LocalTime.of(0, 30));
    }

    @Test
    void createCommandRejectsAServiceWindowThatDoesNotIncrease() {
        assertThatThrownBy(() -> new MenuHoldCreateCommand(
                1L,
                2L,
                3L,
                SERVICE_DATE,
                LocalTime.of(23, 30),
                SERVICE_DATE,
                LocalTime.of(0, 30),
                Instant.parse("2026-08-10T14:30:00Z"),
                Instant.parse("2026-08-10T15:30:00Z"),
                "operation-create-01",
                List.of(selection())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("service time range must be increasing");
    }

    @Test
    void terminalCommandsHideTheSourceAcquireOperationFromReservation() {
        MenuHoldReleaseCommand release =
                new MenuHoldReleaseCommand(1L, "operation-release-01");
        MenuHoldFulfillCommand fulfill =
                new MenuHoldFulfillCommand(1L, "operation-fulfill-01");
        MenuHoldForfeitCommand forfeit =
                new MenuHoldForfeitCommand(1L, "operation-forfeit-01");

        assertThat(release.reservationId()).isEqualTo(1L);
        assertThat(release.operationId()).isEqualTo("operation-release-01");
        assertThat(fulfill.reservationId()).isEqualTo(1L);
        assertThat(fulfill.operationId()).isEqualTo("operation-fulfill-01");
        assertThat(forfeit.reservationId()).isEqualTo(1L);
        assertThat(forfeit.operationId()).isEqualTo("operation-forfeit-01");
    }

    @Test
    void commandResultExposesOnlyReservationOutcome() {
        MenuHoldCommandResult result =
                MenuHoldCommandResult.confirmed(1L);

        assertThat(result.reservationId()).isEqualTo(1L);
        assertThat(result.outcome()).isEqualTo(MenuHoldCommandResult.Outcome.CONFIRMED);
        assertThat(MenuHoldCommandResult.forfeited(1L).outcome())
                .isEqualTo(MenuHoldCommandResult.Outcome.FORFEITED);
    }

    @Test
    void reservationConsumerSkipsMenuHoldForEmptySelections() {
        ReservationMenuHoldContractFixture fixture =
                ReservationMenuHoldContractFixture.succeeding();
        ReservationConsumer consumer = new ReservationConsumer(fixture);

        MenuHoldCommandResult result = consumer.create(
                "idempotency-key", 1L, List.of());

        assertThat(result).isEqualTo(MenuHoldCommandResult.noHold(1L));
        assertThat(fixture.createCommands()).isEmpty();
    }

    @Test
    void reservationConsumerLocksCapacityBeforeCreateAndSkipsReplay() {
        ReservationMenuHoldContractFixture fixture =
                ReservationMenuHoldContractFixture.succeeding();
        AtomicInteger generatedOperationIds = new AtomicInteger();
        ReservationConsumer consumer = new ReservationConsumer(
                fixture,
                () -> "operation-" + generatedOperationIds.incrementAndGet());

        MenuHoldCommandResult first = consumer.create(
                "idempotency-key", 1L, List.of(selection()));
        MenuHoldCommandResult replay = consumer.create(
                "idempotency-key", 1L, List.of(selection()));

        assertThat(consumer.events).containsExactly("capacity-locked", "menu-hold-created");
        assertThat(fixture.createCommands()).hasSize(1);
        assertThat(generatedOperationIds).hasValue(1);
        assertThat(replay).isSameAs(first);
    }

    @Test
    void reservationConsumerUsesNewOperationForEveryLogicalCommand() {
        ReservationMenuHoldContractFixture fixture =
                ReservationMenuHoldContractFixture.succeeding();
        ReservationConsumer consumer = new ReservationConsumer(fixture);

        consumer.create("create-key", 1L, List.of(selection()));
        consumer.release(1L);
        consumer.fulfill(2L);
        consumer.forfeit(3L);

        assertThat(List.of(
                fixture.createCommands().getFirst().operationId(),
                fixture.releaseCommands().getFirst().operationId(),
                fixture.fulfillCommands().getFirst().operationId(),
                fixture.forfeitCommands().getFirst().operationId()))
                .doesNotHaveDuplicates();
    }

    @Test
    void reservationConsumerPrelocksHoldBeforeCapacityAndThenReleasesIt() {
        ReservationMenuHoldContractFixture fixture =
                ReservationMenuHoldContractFixture.succeeding();
        ReservationConsumer consumer = new ReservationConsumer(fixture, () -> "cancel-operation");

        consumer.cancel(10L);

        assertThat(consumer.events).containsExactly(
                "idempotency-claimed",
                "reservation-locked",
                "menu-hold-prelocked",
                "capacity-restored",
                "menu-hold-released");
        assertThat(fixture.terminationLockReservationIds()).containsExactly(10L);
        assertThat(fixture.releaseCommands()).containsExactly(
                new MenuHoldReleaseCommand(10L, "cancel-operation"));
    }

    @Test
    void reservationConsumerStillRestoresCapacityButSkipsReleaseWhenNoHoldExists() {
        ReservationMenuHoldContractFixture fixture =
                ReservationMenuHoldContractFixture.succeedingWithNoHold();
        ReservationConsumer consumer = new ReservationConsumer(fixture);

        consumer.cancel(10L);

        assertThat(consumer.events).containsExactly(
                "idempotency-claimed",
                "reservation-locked",
                "menu-hold-prelocked",
                "capacity-restored");
        assertThat(fixture.terminationLockReservationIds()).containsExactly(10L);
        assertThat(fixture.releaseCommands()).isEmpty();
    }

    @Test
    void reservationConsumersUseGloballyUniqueOperationsAcrossInstances() {
        ReservationMenuHoldContractFixture fixture =
                ReservationMenuHoldContractFixture.succeeding();
        ReservationConsumer firstConsumer = new ReservationConsumer(fixture);
        ReservationConsumer secondConsumer = new ReservationConsumer(fixture);

        firstConsumer.create("first-key", 1L, List.of(selection()));
        secondConsumer.create("second-key", 2L, List.of(selection()));

        assertThat(fixture.createCommands())
                .extracting(MenuHoldCreateCommand::operationId)
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
                "create-key", 1L, List.of(selection())))
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
            long reservationId,
            String operationId,
            List<MenuSelection> selections
    ) {
        return new MenuHoldCreateCommand(
                reservationId,
                2L,
                3L,
                SERVICE_DATE,
                START_TIME,
                SERVICE_DATE,
                END_TIME,
                Instant.parse("2026-08-10T03:00:00Z"),
                Instant.parse("2026-08-10T04:00:00Z"),
                operationId,
                selections);
    }

    private static MenuHoldCreateCommand createCommandWithIds(
            long reservationId,
            long storeId,
            long consumerAccountId
    ) {
        return new MenuHoldCreateCommand(
                reservationId,
                storeId,
                consumerAccountId,
                SERVICE_DATE,
                START_TIME,
                SERVICE_DATE,
                END_TIME,
                Instant.parse("2026-08-10T03:00:00Z"),
                Instant.parse("2026-08-10T04:00:00Z"),
                "operation-create-01",
                List.of(selection()));
    }

    private static MenuSelection selection() {
        return new MenuSelection(4L, 2);
    }

    private static final class ReservationConsumer {
        private final ReservationMenuHoldContractFixture menuHoldService;
        private final Supplier<String> operationIdGenerator;
        private final Map<String, MenuHoldCommandResult> replayResults = new HashMap<>();
        private final java.util.ArrayList<String> events = new java.util.ArrayList<>();

        private ReservationConsumer(ReservationMenuHoldContractFixture menuHoldService) {
            this(menuHoldService, () -> UUID.randomUUID().toString());
        }

        private ReservationConsumer(
                ReservationMenuHoldContractFixture menuHoldService,
                Supplier<String> operationIdGenerator
        ) {
            this.menuHoldService = menuHoldService;
            this.operationIdGenerator = operationIdGenerator;
        }

        private MenuHoldCommandResult create(
                String idempotencyKey,
                long reservationId,
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

        private MenuHoldCommandResult release(long reservationId) {
            return menuHoldService.release(
                    new MenuHoldReleaseCommand(reservationId, nextOperationId()));
        }

        private void cancel(long reservationId) {
            events.add("idempotency-claimed");
            events.add("reservation-locked");
            MenuHoldTerminationPresence presence =
                    menuHoldService.lockForTermination(reservationId);
            events.add("menu-hold-prelocked");
            events.add("capacity-restored");
            if (presence == MenuHoldTerminationPresence.HOLD_PRESENT) {
                release(reservationId);
                events.add("menu-hold-released");
            }
        }

        private MenuHoldCommandResult fulfill(long reservationId) {
            return menuHoldService.fulfill(
                    new MenuHoldFulfillCommand(reservationId, nextOperationId()));
        }

        private MenuHoldCommandResult forfeit(long reservationId) {
            return menuHoldService.forfeit(
                    new MenuHoldForfeitCommand(reservationId, nextOperationId()));
        }

        private String nextOperationId() {
            return operationIdGenerator.get();
        }
    }
}
