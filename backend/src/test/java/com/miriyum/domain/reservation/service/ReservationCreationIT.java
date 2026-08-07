package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.domain.reservation.dto.request.ReservationCreateRequest;
import com.miriyum.domain.reservation.dto.request.ReservationMenuSelectionRequest;
import com.miriyum.domain.reservation.dto.request.ReservationPartyRequest;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import com.miriyum.domain.store.closure.entity.RegularClosureVersion;
import com.miriyum.domain.store.closure.repository.RegularClosureVersionRepository;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.menu.entity.Menu;
import com.miriyum.domain.store.menu.model.AllergenDisclosure;
import com.miriyum.domain.store.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.store.menu.model.AllergenIngredientCode;
import com.miriyum.domain.store.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.store.menu.model.MenuContent;
import com.miriyum.domain.store.menu.repository.MenuRepository;
import com.miriyum.domain.store.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.store.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.store.schedule.entity.StoreScheduleState;
import com.miriyum.domain.store.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.store.schedule.model.WeeklyInterval;
import com.miriyum.domain.store.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.store.schedule.repository.ReservationScheduleVersionRepository;
import com.miriyum.domain.store.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-b")
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false"
        }
)
class ReservationCreationIT {

    private static final String TIME_ZONE_ID = "Asia/Seoul";
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 10);
    private static final LocalTime START_TIME = LocalTime.NOON;
    private static final LocalTime SERVICE_END_TIME = LocalTime.of(13, 0);
    private static final Instant ACTIVATED_AT = Instant.parse("2026-08-01T00:00:00Z");
    private static final long EXECUTOR_TERMINATION_TIMEOUT_SECONDS = 5L;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private ReservationCreationCommandFacade commandFacade;

    @Autowired
    private ConsumerAccountRepository consumerRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreScheduleStateRepository scheduleStateRepository;

    @Autowired
    private OperatingScheduleVersionRepository operatingScheduleRepository;

    @Autowired
    private ReservationScheduleVersionRepository reservationScheduleRepository;

    @Autowired
    private RegularClosureVersionRepository regularClosureRepository;

    @Autowired
    private ReservationTimePolicyVersionRepository timePolicyRepository;

    @Autowired
    private ReservationCapacityBucketRepository capacityBucketRepository;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private MenuInventoryBucketRepository inventoryBucketRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void cleanRowsInForeignKeyOrder() {
        jdbcTemplate.execute("DELETE FROM menu_hold_items");
        jdbcTemplate.execute("DELETE FROM menu_holds");
        jdbcTemplate.execute("DELETE FROM menu_inventory_ledger");
        jdbcTemplate.execute("DELETE FROM menu_inventory_policy_audits");
        jdbcTemplate.execute("DELETE FROM menu_inventory_buckets");
        jdbcTemplate.execute("DELETE FROM reservation_capacity_allocations");
        jdbcTemplate.execute("DELETE FROM reservations");
        jdbcTemplate.execute("DELETE FROM reservation_capacity_buckets");
        jdbcTemplate.execute("DELETE FROM reservation_time_policy_audits");
        jdbcTemplate.execute("DELETE FROM reservation_time_policy_versions");
        jdbcTemplate.execute("DELETE FROM menu_publication_events");
        jdbcTemplate.execute("DELETE FROM menu_version_origin_disclosures");
        jdbcTemplate.execute("DELETE FROM menu_version_allergen_disclosures");
        jdbcTemplate.execute("DELETE FROM menu_version_local_tags");
        jdbcTemplate.execute("DELETE FROM menu_version_secondary_categories");
        jdbcTemplate.execute("DELETE FROM menu_versions");
        jdbcTemplate.execute("DELETE FROM menus");
        jdbcTemplate.execute("DELETE FROM store_closure_audit_events");
        jdbcTemplate.execute("DELETE FROM store_temporary_closures");
        jdbcTemplate.execute("DELETE FROM store_schedule_audit_events");
        jdbcTemplate.execute("DELETE FROM store_schedule_state");
        jdbcTemplate.execute("DELETE FROM store_regular_closure_entries");
        jdbcTemplate.execute("DELETE FROM store_regular_closure_versions");
        jdbcTemplate.execute("DELETE FROM store_reservation_schedule_entries");
        jdbcTemplate.execute("DELETE FROM store_reservation_schedule_versions");
        jdbcTemplate.execute("DELETE FROM store_operating_schedule_entries");
        jdbcTemplate.execute("DELETE FROM store_operating_schedule_versions");
        jdbcTemplate.update(
                "DELETE FROM idempotency_commands WHERE command_type = ?",
                "RESERVATION_CREATE");
        jdbcTemplate.execute("DELETE FROM store_tag_assignment");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");
    }

    @Test
    @DisplayName("메뉴 없는 생성은 같은 키를 재생하고 다른 지문 재사용을 거절한다")
    void noMenuCreationReplaysAndRejectsDifferentFingerprint() {
        // given
        Scenario scenario = createScenario(10, 2);
        long consumerId = createConsumer();
        IdempotencyKey key = key(1);
        ReservationCreateRequest request = request(scenario.storeId(), 2, List.of());

        // when
        ReservationCreationCommandResult first =
                commandFacade.create(consumerId, key, request);
        jdbcTemplate.update(
                "UPDATE consumer_accounts "
                        + "SET phone = NULL, reservation_contact_reference = NULL "
                        + "WHERE consumer_account_id = ?",
                consumerId);
        ReservationCreationCommandResult replay =
                commandFacade.create(consumerId, key, request);

        // then
        assertThat(first.httpStatus()).isEqualTo(201);
        assertThat(replay.httpStatus()).isEqualTo(201);
        assertThat(replay.data()).isEqualTo(first.data());
        assertThatThrownBy(() -> commandFacade.create(
                consumerId,
                key,
                request(scenario.storeId(), 3, List.of())
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        assertThat(count("reservations")).isEqualTo(1);
        assertThat(count("reservation_capacity_allocations")).isEqualTo(1);
        assertReservationCreateCommands(1);
        assertThat(count("menu_holds")).isZero();
        assertThat(count("menu_hold_items")).isZero();
        assertThat(count("menu_inventory_ledger")).isZero();
        assertBucketOccupancy(scenario.capacityBucketId(), 2, 1);
    }

    @Test
    @DisplayName("마지막 인원·팀 수용량 경합은 정확히 한 예약만 확정한다")
    void concurrentLastCapacityAllowsExactlyOneReservation() throws Exception {
        // given
        Scenario scenario = createScenario(1, 1);
        long firstConsumerId = createConsumer();
        long secondConsumerId = createConsumer();
        ReservationCreateRequest request = request(scenario.storeId(), 1, List.of());

        // when
        List<CreationAttempt> attempts = invokeConcurrently(
                new CreationInvocation(firstConsumerId, key(2), request),
                new CreationInvocation(secondConsumerId, key(3), request));

        // then
        assertSingleSuccessAndFailure(attempts, ReservationErrorCode.INSUFFICIENT_CAPACITY);
        assertThat(count("reservations")).isEqualTo(1);
        assertThat(count("reservation_capacity_allocations")).isEqualTo(1);
        assertReservationCreateCommands(1);
        assertBucketOccupancy(scenario.capacityBucketId(), 1, 1);
    }

    @Test
    @DisplayName("같은 소비자의 겹치는 동시 예약은 정확히 한 건만 확정한다")
    void concurrentOverlappingRequestsForSameConsumerAllowExactlyOneReservation()
            throws Exception {
        // given
        Scenario scenario = createScenario(10, 10);
        long consumerId = createConsumer();
        ReservationCreateRequest request = request(scenario.storeId(), 1, List.of());

        // when
        List<CreationAttempt> attempts = invokeConcurrently(
                new CreationInvocation(consumerId, key(4), request),
                new CreationInvocation(consumerId, key(5), request));

        // then
        assertSingleSuccessAndFailure(attempts, ReservationErrorCode.DUPLICATE_RESERVATION);
        assertThat(count("reservations")).isEqualTo(1);
        assertThat(count("reservation_capacity_allocations")).isEqualTo(1);
        assertReservationCreateCommands(1);
        assertBucketOccupancy(scenario.capacityBucketId(), 1, 1);
    }

    @Test
    @DisplayName("메뉴 수량 부족은 예약 생성 전체와 멱등 선점을 롤백한다")
    void insufficientMenuInventoryRollsBackReservationCreation() {
        // given
        Scenario scenario = createScenario(10, 10);
        long consumerId = createConsumer();
        MenuInventoryFixture menu = createMenuInventory(scenario, 0);
        Map<String, Object> inventoryBefore = inventorySnapshot(menu.inventoryBucketId());
        ReservationCreateRequest request = request(
                scenario.storeId(),
                1,
                List.of(new ReservationMenuSelectionRequest(
                        String.valueOf(menu.menuId()), 1)));

        // when & then
        assertThatThrownBy(() -> commandFacade.create(consumerId, key(6), request))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(MenuHoldErrorCode.INSUFFICIENT_QUANTITY));

        assertThat(count("reservations")).isZero();
        assertThat(count("reservation_capacity_allocations")).isZero();
        assertThat(count("menu_holds")).isZero();
        assertThat(count("menu_hold_items")).isZero();
        assertThat(count("menu_inventory_ledger")).isZero();
        assertReservationCreateCommands(0);
        assertBucketOccupancy(scenario.capacityBucketId(), 0, 0);
        assertThat(inventorySnapshot(menu.inventoryBucketId())).isEqualTo(inventoryBefore);
    }

    private Scenario createScenario(int maxPeople, int maxTeams) {
        return transactions.execute(status -> {
            int sequence = SEQUENCE.incrementAndGet();
            StoreOperatorAccount operator = operatorRepository.saveAndFlush(
                    StoreOperatorAccount.create(
                            "reservation-owner-" + sequence + "@example.com",
                            "hashed-password",
                            "owner"));
            Store store = storeRepository.saveAndFlush(Store.create(
                    operator.getId(),
                    Long.toString(7_000_000_000L + sequence),
                    BusinessType.CAFE,
                    "MiriYum Reservation Store " + sequence,
                    "",
                    Region.SEOUL,
                    "fixture-address",
                    "CAFE_BAKERY",
                    Set.of(),
                    true,
                    true,
                    false,
                    TIME_ZONE_ID,
                    LocalDateTime.of(2026, 8, 1, 9, 0),
                    "STORE_ONBOARDING_REQUIRED_TERMS_V1"));

            StoreScheduleState scheduleState = StoreScheduleState.initialize(store.getId());
            OperatingScheduleVersion operating = OperatingScheduleVersion.createDraft(
                    store.getId(),
                    scheduleState.allocateOperatingVersion(),
                    TIME_ZONE_ID,
                    List.of(weeklyInterval(
                            LocalTime.of(10, 0),
                            LocalTime.of(18, 0),
                            ScheduleIntervalKind.BUSINESS_HOURS)));
            operating.activate(ACTIVATED_AT, "reservation creation fixture");
            operating = operatingScheduleRepository.saveAndFlush(operating);
            scheduleState.activateOperating(operating.getId());

            ReservationScheduleVersion reservationSchedule =
                    ReservationScheduleVersion.createDraft(
                            store.getId(),
                            scheduleState.allocateReservationVersion(),
                            operating.getId(),
                            TIME_ZONE_ID,
                            List.of(weeklyInterval(
                                    LocalTime.of(11, 0),
                                    LocalTime.of(15, 0),
                                    ScheduleIntervalKind.RESERVATION_SLOT)));
            reservationSchedule.activate(ACTIVATED_AT, "reservation creation fixture");
            reservationSchedule = reservationScheduleRepository.saveAndFlush(
                    reservationSchedule);
            scheduleState.activateReservation(reservationSchedule.getId());

            RegularClosureVersion regularClosure = RegularClosureVersion.createDraft(
                    store.getId(),
                    scheduleState.allocateRegularClosureVersion(),
                    TIME_ZONE_ID,
                    List.of(),
                    List.of());
            regularClosure.activate(ACTIVATED_AT, "reservation creation fixture");
            regularClosure = regularClosureRepository.saveAndFlush(regularClosure);
            scheduleState.activateRegularClosure(regularClosure.getId());
            scheduleStateRepository.saveAndFlush(scheduleState);

            ReservationTimePolicyVersion timePolicy =
                    ReservationTimePolicyVersion.createDraft(
                            store.getId(), 1L, 60, 60, 0);
            timePolicy.activate(ACTIVATED_AT, "reservation creation fixture");
            timePolicyRepository.saveAndFlush(timePolicy);

            ReservationCapacityBucket capacityBucket =
                    capacityBucketRepository.saveAndFlush(
                            ReservationCapacityBucket.create(
                                    store.getId(),
                                    SERVICE_DATE,
                                    START_TIME,
                                    SERVICE_END_TIME,
                                    maxPeople,
                                    maxTeams,
                                    0,
                                    0,
                                    1,
                                    maxPeople,
                                    true,
                                    1L));
            return new Scenario(
                    operator.getId(), store.getId(), capacityBucket.getId());
        });
    }

    private long createConsumer() {
        return transactions.execute(status -> {
            int sequence = SEQUENCE.incrementAndGet();
            return consumerRepository.saveAndFlush(ConsumerAccount.createWithContact(
                    "reservation-consumer-" + sequence + "@example.com",
                    "hashed-password",
                    "consumer",
                    String.format("010%08d", sequence),
                    "opaque-reservation-contact-" + sequence
            )).getId();
        });
    }

    private MenuInventoryFixture createMenuInventory(Scenario scenario, int onlineQuantity) {
        return transactions.execute(status -> {
            Menu menu = Menu.create(
                    scenario.storeId(),
                    menuContent(),
                    scenario.operatorId(),
                    ACTIVATED_AT);
            menu.publish(ACTIVATED_AT);
            menu = menuRepository.saveAndFlush(menu);
            MenuInventoryBucket inventory = inventoryBucketRepository.saveAndFlush(
                    MenuInventoryBucket.create(
                            menu.getId(),
                            SERVICE_DATE,
                            START_TIME,
                            SERVICE_DATE,
                            SERVICE_END_TIME,
                            TIME_ZONE_ID,
                            1L,
                            onlineQuantity,
                            onlineQuantity,
                            0,
                            0,
                            false));
            return new MenuInventoryFixture(menu.getId(), inventory.getId());
        });
    }

    private static MenuContent menuContent() {
        return new MenuContent(
                "Americano",
                "",
                5_000,
                false,
                "BEVERAGE",
                List.of(),
                List.of(),
                true,
                false,
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosure(
                        AllergenIngredientCode.MILK,
                        AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.NOT_APPLICABLE,
                List.of(),
                false);
    }

    private static WeeklyInterval weeklyInterval(
            LocalTime start,
            LocalTime end,
            ScheduleIntervalKind kind
    ) {
        int dayStart = (DayOfWeek.MONDAY.getValue() - 1) * 1440;
        return new WeeklyInterval(
                DayOfWeek.MONDAY,
                start,
                end,
                false,
                kind,
                dayStart + start.getHour() * 60 + start.getMinute(),
                dayStart + end.getHour() * 60 + end.getMinute());
    }

    private static ReservationCreateRequest request(
            long storeId,
            int adultCount,
            List<ReservationMenuSelectionRequest> menuSelections
    ) {
        return new ReservationCreateRequest(
                String.valueOf(storeId),
                SERVICE_DATE,
                START_TIME,
                null,
                new ReservationPartyRequest(adultCount, 0, 0),
                menuSelections);
    }

    private List<CreationAttempt> invokeConcurrently(
            CreationInvocation first,
            CreationInvocation second
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(
                2, reservationCreationWorkerFactory());
        Future<CreationAttempt> firstFuture = null;
        Future<CreationAttempt> secondFuture = null;
        List<CreationAttempt> attempts = null;
        Throwable failure = null;
        try {
            firstFuture = executor.submit(
                    () -> invokeAfterStart(first, ready, start));
            secondFuture = executor.submit(
                    () -> invokeAfterStart(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            attempts = List.of(
                    firstFuture.get(30, TimeUnit.SECONDS),
                    secondFuture.get(30, TimeUnit.SECONDS));
        } catch (Throwable thrown) {
            failure = thrown;
        } finally {
            Throwable cleanupFailure = stopWorkers(
                    start, firstFuture, secondFuture, executor);
            if (cleanupFailure != null) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        rethrow(failure);
        return attempts;
    }

    private static ThreadFactory reservationCreationWorkerFactory() {
        return task -> {
            Thread worker = new Thread(
                    task,
                    "reservation-creation-it-worker-" + WORKER_SEQUENCE.incrementAndGet());
            worker.setDaemon(true);
            return worker;
        };
    }

    private static Throwable stopWorkers(
            CountDownLatch start,
            Future<?> firstFuture,
            Future<?> secondFuture,
            ExecutorService executor
    ) {
        start.countDown();
        cancelIfRunning(firstFuture);
        cancelIfRunning(secondFuture);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(
                    EXECUTOR_TERMINATION_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS)) {
                return new AssertionError(
                        "reservation creation workers did not terminate after cancellation");
            }
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new AssertionError(
                    "reservation creation worker cleanup was interrupted",
                    exception);
        }
    }

    private static void cancelIfRunning(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure == null) {
            return;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        throw new AssertionError("unexpected concurrent reservation failure", failure);
    }

    private CreationAttempt invokeAfterStart(
            CreationInvocation invocation,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent reservation start signal timed out");
        }
        try {
            return CreationAttempt.succeeded(commandFacade.create(
                    invocation.consumerAccountId(),
                    invocation.key(),
                    invocation.request()));
        } catch (ServiceException exception) {
            return CreationAttempt.failed(exception.getErrorCode());
        }
    }

    private static void assertSingleSuccessAndFailure(
            List<CreationAttempt> attempts,
            ErrorCode expectedFailure
    ) {
        assertThat(attempts)
                .filteredOn(attempt -> attempt.result() != null)
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.result().httpStatus()).isEqualTo(201));
        assertThat(attempts)
                .filteredOn(attempt -> attempt.errorCode() != null)
                .singleElement()
                .extracting(CreationAttempt::errorCode)
                .isEqualTo(expectedFailure);
    }

    private void assertBucketOccupancy(
            long capacityBucketId,
            int occupiedPeople,
            int occupiedTeams
    ) {
        assertThat(jdbcTemplate.queryForMap(
                "SELECT occupied_people, occupied_teams "
                        + "FROM reservation_capacity_buckets "
                        + "WHERE reservation_capacity_bucket_id = ?",
                capacityBucketId
        )).containsEntry("occupied_people", occupiedPeople)
                .containsEntry("occupied_teams", occupiedTeams);
    }

    private Map<String, Object> inventorySnapshot(long inventoryBucketId) {
        return jdbcTemplate.queryForMap(
                "SELECT total_supply, online_hold_capacity, online_hold_remaining, "
                        + "onsite_capacity, onsite_remaining, shared_capacity, "
                        + "shared_remaining, lock_version "
                        + "FROM menu_inventory_buckets "
                        + "WHERE menu_inventory_bucket_id = ?",
                inventoryBucketId);
    }

    private void assertReservationCreateCommands(int expectedCount) {
        int totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_commands "
                        + "WHERE command_type = 'RESERVATION_CREATE'",
                Integer.class);
        int succeededCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_commands "
                        + "WHERE command_type = 'RESERVATION_CREATE' "
                        + "AND processing_status = 'SUCCEEDED'",
                Integer.class);
        assertThat(totalCount).isEqualTo(expectedCount);
        assertThat(succeededCount).isEqualTo(expectedCount);
    }

    private int count(String tableName) {
        Set<String> allowed = Set.of(
                "reservations",
                "reservation_capacity_allocations",
                "menu_holds",
                "menu_hold_items",
                "menu_inventory_ledger");
        if (!allowed.contains(tableName)) {
            throw new IllegalArgumentException("unsupported count table");
        }
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName,
                Integer.class);
    }

    private static IdempotencyKey key(int suffix) {
        return IdempotencyKey.parse(String.format(
                java.util.Locale.ROOT,
                "550e8400-e29b-41d4-a716-%012d", suffix));
    }

    private record Scenario(
            long operatorId,
            long storeId,
            long capacityBucketId
    ) {
    }

    private record MenuInventoryFixture(
            long menuId,
            long inventoryBucketId
    ) {
    }

    private record CreationInvocation(
            long consumerAccountId,
            IdempotencyKey key,
            ReservationCreateRequest request
    ) {
    }

    private record CreationAttempt(
            ReservationCreationCommandResult result,
            ErrorCode errorCode
    ) {
        private static CreationAttempt succeeded(ReservationCreationCommandResult result) {
            return new CreationAttempt(result, null);
        }

        private static CreationAttempt failed(ErrorCode errorCode) {
            return new CreationAttempt(null, errorCode);
        }
    }
}
