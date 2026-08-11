package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.domain.reservation.dto.request.ReservationFulfillmentRequest;
import com.miriyum.domain.reservation.dto.request.StoreCancellationRequest;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.model.AllergenDisclosure;
import com.miriyum.domain.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.menu.model.AllergenIngredientCode;
import com.miriyum.domain.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.menu.model.MenuContent;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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

@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false"
        }
)
class ReservationFulfillmentIT {

    private static final DockerImageName MYSQL_IMAGE =
            DockerImageName.parse("mysql:8.0.40");
    private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withCommand("--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private ReservationFulfillmentCommandFacade fulfillmentFacade;

    @Autowired
    private ReservationCancellationCommandFacade cancellationFacade;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private ConsumerAccountRepository consumerRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationCapacityBucketRepository capacityBucketRepository;

    @Autowired
    private ReservationCapacityAllocationRepository allocationRepository;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private MenuInventoryBucketRepository inventoryBucketRepository;

    @AfterEach
    void dropFailureTriggers() {
        for (FailurePoint point : FailurePoint.values()) {
            dropFailureTrigger(point);
        }
    }

    @ParameterizedTest(name = "withHold={0}")
    @ValueSource(booleans = {true, false})
    void commitsReservationHoldAuditAndIdempotencyWithoutChangingResources(
            boolean withHold
    ) {
        Scenario scenario = confirmedScenario(withHold);
        ResourceSnapshot before = snapshot(scenario);

        ReservationFulfillmentCommandResult result = fulfillmentFacade.fulfill(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                key(10 + (withHold ? 1 : 0)), new ReservationFulfillmentRequest());
        ResourceSnapshot after = snapshot(scenario);

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(after.reservation().get("status")).isEqualTo("FULFILLED");
        assertThat(after.holds()).allSatisfy(row ->
                assertThat(row.get("status")).isEqualTo("FULFILLED"));
        if (!withHold) {
            assertThat(after.holds()).isEmpty();
        }
        assertThat(after.capacities()).isEqualTo(before.capacities());
        assertThat(after.allocations()).isEqualTo(before.allocations());
        assertThat(after.inventory()).isEqualTo(before.inventory());
        assertThat(after.ledger()).isEqualTo(before.ledger());
        assertThat(after.audits()).hasSize(1);
        assertThat(after.audits().getFirst().get("occurred_at"))
                .isEqualTo(after.reservation().get("fulfilled_at"));
        assertThat(after.idempotency()).singleElement()
                .satisfies(row -> assertThat(row.get("processing_status"))
                        .isEqualTo("SUCCEEDED"));
    }

    @Test
    void fulfillsConfirmedReservationOwnedByClosedStore() {
        Scenario scenario = confirmedScenario(false, true);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT operation_status FROM stores WHERE store_id = ?",
                String.class,
                scenario.storeId())).isEqualTo("CLOSED");
        assertThat(fulfillmentFacade.fulfill(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                key(12), new ReservationFulfillmentRequest()).httpStatus()).isEqualTo(200);

