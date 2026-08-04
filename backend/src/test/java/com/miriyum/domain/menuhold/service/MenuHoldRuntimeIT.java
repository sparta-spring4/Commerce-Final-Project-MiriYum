package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.menuhold.dto.MenuHoldCreateCommand;
import com.miriyum.domain.menuhold.dto.MenuSelection;
import com.miriyum.domain.menuhold.entity.MenuHoldStatus;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.domain.menuhold.repository.MenuHoldRepository;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.menu.dto.MenuTransactionEligibility;
import com.miriyum.domain.store.menu.entity.Menu;
import com.miriyum.domain.store.menu.model.AllergenDisclosure;
import com.miriyum.domain.store.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.store.menu.model.AllergenIngredientCode;
import com.miriyum.domain.store.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.store.menu.model.MenuContent;
import com.miriyum.domain.store.menu.repository.MenuRepository;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalResult;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalStatus;
import com.miriyum.domain.store.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
})
class MenuHoldRuntimeIT {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MenuHoldServiceRuntime service;
    @Autowired MenuHoldRepository holdRepository;
    @Autowired MenuInventoryBucketRepository bucketRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired ConsumerAccountRepository consumerRepository;
    @Autowired StoreOperatorAccountRepository operatorRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactions;
    @MockitoSpyBean StoreService storeService;
    @MockitoSpyBean StoreServiceIntervalValidationService intervalService;

    private long consumerId;
    private long storeId;
    private long menuId;

    @BeforeEach
    void setUp() {
        int sequence = SEQUENCE.incrementAndGet();
        transactions.executeWithoutResult(status -> {
            consumerId = consumerRepository.saveAndFlush(ConsumerAccount.create(
                    "hold-consumer-" + sequence + "@example.com", "hashed", "consumer")).getId();
            long operatorId = operatorRepository.saveAndFlush(StoreOperatorAccount.create(
                    "hold-owner-" + sequence + "@example.com", "hashed", "owner")).getId();
            Store store = storeRepository.saveAndFlush(Store.create(
                    operatorId, String.format("%010d", 500000 + sequence), BusinessType.CAFE,
                    "store", "", Region.SEOUL, "address", "CAFE_BAKERY", Set.of(),
                    true, true, true, "Asia/Seoul", LocalDateTime.of(2026, 8, 1, 9, 0),
                    "STORE_ONBOARDING_REQUIRED_TERMS_V1"));
            storeId = store.getId();
            menuId = menuRepository.saveAndFlush(Menu.create(
                    storeId, menuContent(), operatorId, Instant.parse("2026-08-01T00:00:00Z"))).getId();
        });
        willReturn(new MenuTransactionEligibility(storeId, menuId, 1, true, false))
                .given(storeService).requireMenuTransactionEligibility(storeId, menuId);
        willAnswer(invocation -> invocation.<List<com.miriyum.domain.store.schedule.dto.StoreServiceIntervalRequest>>getArgument(0)
                .stream().map(request -> new StoreServiceIntervalResult(
                        request.storeId(), request.startAt(), request.serviceEndAt(),
                        StoreServiceIntervalStatus.ACCEPTING)).toList())
                .given(intervalService).validateServiceIntervals(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("메뉴 홀드 확정과 중앙 재고 차감이 같은 MySQL 트랜잭션에 저장된다")
    void persistsConfirmedHoldAndDecrementsInventoryInOneTransaction() {
        MenuInventoryBucket bucket = transactions.execute(status -> bucketRepository.saveAndFlush(bucket(2)));
        Reservation reservation = transactions.execute(status -> reservationRepository.saveAndFlush(reservation()));

        transactions.executeWithoutResult(status -> service.create(command(reservation.getId(), 2, "operation-success")));

        assertThat(holdRepository.existsByReservationId(reservation.getId())).isTrue();
        assertThat(holdRepository.findAll().stream()
                .filter(hold -> hold.getReservationId() == reservation.getId()).toList())
                .singleElement().satisfies(hold -> {
            assertThat(hold.getReservationId()).isEqualTo(reservation.getId());
            assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.CONFIRMED);
        });
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow().getOnlineHoldRemaining()).isZero();
    }

