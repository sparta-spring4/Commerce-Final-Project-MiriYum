package com.miriyum.domain.pickup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.menuhold.dto.MenuHoldCommandResult;
import com.miriyum.domain.menuhold.dto.MenuHoldCreateCommand;
import com.miriyum.domain.menuhold.dto.MenuSelection;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.service.MenuHoldService;
import com.miriyum.domain.pickup.dto.request.PickupMenuSelectionRequest;
import com.miriyum.domain.pickup.dto.request.PickupReservationCreateRequest;
import com.miriyum.domain.pickup.dto.request.PickupCancellationRequest;
import com.miriyum.domain.pickup.exception.PickupErrorCode;
import com.miriyum.domain.pickup.repository.PickupReservationRepository;
import com.miriyum.domain.store.dto.contract.StorePickupTransactionEligibility;
import com.miriyum.domain.menu.service.MenuTransactionFacade;
import com.miriyum.domain.store.service.StoreTransactionEligibilityService;
import com.miriyum.domain.menu.dto.contract.MenuTransactionEligibility;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalRequest;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalResult;
import com.miriyum.domain.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.global.exception.ErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
@Import(PickupCreationIntegrationTest.FixedClockConfig.class)
class PickupCreationIntegrationTest {

    private static final long CONSUMER_1 = 10_001L;
    private static final long CONSUMER_2 = 10_002L;
    private static final long OPERATOR_ID = 20_001L;
    private static final long STORE_ID = 30_001L;
    private static final long MENU_ID = 40_001L;
    private static final long BUCKET_ID = 50_001L;
    private static final long RESERVATION_ID = 60_001L;
    private static final LocalDate PICKUP_DATE = LocalDate.of(2026, 8, 10);

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired PickupReservationService service;
    @Autowired PickupStoreManagementService managementService;
    @Autowired MenuHoldService menuHoldService;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean StoreTransactionEligibilityService storeEligibilityService;
    @MockitoBean MenuTransactionFacade menuTransactionFacade;
    @MockitoBean StoreServiceIntervalValidationService intervalValidationService;
    @MockitoSpyBean PickupReservationRepository pickupReservationRepository;

    @BeforeEach
    void resetAndSeed() {
        jdbcTemplate.execute("DELETE FROM notification_task_transition_audits");
        jdbcTemplate.execute("DELETE FROM notification_channel_attempts");
        jdbcTemplate.execute("DELETE FROM notification_tasks");
        jdbcTemplate.execute("DELETE FROM pickup_reservation_items");
        jdbcTemplate.execute("DELETE FROM pickup_reservations");
        jdbcTemplate.execute("DELETE FROM menu_hold_items");
        jdbcTemplate.execute("DELETE FROM menu_holds");
        jdbcTemplate.execute("DELETE FROM reservations");
        jdbcTemplate.execute("DELETE FROM idempotency_commands");
        jdbcTemplate.execute("DELETE FROM menu_inventory_ledger");
        jdbcTemplate.execute("DELETE FROM menu_inventory_buckets");
        jdbcTemplate.execute("DELETE FROM menus");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");
        insertConsumer(CONSUMER_1, "pickup-one@example.com");
        insertConsumer(CONSUMER_2, "pickup-two@example.com");
        insertOperatorAndStore();
        insertMenu();
        stubStoreContracts();
    }

    @Test
    void sameIdempotencyKeyReplaysWithoutSecondPickupOrInventoryAcquire() {
        insertBucket(5);
        IdempotencyKey key = key();

        PickupCommandResult first = service.create(CONSUMER_1, key, request(2));
        PickupCommandResult replay = service.create(CONSUMER_1, key, request(2));

        assertThat(replay.data()).isEqualTo(first.data());
        assertThat(count("pickup_reservations")).isEqualTo(1);
        assertThat(count("menu_inventory_ledger")).isEqualTo(1);
        assertThat(onlineRemaining()).isEqualTo(3);
        assertThat(count("idempotency_commands")).isEqualTo(1);
    }

    @Test
    void persistenceFailureRollsBackInventoryLedgerAndIdempotencyClaim() {
        insertBucket(5);
        DataIntegrityViolationException forcedFailure =
                new DataIntegrityViolationException("forced pickup persistence failure");
        willThrow(forcedFailure)
                .given(pickupReservationRepository).saveAndFlush(any());

        assertThatThrownBy(() -> service.create(CONSUMER_1, key(), request(2)))
                .isSameAs(forcedFailure);

        then(pickupReservationRepository).should().saveAndFlush(any());
        assertThat(count("pickup_reservations")).isZero();
        assertThat(count("menu_inventory_ledger")).isZero();
        assertThat(onlineRemaining()).isEqualTo(5);
        assertThat(count("idempotency_commands")).isZero();
    }