        ResourceSnapshot after = snapshot(scenario);
        assertThat(after.reservation().get("status")).isEqualTo("FULFILLED");
        assertThat(after.audits()).hasSize(1);
    }

    @Test
    void sameKeyContentionBlocksOnIdempotencyAndReturnsOneEqualStoredResult()
            throws Exception {
        Scenario scenario = confirmedScenario(true);
        IdempotencyKey key = key(200);
        String fingerprint = fulfillmentFingerprint(
                scenario.storeId(), scenario.reservationId());
        ResourceSnapshot before = snapshot(scenario);
        CountDownLatch holderLocked = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch startWorkers = new CountDownLatch(1);
        AtomicLong holderConnectionId = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(3, workerFactory());
        Future<Long> holder = null;
        Future<Attempt> first = null;
        Future<Attempt> second = null;
        try {
            holder = executor.submit(() -> transactions.execute(status -> {
                jdbcTemplate.update("""
                        INSERT INTO idempotency_commands (
                          principal_namespace, principal_id, command_type, idempotency_key,
                          request_fingerprint, processing_status, created_at, updated_at
                        ) VALUES ('store-operator', ?, 'RESERVATION_FULFILL', ?, ?,
                                  'PROCESSING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                        """, scenario.operatorId(), key.value(), fingerprint);
                holderConnectionId.set(jdbcTemplate.queryForObject(
                        "SELECT CONNECTION_ID()", Long.class));
                holderLocked.countDown();
                awaitLatch(releaseHolder, "idempotency holder release");
                status.setRollbackOnly();
                return holderConnectionId.get();
            }));
            assertThat(holderLocked.await(5, TimeUnit.SECONDS)).isTrue();
            first = executor.submit(() -> fulfillAfterStart(
                    scenario, key, workersReady, startWorkers));
            second = executor.submit(() -> fulfillAfterStart(
                    scenario, key, workersReady, startWorkers));
            assertThat(workersReady.await(5, TimeUnit.SECONDS)).isTrue();
            startWorkers.countDown();
            assertFutureBlocked(first);
            assertFutureBlocked(second);
            awaitBlockingWaits(holderConnectionId.get(),
                    "idempotency_commands", "uk_idempotency_commands", 2);
            assertThat(snapshot(scenario)).isEqualTo(before);

            releaseHolder.countDown();
            holder.get(10, TimeUnit.SECONDS);
            Attempt firstAttempt = first.get(15, TimeUnit.SECONDS);
            Attempt secondAttempt = second.get(15, TimeUnit.SECONDS);
            assertThat(firstAttempt.errorCode()).isNull();
            assertThat(secondAttempt.errorCode()).isNull();
            assertThat(firstAttempt.result()).isEqualTo(secondAttempt.result());
            ResourceSnapshot after = snapshot(scenario);
            assertThat(after.audits()).hasSize(1);
            assertThat(after.idempotency()).singleElement().satisfies(row -> {
                assertThat(row.get("idempotency_key")).isEqualTo(key.value());
                assertThat(row.get("processing_status")).isEqualTo("SUCCEEDED");
                assertThat(row.get("result_http_status")).isEqualTo(200);
            });
        } finally {
            holderLocked.countDown();
            releaseHolder.countDown();
            workersReady.countDown();
            workersReady.countDown();
            startWorkers.countDown();
            cancelIfRunning(holder);
            cancelIfRunning(first);
            cancelIfRunning(second);
            shutdownAndAwait(executor);
        }
    }

    @Test
    void differentKeysBlockOnReservationAndProduceOneSuccessOneReservation005()
            throws Exception {
        Scenario scenario = confirmedScenario(true);
        CountDownLatch holderLocked = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch startWorkers = new CountDownLatch(1);
        AtomicLong holderConnectionId = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(3, workerFactory());
        Future<Long> holder = null;
        Future<Attempt> first = null;
        Future<Attempt> second = null;
        try {
            holder = executor.submit(() -> transactions.execute(status -> {
                jdbcTemplate.queryForObject(
                        "SELECT reservation_id FROM reservations "
                                + "WHERE reservation_id = ? FOR UPDATE",
                        Long.class,
                        scenario.reservationId());
                holderConnectionId.set(jdbcTemplate.queryForObject(
                        "SELECT CONNECTION_ID()", Long.class));
                holderLocked.countDown();
                awaitLatch(releaseHolder, "reservation holder release");
                return holderConnectionId.get();
            }));
            assertThat(holderLocked.await(5, TimeUnit.SECONDS)).isTrue();
            first = executor.submit(() -> fulfillAfterStart(
                    scenario, key(201), workersReady, startWorkers));
            second = executor.submit(() -> fulfillAfterStart(
                    scenario, key(202), workersReady, startWorkers));
            assertThat(workersReady.await(5, TimeUnit.SECONDS)).isTrue();
            startWorkers.countDown();
            assertFutureBlocked(first);
            assertFutureBlocked(second);
            awaitBlockingWaits(holderConnectionId.get(), "reservations", "PRIMARY", 2);
            releaseHolder.countDown();
            holder.get(10, TimeUnit.SECONDS);
            List<Attempt> attempts = List.of(
                    first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS));
            assertThat(attempts).filteredOn(Attempt::succeeded).hasSize(1);
            assertThat(attempts).filteredOn(attempt ->
                    attempt.errorCode() == ReservationErrorCode.INVALID_STATE_TRANSITION)
                    .hasSize(1);
            assertThat(snapshot(scenario).audits()).hasSize(1);
        } finally {
            holderLocked.countDown();
            releaseHolder.countDown();
            workersReady.countDown();
            workersReady.countDown();
            startWorkers.countDown();
            cancelIfRunning(holder);
            cancelIfRunning(first);
            cancelIfRunning(second);
            shutdownAndAwait(executor);
        }
    }

    @Test
    void cancellationAndFulfillmentRaceHasOneTerminalWinner() throws Exception {
        Scenario scenario = confirmedScenario(true);
        ResourceSnapshot before = snapshot(scenario);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch startWorkers = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2, workerFactory());
        Future<Attempt> cancellation = null;
        Future<Attempt> fulfillment = null;
        try {
            cancellation = executor.submit(() -> cancelAfterStart(
                    scenario, key(250), workersReady, startWorkers));
            fulfillment = executor.submit(() -> fulfillAfterStart(
                    scenario, key(251), workersReady, startWorkers));
            assertThat(workersReady.await(5, TimeUnit.SECONDS)).isTrue();
            startWorkers.countDown();

            List<Attempt> attempts = List.of(
                    cancellation.get(15, TimeUnit.SECONDS),
                    fulfillment.get(15, TimeUnit.SECONDS));
            assertThat(attempts).filteredOn(Attempt::succeeded).hasSize(1);
            assertThat(attempts).filteredOn(attempt ->
                    attempt.errorCode() == ReservationErrorCode.INVALID_STATE_TRANSITION)
                    .hasSize(1);

            ResourceSnapshot after = snapshot(scenario);
            String terminalStatus = (String) after.reservation().get("status");
            if ("CANCELLED".equals(terminalStatus)) {
                assertThat(after.cancellationAudits()).hasSize(1);
                assertThat(after.audits()).isEmpty();
                assertThat(after.holds()).singleElement().satisfies(row ->
                        assertThat(row.get("status")).isEqualTo("RELEASED"));
                assertThat(after.capacities()).allSatisfy(row -> {
                    assertThat(row.get("occupied_people")).isEqualTo(0);
                    assertThat(row.get("occupied_teams")).isEqualTo(0);
                });
                assertThat(after.allocations()).isEqualTo(before.allocations());
                assertThat(inventoryRemaining(scenario)).isEqualTo(5);
                assertThat(restoreLedgerCount(scenario)).isOne();
                assertThat(after.idempotency()).isEmpty();
            } else {
                assertThat(terminalStatus).isEqualTo("FULFILLED");
                assertThat(after.cancellationAudits()).isEmpty();
                assertThat(after.audits()).hasSize(1);
                assertThat(after.holds()).singleElement().satisfies(row ->
                        assertThat(row.get("status")).isEqualTo("FULFILLED"));
                assertThat(after.capacities()).isEqualTo(before.capacities());
                assertThat(after.allocations()).isEqualTo(before.allocations());
                assertThat(after.inventory()).isEqualTo(before.inventory());
                assertThat(after.ledger()).isEqualTo(before.ledger());
                assertThat(after.idempotency()).singleElement().satisfies(row ->
                        assertThat(row.get("processing_status")).isEqualTo("SUCCEEDED"));
            }
        } finally {
            workersReady.countDown();
            workersReady.countDown();
            startWorkers.countDown();
            cancelIfRunning(cancellation);
            cancelIfRunning(fulfillment);
            shutdownAndAwait(executor);
        }
    }

    @ParameterizedTest
    @EnumSource(FailurePoint.class)
    void latePersistenceFailureRollsBackAllEffectsAndSameKeyCanRetry(FailurePoint point) {
        Scenario scenario = confirmedScenario(true);
        IdempotencyKey key = key(300 + point.ordinal());
        ResourceSnapshot before = snapshot(scenario);
        try {
            createFailureTrigger(point);
            assertThatThrownBy(() -> fulfillmentFacade.fulfill(
                    scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                    key, new ReservationFulfillmentRequest()))
                    .hasRootCauseInstanceOf(SQLException.class);
        } finally {
            dropFailureTrigger(point);
        }
        assertThat(snapshot(scenario)).isEqualTo(before);
        assertThat(fulfillmentFacade.fulfill(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                key, new ReservationFulfillmentRequest()).httpStatus()).isEqualTo(200);
        assertThat(snapshot(scenario).audits()).hasSize(1);
    }

    private Scenario confirmedScenario(boolean withHold) {
        return confirmedScenario(withHold, false);
    }

    private Scenario confirmedScenario(boolean withHold, boolean closedStore) {
        return transactions.execute(status -> {
            int sequence = WORKER_SEQUENCE.incrementAndGet();
            StoreOperatorAccount operator = operatorRepository.saveAndFlush(
                    StoreOperatorAccount.create(
                            "fulfill-owner-" + sequence + "@example.com",
                            "hashed-password",
                            "owner"));
            Store store = Store.create(
                    operator.getId(),
                    Long.toString(9_000_000_000L + sequence),
                    BusinessType.CAFE,
                    "Fulfillment Store " + sequence,
                    "",
                    Region.SEOUL,
                    "fixture-address",
                    "CAFE_BAKERY",
                    Set.of(),
                    true,
                    true,
                    false,
                    "Asia/Seoul",
                    LocalDateTime.of(2026, 8, 1, 9, 0),
                    "STORE_ONBOARDING_REQUIRED_TERMS_V1");
            if (closedStore) {
                store.close();
            }
            store = storeRepository.saveAndFlush(store);
            ConsumerAccount consumer = consumerRepository.saveAndFlush(
                    ConsumerAccount.createWithContact(
                            "fulfill-consumer-" + sequence + "@example.com",
                            "hashed-password",
                            "consumer",
                            String.format(Locale.ROOT, "010%08d", sequence),
                            "opaque-fulfill-contact-" + sequence));
            ReservationTimePolicyVersion policy =
                    ReservationTimePolicyVersion.createDraft(
                            store.getId(), 1L, 60, 60, 0);
            policy.activate(
                    Instant.parse("2026-08-01T00:00:00Z"),
                    "fulfillment fixture");
            ReservationTimeSnapshot time = ReservationTimeSnapshot.calculate(
                    policy,
                    LocalDateTime.of(LocalDate.of(2026, 8, 15), LocalTime.of(12, 0)),
                    ZoneId.of("Asia/Seoul"),
                    null);
            Reservation reservation = reservationRepository.saveAndFlush(
                    Reservation.confirm(
                            consumer.getId(),
                            store.getId(),
                            store.getName(),
                            time,
                            PartyComposition.of(2, 0, 0),
                            ReservationContactSnapshot.contactable(
                                    "opaque-target-" + sequence),
                            1L,
                            new ReservationCancellationPolicyVersion(1L),
                            Instant.parse("2026-08-10T00:00:00Z")));
            ReservationCapacityBucket bucket = capacityBucketRepository.saveAndFlush(
                    ReservationCapacityBucket.create(
                            store.getId(),
                            LocalDate.of(2026, 8, 15),
                            LocalTime.of(12, 0),
                            LocalTime.of(13, 0),
                            10,
                            5,
                            2,
                            1,
                            1,
                            10,
                            true,
                            1L));
            allocationRepository.saveAndFlush(
                    ReservationCapacityAllocation.allocate(
                            reservation.getId(), bucket.getId(), 2, 1L));
            Long holdId = withHold
                    ? seedConfirmedMenuHold(
                            operator.getId(), store.getId(), consumer.getId(),
                            reservation.getId(), sequence)
                    : null;
            return new Scenario(
                    operator.getId(), store.getId(), reservation.getId(), holdId);
        });
    }

    private long seedConfirmedMenuHold(
            long operatorId,
            long storeId,
            long consumerId,
            long reservationId,
            int sequence
    ) {
        Menu menu = Menu.create(
                storeId,
                menuContent(),
                operatorId,
                Instant.parse("2026-08-01T00:00:00Z"));
        menu.publish(Instant.parse("2026-08-01T00:00:00Z"));
        menu = menuRepository.saveAndFlush(menu);
        MenuInventoryBucket inventory = inventoryBucketRepository.saveAndFlush(
                MenuInventoryBucket.create(
                        menu.getId(),
                        LocalDate.of(2026, 8, 15),
                        LocalTime.of(12, 0),
                        LocalDate.of(2026, 8, 15),
                        LocalTime.of(13, 0),
                        "Asia/Seoul",
                        1L,
                        5,
                        5,
                        0,
                        0,
                        false));
        jdbcTemplate.update(
                "UPDATE menu_inventory_buckets SET online_hold_remaining = 4 "
                        + "WHERE menu_inventory_bucket_id = ?",
                inventory.getId());
        String acquireOperation = "reservation-create:fulfillment-it:" + sequence;
        jdbcTemplate.update("""
                INSERT INTO menu_inventory_ledger (
                  operation_id, source_operation_id, menu_inventory_bucket_id,
                  operation_type, pool_type, quantity_delta, quantity_before,
                  quantity_after, created_at, updated_at
                ) VALUES (?, NULL, ?, 'ACQUIRE', 'ONLINE_HOLD', -1, 5, 4,
                          CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, acquireOperation, inventory.getId());
        jdbcTemplate.update("""
                INSERT INTO menu_holds (
                  reservation_id, store_id, consumer_account_id, service_date, start_time,
                  end_date, end_time, acquire_operation_id, status, created_at, updated_at
                ) VALUES (?, ?, ?, '2026-08-15', '12:00:00', '2026-08-15', '13:00:00',
                          ?, 'CONFIRMED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, reservationId, storeId, consumerId, acquireOperation);
        long holdId = jdbcTemplate.queryForObject(
                "SELECT menu_hold_id FROM menu_holds WHERE reservation_id = ?",
                Long.class,
                reservationId);
        jdbcTemplate.update("""
                INSERT INTO menu_hold_items (
                  menu_hold_id, menu_id, menu_inventory_bucket_id, menu_policy_version,
                  menu_name_snapshot, unit_price_snapshot, inventory_policy_version,
                  quantity, created_at, updated_at
                ) VALUES (?, ?, ?, 1, 'Fulfillment Americano', 5000, 1, 1,
                          CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, holdId, menu.getId(), inventory.getId());
        return holdId;
    }

    private static MenuContent menuContent() {
        return new MenuContent(
                "Fulfillment Americano",
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

    private ResourceSnapshot snapshot(Scenario scenario) {
        return new ResourceSnapshot(
                jdbcTemplate.queryForMap(
                        "SELECT status, fulfilled_at FROM reservations "
                                + "WHERE reservation_id = ?",
                        scenario.reservationId()),
                jdbcTemplate.queryForList(
                        "SELECT menu_hold_id, status FROM menu_holds "
                                + "WHERE reservation_id = ? ORDER BY menu_hold_id",
                        scenario.reservationId()),
                jdbcTemplate.queryForList(
                        "SELECT reservation_capacity_bucket_id, occupied_people, "
                                + "occupied_teams FROM reservation_capacity_buckets "
                                + "WHERE store_id = ? ORDER BY reservation_capacity_bucket_id",
                        scenario.storeId()),
                jdbcTemplate.queryForList(
                        "SELECT reservation_capacity_allocation_id, "
                                + "reservation_capacity_bucket_id, occupied_people, "
                                + "occupied_teams, capacity_policy_version "
                                + "FROM reservation_capacity_allocations "
                                + "WHERE reservation_id = ? ORDER BY reservation_capacity_bucket_id",
                        scenario.reservationId()),
                jdbcTemplate.queryForList(
                        "SELECT menu_inventory_bucket_id, online_hold_remaining, "
                                + "shared_remaining, lock_version FROM menu_inventory_buckets "
                                + "ORDER BY menu_inventory_bucket_id"),
                jdbcTemplate.queryForList(
                        "SELECT menu_inventory_ledger_id, operation_id, operation_type, "
                                + "quantity_delta, quantity_before, quantity_after "
                                + "FROM menu_inventory_ledger ORDER BY menu_inventory_ledger_id"),
                jdbcTemplate.queryForList(
                        "SELECT actor_type, actor_id, requested_at, occurred_at, "
                                + "before_status, after_status, reservation_time_policy_version, "
                                + "capacity_policy_version, command_id "
                                + "FROM reservation_fulfillment_audits WHERE reservation_id = ?",
                        scenario.reservationId()),
                jdbcTemplate.queryForList(
                        "SELECT actor_type, actor_id, cancellation_reason, requested_at, "
                                + "occurred_at, before_status, after_status, "
                                + "cancellation_policy_version, capacity_policy_version, "
                                + "command_id FROM reservation_cancellation_audits "
                                + "WHERE reservation_id = ?",
                        scenario.reservationId()),
                jdbcTemplate.queryForList(
                        "SELECT idempotency_key, processing_status, result_http_status, "
                                + "result_response_code, result_payload "
                                + "FROM idempotency_commands "
                                + "WHERE principal_namespace = 'store-operator' "
                                + "AND principal_id = ? "
                                + "AND command_type = 'RESERVATION_FULFILL' "
                                + "ORDER BY idempotency_command_id",
                        scenario.operatorId()));
    }

    private Attempt fulfillAfterStart(
            Scenario scenario,
            IdempotencyKey key,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        awaitLatch(start, "fulfillment worker start");
        try {
            return Attempt.success(fulfillmentFacade.fulfill(
                    scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                    key, new ReservationFulfillmentRequest()));
        } catch (ServiceException exception) {
            return Attempt.failure(exception);
        }
    }

    private Attempt cancelAfterStart(
            Scenario scenario,
            IdempotencyKey key,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        awaitLatch(start, "cancellation worker start");
        try {
            return Attempt.success(cancellationFacade.cancelByStoreOperator(
                    scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                    key, new StoreCancellationRequest("terminal race")));
        } catch (ServiceException exception) {
            return Attempt.failure(exception);
        }
    }

    private static String fulfillmentFingerprint(long storeId, long reservationId) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(canonical, "method", "POST");
        appendCanonical(
                canonical,
                "route",
                "/api/v1/store-operator/stores/{storeId}/reservations/"
                        + "{reservationId}/fulfillments");
        appendCanonical(canonical, "storeId", String.valueOf(storeId));
        appendCanonical(canonical, "reservationId", String.valueOf(reservationId));
        return RequestFingerprint.of(canonical.toString());
    }

    private static void appendCanonical(
            StringBuilder target,
            String field,
            String value
    ) {
        target.append(field)
                .append('=')
                .append(value.length())
                .append(':')
                .append(value)
                .append('|');
    }

    private static IdempotencyKey key(int suffix) {
        return IdempotencyKey.parse(String.format(
                Locale.ROOT,
                "550e8400-e29b-41d4-a716-%012d",
                suffix));
    }

    private int inventoryRemaining(Scenario scenario) {
        return jdbcTemplate.queryForObject("""
                SELECT inventory.online_hold_remaining
                  FROM menu_inventory_buckets inventory
                  JOIN menu_hold_items item
                    ON item.menu_inventory_bucket_id = inventory.menu_inventory_bucket_id
                  JOIN menu_holds hold ON hold.menu_hold_id = item.menu_hold_id
                 WHERE hold.reservation_id = ?
                """, Integer.class, scenario.reservationId());
    }

    private int restoreLedgerCount(Scenario scenario) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM menu_inventory_ledger ledger
                  JOIN menu_hold_items item
                    ON item.menu_inventory_bucket_id = ledger.menu_inventory_bucket_id
                  JOIN menu_holds hold ON hold.menu_hold_id = item.menu_hold_id
                 WHERE hold.reservation_id = ? AND ledger.operation_type = 'RESTORE'
                """, Integer.class, scenario.reservationId());
    }

    private void awaitBlockingWaits(
            long holderConnectionId,
            String table,
            String index,
            int expected
    ) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM performance_schema.data_lock_waits wait_edge
                     JOIN performance_schema.data_locks blocking_lock
                       ON blocking_lock.ENGINE = wait_edge.ENGINE
                      AND blocking_lock.ENGINE_LOCK_ID = wait_edge.BLOCKING_ENGINE_LOCK_ID
                     JOIN information_schema.INNODB_TRX blocking_transaction
                       ON blocking_transaction.TRX_ID = blocking_lock.ENGINE_TRANSACTION_ID
                     WHERE blocking_transaction.TRX_MYSQL_THREAD_ID = ?
                       AND blocking_lock.OBJECT_SCHEMA = DATABASE()
                       AND blocking_lock.OBJECT_NAME = ? AND blocking_lock.INDEX_NAME = ?
                     """)) {
            statement.setLong(1, holderConnectionId);
            statement.setString(2, table);
            statement.setString(3, index);
            while (System.nanoTime() < deadline) {
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    if (result.getInt(1) >= expected) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("cannot observe lock waits", exception);
        }
        throw new AssertionError("expected lock waits on " + table + "." + index);
    }

    private static void assertFutureBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(250, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    private static void awaitLatch(CountDownLatch latch, String name) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(name + " timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name + " interrupted", exception);
        }
    }

    private static void cancelIfRunning(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private static void shutdownAndAwait(ExecutorService executor) throws Exception {
        executor.shutdownNow();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            throw new AssertionError("fulfillment workers did not terminate");
        }
    }

    private static ThreadFactory workerFactory() {
        return task -> {
            Thread worker = new Thread(
                    task,
                    "reservation-fulfillment-it-worker-"
                            + WORKER_SEQUENCE.incrementAndGet());
            worker.setDaemon(true);
            return worker;
        };
    }

    private void createFailureTrigger(FailurePoint point) {
        String ddl = switch (point) {
            case MENU_HOLD_UPDATE -> "CREATE TRIGGER trg_fulfill_hold_failure "
                    + "BEFORE UPDATE ON menu_holds FOR EACH ROW SIGNAL SQLSTATE '45000' "
                    + "SET MESSAGE_TEXT = 'fulfillment hold failure'";
            case AUDIT_INSERT -> "CREATE TRIGGER trg_fulfill_audit_failure "
                    + "BEFORE INSERT ON reservation_fulfillment_audits FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' "
                    + "SET MESSAGE_TEXT = 'fulfillment audit failure'";
            case IDEMPOTENCY_SUCCEEDED_UPDATE ->
                    "CREATE TRIGGER trg_fulfill_idempotency_failure "
                            + "BEFORE UPDATE ON idempotency_commands FOR EACH ROW "
                            + "SIGNAL SQLSTATE '45000' "
                            + "SET MESSAGE_TEXT = 'fulfillment idempotency failure'";
        };
        jdbcTemplate.execute(ddl);
    }

    private void dropFailureTrigger(FailurePoint point) {
        String name = switch (point) {
            case MENU_HOLD_UPDATE -> "trg_fulfill_hold_failure";
            case AUDIT_INSERT -> "trg_fulfill_audit_failure";
            case IDEMPOTENCY_SUCCEEDED_UPDATE -> "trg_fulfill_idempotency_failure";
        };
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + name);
    }

    private enum FailurePoint {
        MENU_HOLD_UPDATE,
        AUDIT_INSERT,
        IDEMPOTENCY_SUCCEEDED_UPDATE
    }

    private record Scenario(
            long operatorId,
            long storeId,
            long reservationId,
            Long menuHoldId
    ) {
    }

    private record ResourceSnapshot(
            Map<String, Object> reservation,
            List<Map<String, Object>> holds,
            List<Map<String, Object>> capacities,
            List<Map<String, Object>> allocations,
            List<Map<String, Object>> inventory,
            List<Map<String, Object>> ledger,
            List<Map<String, Object>> audits,
            List<Map<String, Object>> cancellationAudits,
            List<Map<String, Object>> idempotency
    ) {
    }

    private record Attempt(Object result, ErrorCode errorCode) {
        private boolean succeeded() {
            return result != null;
        }

        private static Attempt success(Object result) {
            return new Attempt(result, null);
        }

        private static Attempt failure(ServiceException failure) {
            return new Attempt(null, failure.getErrorCode());
        }
    }
}