    @Test
    @DisplayName("재고 부족이면 예약·홀드·수량 변경이 모두 롤백된다")
    void insufficientInventoryRollsBackReservationHoldAndQuantity() {
        MenuInventoryBucket bucket = transactions.execute(status -> bucketRepository.saveAndFlush(bucket(1)));
        long reservationsBefore = reservationRepository.count();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            Reservation reservation = reservationRepository.saveAndFlush(reservation());
            service.create(command(reservation.getId(), 2, "operation-rollback"));
        })).isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(MenuHoldErrorCode.INSUFFICIENT_QUANTITY);

        assertThat(reservationRepository.count()).isEqualTo(reservationsBefore);
        assertThat(holdRepository.existsByAcquireOperationId("operation-rollback")).isFalse();
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow().getOnlineHoldRemaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 예약과 같은 operationId 재사용은 추가 차감 없이 거부된다")
    void rejectsDuplicateReservationAndReusedOperationWithoutAdditionalDecrement() {
        MenuInventoryBucket bucket = transactions.execute(status -> bucketRepository.saveAndFlush(bucket(3)));
        Reservation first = transactions.execute(status -> reservationRepository.saveAndFlush(reservation()));
        Reservation second = transactions.execute(status -> reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status -> service.create(command(first.getId(), 1, "case-sensitive-op")));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                service.create(command(first.getId(), 1, "different-op"))))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                service.create(command(second.getId(), 1, "case-sensitive-op"))))
                .isInstanceOf(ServiceException.class);

        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow().getOnlineHoldRemaining()).isEqualTo(2);
    }

    @Test
    @DisplayName("operationId 유일성은 MySQL에서 대소문자를 구분한다")
    void operationIdsAreCaseSensitive() {
        MenuInventoryBucket bucket = transactions.execute(status -> bucketRepository.saveAndFlush(bucket(2)));
        Reservation first = transactions.execute(status -> reservationRepository.saveAndFlush(reservation()));
        Reservation second = transactions.execute(status -> reservationRepository.saveAndFlush(reservation()));

        transactions.executeWithoutResult(status -> service.create(command(first.getId(), 1, "Case-Operation")));
        transactions.executeWithoutResult(status -> service.create(command(second.getId(), 1, "case-operation")));

        assertThat(holdRepository.existsByAcquireOperationId("Case-Operation")).isTrue();
        assertThat(holdRepository.existsByAcquireOperationId("case-operation")).isTrue();
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow().getOnlineHoldRemaining()).isZero();
    }

    @Test
    @DisplayName("V21의 FK·CHECK·유일 인덱스가 실제 MySQL에 정확히 생성된다")
    void mysqlSchemaDefinesExactConstraintsAndIndexOrder() {
        assertThat(constraintNames("menu_holds", "FOREIGN KEY"))
                .containsExactlyInAnyOrder(
                        "fk_menu_holds_reservation",
                        "fk_menu_holds_store",
                        "fk_menu_holds_consumer");
        assertThat(constraintNames("menu_hold_items", "FOREIGN KEY"))
                .containsExactlyInAnyOrder(
                        "fk_menu_hold_items_hold",
                        "fk_menu_hold_items_menu",
                        "fk_menu_hold_items_bucket");
        assertThat(constraintNames("menu_holds", "CHECK"))
                .containsExactlyInAnyOrder(
                        "ck_menu_holds_service_interval",
                        "ck_menu_holds_status");
        assertThat(constraintNames("menu_hold_items", "CHECK"))
                .containsExactlyInAnyOrder(
                        "ck_menu_hold_items_versions",
                        "ck_menu_hold_items_quantity");

        assertThat(indexColumns("menu_holds", "uk_menu_holds_reservation"))
                .isEqualTo("reservation_id");
        assertThat(indexColumns("menu_holds", "uk_menu_holds_acquire_operation"))
                .isEqualTo("acquire_operation_id");
        assertThat(indexColumns("menu_hold_items", "uk_menu_hold_items_hold_bucket"))
                .isEqualTo("menu_hold_id,menu_inventory_bucket_id");
        assertThat(indexColumns("menu_hold_items", "idx_menu_hold_items_bucket"))
                .isEqualTo("menu_inventory_bucket_id,menu_hold_item_id");
    }

    @Test
    @DisplayName("메뉴 홀드 항목의 0 수량은 실제 MySQL CHECK 제약으로 거부된다")
    void mysqlRejectsInvalidMenuHoldItemQuantity() {
        transactions.execute(status -> bucketRepository.saveAndFlush(bucket(1)));
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status ->
                service.create(command(reservation.getId(), 1, "check-quantity")));
        Long itemId = jdbcTemplate.queryForObject(
                "SELECT menu_hold_item_id FROM menu_hold_items WHERE menu_hold_id = "
                        + "(SELECT menu_hold_id FROM menu_holds WHERE reservation_id = ?)",
                Long.class, reservation.getId());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE menu_hold_items SET quantity = 0 WHERE menu_hold_item_id = ?", itemId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_menu_hold_items_quantity");
    }

    @Test
    @DisplayName("동일 예약 동시 생성은 한 홀드만 확정하고 추가 차감하지 않는다")
    void concurrentDuplicateReservationCreatesOnlyOneHold() throws Exception {
        MenuInventoryBucket bucket = transactions.execute(status -> bucketRepository.saveAndFlush(bucket(2)));
        Reservation reservation = transactions.execute(status -> reservationRepository.saveAndFlush(reservation()));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> createConcurrently(
                    reservation.getId(), "duplicate-reservation-1", ready, start));
            Future<Object> second = executor.submit(() -> createConcurrently(
                    reservation.getId(), "duplicate-reservation-2", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            MenuHoldStatus.CONFIRMED,
                            MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        }
        assertThat(holdRepository.findAll().stream()
                .filter(hold -> hold.getReservationId() == reservation.getId())).hasSize(1);
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow().getOnlineHoldRemaining())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("동일 operationId 동시 생성은 한 홀드만 확정하고 추가 차감하지 않는다")
    void concurrentDuplicateOperationCreatesOnlyOneHold() throws Exception {
        MenuInventoryBucket bucket = transactions.execute(status -> bucketRepository.saveAndFlush(bucket(2)));
        Reservation firstReservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        Reservation secondReservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> createConcurrently(
                    firstReservation.getId(), "duplicate-operation", ready, start));
            Future<Object> second = executor.submit(() -> createConcurrently(
                    secondReservation.getId(), "duplicate-operation", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            MenuHoldStatus.CONFIRMED,
                            MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        }
        assertThat(holdRepository.findAll().stream()
                .filter(hold -> hold.getAcquireOperationId().equals("duplicate-operation")))
                .hasSize(1);
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow().getOnlineHoldRemaining())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("마지막 온라인 수량 경합에서 한 예약만 확정된다")
    void concurrentCreatesCannotOversellTheLastQuantity() throws Exception {
        MenuInventoryBucket bucket = transactions.execute(status -> bucketRepository.saveAndFlush(bucket(1)));
        Reservation first = transactions.execute(status -> reservationRepository.saveAndFlush(reservation()));
        Reservation second = transactions.execute(status -> reservationRepository.saveAndFlush(reservation()));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> firstResult = executor.submit(() -> createConcurrently(
                    first.getId(), "concurrent-1", ready, start));
            Future<Object> secondResult = executor.submit(() -> createConcurrently(
                    second.getId(), "concurrent-2", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(firstResult.get(20, TimeUnit.SECONDS),
                    secondResult.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(MenuHoldStatus.CONFIRMED,
                            MenuHoldErrorCode.INSUFFICIENT_QUANTITY);
        }
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow().getOnlineHoldRemaining()).isZero();
    }

    private Object createConcurrently(long reservationId, String operationId,
            CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                return AssertionError.class;
            }
            transactions.executeWithoutResult(status -> service.create(command(reservationId, 1, operationId)));
            return MenuHoldStatus.CONFIRMED;
        } catch (ServiceException exception) {
            return exception.getErrorCode();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return InterruptedException.class;
        }
    }

    private List<String> constraintNames(String tableName, String constraintType) {
        return jdbcTemplate.queryForList("""
                SELECT constraint_name
                  FROM information_schema.table_constraints
                 WHERE table_schema = DATABASE()
                   AND table_name = ?
                   AND constraint_type = ?
                 ORDER BY constraint_name
                """, String.class, tableName, constraintType);
    }

    private String indexColumns(String tableName, String indexName) {
        return jdbcTemplate.queryForObject("""
                SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                  FROM information_schema.statistics
                 WHERE table_schema = DATABASE()
                   AND table_name = ?
                   AND index_name = ?
                """, String.class, tableName, indexName);
    }

    private Reservation reservation() {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                storeId, 1L, 30, 60, 0);
        policy.activate(Instant.parse("2026-08-01T00:00:00Z"), "메뉴 홀드 통합 테스트");
        ReservationTimeSnapshot timeSnapshot = ReservationTimeSnapshot.calculate(
                policy, LocalDateTime.of(2026, 8, 10, 12, 0), ZoneId.of("Asia/Seoul"), null);
        return Reservation.confirm(consumerId, storeId, "store", timeSnapshot,
                PartyComposition.of(2, 0, 0),
                ReservationContactSnapshot.contactable("consumer:" + consumerId),
                1L, Instant.parse("2026-08-01T00:00:00Z"));
    }

    private MenuHoldCreateCommand command(long reservationId, int quantity, String operationId) {
        return new MenuHoldCreateCommand(String.valueOf(reservationId), String.valueOf(storeId),
                String.valueOf(consumerId), LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                Instant.parse("2026-08-10T03:00:00Z"),
                Instant.parse("2026-08-10T04:00:00Z"), operationId,
                List.of(new MenuSelection(String.valueOf(menuId), quantity)));
    }

    private MenuInventoryBucket bucket(int online) {
        return MenuInventoryBucket.create(menuId, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0), "Asia/Seoul", 1L,
                online, online, 0, 0, false);
    }

    private static MenuContent menuContent() {
        return new MenuContent("Americano", "", 5000, false, "BEVERAGE", List.of(),
                List.of(), true, true, DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosure(AllergenIngredientCode.MILK,
                        AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.NOT_APPLICABLE, List.of(), false);
    }
}