    @Test
    void concurrentPickupsCannotConsumeTheSameLastOnlineQuantity() throws Exception {
        insertBucket(1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> createConcurrently(
                    CONSUMER_1, ready, start));
            Future<Object> second = executor.submit(() -> createConcurrently(
                    CONSUMER_2, ready, start));
            ready.await();
            start.countDown();

            List<Object> results = List.of(first.get(), second.get());
            assertThat(results).filteredOn(PickupCommandResult.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(result -> result == PickupErrorCode.INSUFFICIENT_QUANTITY)
                    .hasSize(1);
        }
        assertThat(count("pickup_reservations")).isEqualTo(1);
        assertThat(count("menu_inventory_ledger")).isEqualTo(1);
        assertThat(onlineRemaining()).isZero();
    }

    @Test
    void menuHoldAndPickupCannotConsumeTheSameLastOnlineQuantity() throws Exception {
        insertBucket(1);
        insertReservation();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> pickup = executor.submit(() -> createConcurrently(
                    CONSUMER_2, ready, start));
            Future<Object> menuHold = executor.submit(() -> createMenuHoldConcurrently(
                    ready, start));
            ready.await();
            start.countDown();

            List<Object> results = List.of(pickup.get(), menuHold.get());
            assertThat(results).filteredOn(result ->
                    result instanceof PickupCommandResult
                            || result instanceof MenuHoldCommandResult)
                    .hasSize(1);
            assertThat(results).filteredOn(result ->
                    result == PickupErrorCode.INSUFFICIENT_QUANTITY
                            || result == MenuHoldErrorCode.INSUFFICIENT_QUANTITY)
                    .hasSize(1);
        }
        assertThat(count("pickup_reservations") + count("menu_holds")).isEqualTo(1);
        assertThat(count("menu_inventory_ledger")).isEqualTo(1);
        assertThat(onlineRemaining()).isZero();
    }

    @Test
    void consumerCancellationRestoresOriginalPoolsExactlyOnceOnReplay() {
        insertBucket(5);
        PickupCommandResult created = service.create(CONSUMER_1, key(), request(2));
        long pickupId = Long.parseLong(created.data().pickupReservationId());
        IdempotencyKey cancellationKey = key();

        PickupCommandResult cancelled = service.cancelByConsumer(
                CONSUMER_1, pickupId, cancellationKey,
                new PickupCancellationRequest("일정 변경"),
                Instant.parse("2026-08-09T01:00:00Z"));
        PickupCommandResult replay = service.cancelByConsumer(
                CONSUMER_1, pickupId, cancellationKey,
                new PickupCancellationRequest("일정 변경"),
                Instant.parse("2026-08-09T01:00:00Z"));

        assertThat(cancelled.data().status().name()).isEqualTo("CANCELLED");
        assertThat(replay.data()).isEqualTo(cancelled.data());
        assertThat(onlineRemaining()).isEqualTo(5);
        assertThat(count("menu_inventory_ledger")).isEqualTo(2);
        assertThat(count("idempotency_commands")).isEqualTo(2);
    }

    @Test
    void fulfillmentChangesStateWithoutRestoringInventory() {
        insertBucket(5);
        PickupCommandResult created = service.create(CONSUMER_1, key(), request(2));
        long pickupId = Long.parseLong(created.data().pickupReservationId());

        PickupCommandResult fulfilled = managementService.fulfill(
                OPERATOR_ID, STORE_ID, pickupId, key());

        assertThat(fulfilled.data().status().name()).isEqualTo("PICKED_UP");
        assertThat(onlineRemaining()).isEqualTo(3);
        assertThat(count("menu_inventory_ledger")).isEqualTo(1);
    }

    private Object createConcurrently(
            long consumerId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return service.create(consumerId, key(), request(1));
        } catch (ServiceException exception) {
            return exception.getErrorCode();
        }
    }

    private Object createMenuHoldConcurrently(
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return transactionTemplate.execute(status -> menuHoldService.create(
                    new MenuHoldCreateCommand(
                            RESERVATION_ID, STORE_ID, CONSUMER_1,
                            PICKUP_DATE, LocalTime.NOON,
                            PICKUP_DATE, LocalTime.of(13, 0),
                            Instant.parse("2026-08-10T03:00:00Z"),
                            Instant.parse("2026-08-10T04:00:00Z"),
                            "reservation-menu-hold-race",
                            List.of(new MenuSelection(MENU_ID, 1)))));
        } catch (ServiceException exception) {
            return exception.getErrorCode();
        }
    }

    private void stubStoreContracts() {
        given(storeEligibilityService.requirePickupTransactionEligibility(STORE_ID))
                .willReturn(new StorePickupTransactionEligibility(
                        STORE_ID, "픽업 매장", "Asia/Seoul"));
        given(menuTransactionFacade.requireTransactionEligibility(STORE_ID, MENU_ID))
                .willReturn(new MenuTransactionEligibility(
                        STORE_ID, MENU_ID, 2, "바질 파스타", 12_000, true, true));
        given(intervalValidationService.validateServiceIntervals(any()))
                .willAnswer(invocation -> {
                    List<StoreServiceIntervalRequest> requests = invocation.getArgument(0);
                    return requests.stream()
                            .map(request -> StoreServiceIntervalResult.of(request, true))
                            .toList();
                });
    }

    private static PickupReservationCreateRequest request(int quantity) {
        return new PickupReservationCreateRequest(
                Long.toString(STORE_ID), PICKUP_DATE, LocalTime.NOON,
                List.of(new PickupMenuSelectionRequest(Long.toString(MENU_ID), quantity)));
    }

    private static IdempotencyKey key() {
        return IdempotencyKey.parse(UUID.randomUUID().toString());
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private int onlineRemaining() {
        return jdbcTemplate.queryForObject(
                "SELECT online_hold_remaining FROM menu_inventory_buckets "
                        + "WHERE menu_inventory_bucket_id = ?",
                Integer.class, BUCKET_ID);
    }

    private void insertConsumer(long id, String email) {
        jdbcTemplate.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name, status,
                    created_at, updated_at
                ) VALUES (?, ?, 'hash', '픽업 사용자', 'ACTIVE', NOW(6), NOW(6))
                """, id, email);
    }

    private void insertOperatorAndStore() {
        jdbcTemplate.update("""
                INSERT INTO store_operator_accounts (
                    store_operator_account_id, email, password_hash, display_name, status,
                    created_at, updated_at
                ) VALUES (?, 'operator@example.com', 'hash', '운영자', 'ACTIVE',
                    NOW(6), NOW(6))
                """, OPERATOR_ID);
        jdbcTemplate.update("""
                INSERT INTO stores (
                    store_id, store_operator_account_id, business_registration_number,
                    business_type, name, description, region, address, store_category_code,
                    verification_status, operation_status, reservation_enabled,
                    menu_hold_enabled, pickup_enabled, time_zone_id,
                    applicant_self_attested_at, required_terms_agreed_at,
                    required_terms_version, created_at, updated_at
                ) VALUES (?, ?, '1234567890', 'OTHER', '픽업 매장', '설명', 'SEOUL',
                    '서울', 'ETC', 'APPROVED', 'OPEN', TRUE, TRUE, TRUE, 'Asia/Seoul',
                    NOW(6), NOW(6), 'STORE_ONBOARDING_REQUIRED_TERMS_V1', NOW(6), NOW(6))
                """, STORE_ID, OPERATOR_ID);
    }

    private void insertMenu() {
        jdbcTemplate.update("""
                INSERT INTO menus (
                    menu_id, store_id, next_version_number, published_version_number,
                    visibility, selling_status, retired, lock_version, created_at, updated_at
                ) VALUES (?, ?, 3, 2, 'VISIBLE', 'SELLING', FALSE, 0, NOW(6), NOW(6))
                """, MENU_ID, STORE_ID);
    }

    private void insertBucket(int quantity) {
        jdbcTemplate.update("""
                INSERT INTO menu_inventory_buckets (
                    menu_inventory_bucket_id, menu_id, service_date, start_time,
                    end_date, end_time, time_zone_id, inventory_policy_version,
                    total_supply, online_hold_capacity, online_hold_remaining,
                    onsite_capacity, onsite_remaining, shared_capacity, shared_remaining,
                    shared_online_allowed, availability_status, lock_version,
                    created_at, updated_at
                ) VALUES (?, ?, '2026-08-10', '12:00:00', '2026-08-10', '13:00:00',
                    'Asia/Seoul', 3, ?, ?, ?, 0, 0, 0, 0, FALSE, 'AVAILABLE', 0,
                    NOW(6), NOW(6))
                """, BUCKET_ID, MENU_ID, quantity, quantity, quantity);
    }

    private void insertReservation() {
        jdbcTemplate.update("""
                INSERT INTO reservations (
                    reservation_id, consumer_account_id, store_id, store_name_snapshot,
                    service_date, start_time, end_time, adult_count, child_count,
                    infant_count, notification_target_reference,
                    contact_available_at_confirmation, capacity_policy_version,
                    reservation_policy_version, status, created_at
                ) VALUES (?, ?, ?, 'reservation store', '2026-08-10', '12:00:00',
                    '13:00:00', 1, 0, 0, 'consumer:10001', TRUE, 1, 1,
                    'CONFIRMED', NOW(6))
                """, RESERVATION_ID, CONSUMER_1, STORE_ID);
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock pickupIntegrationClock() {
            return Clock.fixed(Instant.parse("2026-08-09T01:00:00Z"), ZoneOffset.UTC);
        }
    }
}
