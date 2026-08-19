package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.doThrow;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.menuhold.dto.MenuHoldCommandResult;
import com.miriyum.domain.menuhold.dto.MenuHoldCreateCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldForfeitCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldFulfillCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldItemResult;
import com.miriyum.domain.menuhold.dto.MenuHoldReleaseCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldTerminationPresence;
import com.miriyum.domain.menuhold.dto.MenuSelection;
import com.miriyum.domain.menuhold.entity.MenuHold;
import com.miriyum.domain.menuhold.entity.MenuHoldStatus;
import com.miriyum.domain.menuhold.entity.MenuHoldTransitionAudit;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.domain.menuhold.repository.MenuHoldRepository;
import com.miriyum.domain.menuhold.repository.MenuHoldTransitionAuditRepository;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.menu.dto.contract.MenuTransactionEligibility;
import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.model.AllergenDisclosure;
import com.miriyum.domain.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.menu.model.AllergenIngredientCode;
import com.miriyum.domain.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.menu.model.MenuContent;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.menu.service.MenuTransactionFacade;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalResult;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalStatus;
import com.miriyum.domain.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.EntityManagerFactory;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.reservation.hold-expiration.enabled=false"
})
class MenuHoldRuntimeIT {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final long LOCK_WAIT_OBSERVATION_TIMEOUT_MILLIS = 5_000L;
    private static final long LOCK_WAIT_OBSERVATION_POLL_MILLIS = 25L;
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MenuHoldServiceRuntime service;
    @Autowired MenuHoldSnapshotQueryService snapshotQueryService;
    @Autowired MenuHoldRepository holdRepository;
    @Autowired MenuHoldTransitionAuditRepository transitionAuditRepository;
    @Autowired MenuInventoryBucketRepository bucketRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired ConsumerAccountRepository consumerRepository;
    @Autowired StoreOperatorAccountRepository operatorRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManagerFactory entityManagerFactory;
    @MockitoBean MenuTransactionFacade menuTransactionFacade;
    @MockitoSpyBean StoreServiceIntervalValidationService intervalService;
    @MockitoSpyBean MenuInventoryService inventoryService;

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
        willReturn(new MenuTransactionEligibility(
                storeId, menuId, 1, "Americano", 5_000, true, false))
                .given(menuTransactionFacade)
                .requireTransactionEligibility(storeId, menuId);
        willAnswer(invocation -> invocation.<List<com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalRequest>>getArgument(0)
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
            assertThat(hold.getStatusVersion()).isZero();
            assertThat(transitionAuditRepository
                    .findByMenuHold_IdAndOccurredAtLessThanEqualOrderByResultVersionAsc(
                            hold.getId(), Instant.now().plusSeconds(1)))
                    .singleElement().satisfies(audit -> {
                        assertThat(audit.getEventType())
                                .isEqualTo(MenuHoldTransitionAudit.EventType.CREATED);
                        assertThat(audit.getResultVersion()).isZero();
                        assertThat(audit.getAfterStatus()).isEqualTo(MenuHoldStatus.CONFIRMED);
                    });
        });
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow().getOnlineHoldRemaining()).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT menu_name_snapshot, unit_price_snapshot
                  FROM menu_hold_items item
                  JOIN menu_holds hold ON hold.menu_hold_id = item.menu_hold_id
                 WHERE hold.reservation_id = ?
                """, reservation.getId()))
                .containsEntry("menu_name_snapshot", "Americano")
                .containsEntry("unit_price_snapshot", 5_000);
    }

    @Test
    @DisplayName("홀드 확정 뒤 메뉴가 변경·종료되어도 거래 스냅샷은 유지된다")
    void preservesMenuSnapshotAfterPublishedMenuChangesAndRetires() {
        transactions.execute(status -> bucketRepository.saveAndFlush(bucket(1)));
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status ->
                service.create(command(reservation.getId(), 1, "snapshot-immutable")));

        transactions.executeWithoutResult(status -> {
            Menu menu = menuRepository.findById(menuId).orElseThrow();
            menu.publish(Instant.parse("2026-08-01T00:00:01Z"));
            menu.appendDraft(menuContent("Cafe Latte", 6_500), 1L,
                    Instant.parse("2026-08-01T00:00:02Z"));
            menu.publish(Instant.parse("2026-08-01T00:00:03Z"));
            menu.retire(Instant.parse("2026-08-01T00:00:04Z"));
            menuRepository.saveAndFlush(menu);
        });

        assertThat(jdbcTemplate.queryForMap("""
                SELECT menu_name_snapshot, unit_price_snapshot, menu_policy_version
                  FROM menu_hold_items item
                  JOIN menu_holds hold ON hold.menu_hold_id = item.menu_hold_id
                 WHERE hold.reservation_id = ?
                """, reservation.getId()))
                .containsEntry("menu_name_snapshot", "Americano")
                .containsEntry("unit_price_snapshot", 5_000)
                .containsEntry("menu_policy_version", 1L);
    }

    @Test
    @DisplayName("예약 당시 메뉴 스냅샷을 저장 순서대로 단일 쿼리에 조회한다")
    void queriesImmutableSnapshotsInStableOrderWithOneStatement() {
        long secondMenuId = transactions.execute(status -> menuRepository.saveAndFlush(
                Menu.create(storeId, menuContent("Cafe Latte", 6_500), consumerId,
                        Instant.parse("2026-08-01T00:00:00Z"))).getId());
        willReturn(new MenuTransactionEligibility(
                storeId, secondMenuId, 1, "Cafe Latte", 6_500, true, false))
                .given(menuTransactionFacade)
                .requireTransactionEligibility(storeId, secondMenuId);
        transactions.executeWithoutResult(status -> {
            bucketRepository.saveAndFlush(bucket(menuId, 2));
            bucketRepository.saveAndFlush(bucket(secondMenuId, 1));
        });
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        MenuHoldCreateCommand command = new MenuHoldCreateCommand(
                reservation.getId(), storeId, consumerId,
                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                Instant.parse("2026-08-10T03:00:00Z"),
                Instant.parse("2026-08-10T04:00:00Z"),
                "snapshot-query", List.of(
                        new MenuSelection(secondMenuId, 1),
                        new MenuSelection(menuId, 2)));
        transactions.executeWithoutResult(status -> service.create(command));

        transactions.executeWithoutResult(status -> {
            Menu menu = menuRepository.findById(menuId).orElseThrow();
            menu.publish(Instant.parse("2026-08-01T00:00:01Z"));
            menu.appendDraft(menuContent("Changed", 9_000), 1L,
                    Instant.parse("2026-08-01T00:00:02Z"));
            menu.publish(Instant.parse("2026-08-01T00:00:03Z"));
            menuRepository.saveAndFlush(menu);
        });
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class)
                .getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        List<MenuHoldItemResult> result =
                snapshotQueryService.findByReservationId(reservation.getId());

        assertThat(result).containsExactly(
                new MenuHoldItemResult(menuId, "Americano", 5_000L, 2),
                new MenuHoldItemResult(secondMenuId, "Cafe Latte", 6_500L, 1));
        assertThat(statistics.getQueries())
                .filteredOn(query -> query.contains("MenuHoldItemResult"))
                .singleElement()
                .satisfies(query -> assertThat(
                        statistics.getQueryStatistics(query).getExecutionCount()).isEqualTo(1L));
    }

    @Test
    @DisplayName("메뉴 홀드가 없는 예약은 빈 메뉴 스냅샷 목록을 반환한다")
    void returnsEmptySnapshotsWhenReservationHasNoMenuHold() {
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));

        assertThat(snapshotQueryService.findByReservationId(reservation.getId())).isEmpty();
    }

    @Test
    @DisplayName("반복 해제는 최초 확보 수량을 정확히 한 번만 복구한다")
    void repeatedReleaseRestoresInventoryExactlyOnce() {
        MenuInventoryBucket bucket = transactions.execute(status ->
                bucketRepository.saveAndFlush(bucket(1)));
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status -> service.create(
                command(reservation.getId(), 1, "release-acquire")));

        transactions.executeWithoutResult(status -> service.release(
                new MenuHoldReleaseCommand(reservation.getId(), "release-first")));
        transactions.executeWithoutResult(status -> service.release(
                new MenuHoldReleaseCommand(reservation.getId(), "release-repeat")));

        assertThat(holdFor(reservation.getId()).getStatus()).isEqualTo(MenuHoldStatus.RELEASED);
        MenuHold released = holdFor(reservation.getId());
        assertThat(released.getStatusVersion()).isEqualTo(1L);
        assertThat(transitionAuditRepository
                .findByMenuHold_IdAndOccurredAtLessThanEqualOrderByResultVersionAsc(
                        released.getId(), Instant.now().plusSeconds(1)))
                .extracting(
                        MenuHoldTransitionAudit::getEventType,
                        MenuHoldTransitionAudit::getResultVersion,
                        MenuHoldTransitionAudit::getAfterStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                MenuHoldTransitionAudit.EventType.CREATED, 0L,
                                MenuHoldStatus.CONFIRMED),
                        org.assertj.core.groups.Tuple.tuple(
                                MenuHoldTransitionAudit.EventType.TRANSITION, 1L,
                                MenuHoldStatus.RELEASED));
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow()
                .getOnlineHoldRemaining()).isEqualTo(1);
        assertThat(restoreLedgerCount("release-acquire")).isEqualTo(1);
    }

    @Test
    @DisplayName("방문 완료는 홀드만 종결하고 메뉴 수량을 복구하지 않는다")
    void fulfillTerminatesHoldWithoutRestoringInventory() {
        MenuInventoryBucket bucket = transactions.execute(status ->
                bucketRepository.saveAndFlush(bucket(1)));
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status -> service.create(
                command(reservation.getId(), 1, "fulfill-acquire")));

        transactions.executeWithoutResult(status -> service.fulfill(
                new MenuHoldFulfillCommand(reservation.getId(), "fulfill-operation")));

        assertThat(holdFor(reservation.getId()).getStatus())
                .isEqualTo(MenuHoldStatus.FULFILLED);
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow()
                .getOnlineHoldRemaining()).isZero();
        assertThat(restoreLedgerCount("fulfill-acquire")).isZero();
    }

    @Test
    @DisplayName("노쇼 몰수는 홀드만 종결하고 메뉴 수량과 복구 원장을 변경하지 않는다")
    void forfeitTerminatesHoldWithoutRestoringInventory() {
        MenuInventoryBucket bucket = transactions.execute(status ->
                bucketRepository.saveAndFlush(bucket(1)));
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status -> service.create(
                command(reservation.getId(), 1, "forfeit-acquire")));

        transactions.executeWithoutResult(status -> service.forfeit(
                new MenuHoldForfeitCommand(reservation.getId(), "forfeit-operation")));

        assertThat(holdFor(reservation.getId()).getStatus())
                .isEqualTo(MenuHoldStatus.FORFEITED);
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow()
                .getOnlineHoldRemaining()).isZero();
        assertThat(restoreLedgerCount("forfeit-acquire")).isZero();
    }

    @Test
    void terminalCommandsRequireCallerTransaction() {
        assertThatThrownBy(() -> service.lockForTermination(Long.MAX_VALUE))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.release(
                new MenuHoldReleaseCommand(Long.MAX_VALUE, "no-transaction-release")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.fulfill(
                new MenuHoldFulfillCommand(Long.MAX_VALUE, "no-transaction-fulfill")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.forfeit(
                new MenuHoldForfeitCommand(Long.MAX_VALUE, "no-transaction-forfeit")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("종결 선잠금은 연결 홀드 존재만 반환하고 상태·수량·원장을 변경하지 않는다")
    void prelockReportsPresentHoldWithoutPersistentSideEffects() {
        MenuInventoryBucket bucket = transactions.execute(status ->
                bucketRepository.saveAndFlush(bucket(1)));
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status -> service.create(
                command(reservation.getId(), 1, "prelock-presence-acquire")));
        long holdsBefore = holdRepository.count();
        int itemsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM menu_hold_items", Integer.class);
        int ledgersBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM menu_inventory_ledger", Integer.class);
        int idempotencyBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_commands", Integer.class);

        MenuHoldTerminationPresence result = transactions.execute(status ->
                service.lockForTermination(reservation.getId()));

        assertThat(result).isEqualTo(MenuHoldTerminationPresence.HOLD_PRESENT);
        assertThat(holdFor(reservation.getId()).getStatus()).isEqualTo(MenuHoldStatus.CONFIRMED);
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow()
                .getOnlineHoldRemaining()).isZero();
        assertThat(holdRepository.count()).isEqualTo(holdsBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM menu_hold_items", Integer.class)).isEqualTo(itemsBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM menu_inventory_ledger", Integer.class)).isEqualTo(ledgersBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_commands", Integer.class))
                .isEqualTo(idempotencyBefore);
    }

    @Test
    @DisplayName("메뉴 홀드가 없는 예약의 종결 선잠금은 행을 만들지 않고 정상 부재를 반환한다")
    void prelockReportsNoHoldWithoutCreatingPersistentState() {
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        long holdsBefore = holdRepository.count();
        int itemsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM menu_hold_items", Integer.class);
        int ledgersBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM menu_inventory_ledger", Integer.class);

        MenuHoldTerminationPresence result = transactions.execute(status ->
                service.lockForTermination(reservation.getId()));

        assertThat(result).isEqualTo(MenuHoldTerminationPresence.NO_HOLD);
        assertThat(holdRepository.count()).isEqualTo(holdsBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM menu_hold_items", Integer.class)).isEqualTo(itemsBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM menu_inventory_ledger", Integer.class)).isEqualTo(ledgersBefore);
    }

    @Test
    @DisplayName("종결 선잠금은 caller transaction 종료 전 경합 해제를 직렬화한다")
    void prelockSerializesCompetingReleaseUntilCallerTransactionCompletes() throws Exception {
        transactions.execute(status -> bucketRepository.saveAndFlush(bucket(1)));
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status -> service.create(
                command(reservation.getId(), 1, "prelock-race-acquire")));
        CountDownLatch prelocked = new CountDownLatch(1);
        CountDownLatch allowPrelockCommit = new CountDownLatch(1);
        CountDownLatch releaseStarted = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MenuHoldTerminationPresence> lock = executor.submit(() ->
                    transactions.execute(status -> {
                        MenuHoldTerminationPresence presence =
                                service.lockForTermination(reservation.getId());
                        prelocked.countDown();
                        try {
                            if (!allowPrelockCommit.await(10, TimeUnit.SECONDS)) {
                                throw new AssertionError("prelock commit was not allowed");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(exception);
                        }
                        return presence;
                    }));
            assertThat(prelocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<MenuHoldCommandResult.Outcome> release = executor.submit(() -> {
                releaseStarted.countDown();
                return transactions.execute(status -> service.release(
                        new MenuHoldReleaseCommand(
                                reservation.getId(), "prelock-race-release")).outcome());
            });
            assertThat(releaseStarted.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                awaitMenuHoldLockWait();
            } finally {
                allowPrelockCommit.countDown();
            }

            assertThat(lock.get(10, TimeUnit.SECONDS))
                    .isEqualTo(MenuHoldTerminationPresence.HOLD_PRESENT);
            assertThat(release.get(10, TimeUnit.SECONDS))
                    .isEqualTo(MenuHoldCommandResult.Outcome.RELEASED);
        }
        assertThat(holdFor(reservation.getId()).getStatus()).isEqualTo(MenuHoldStatus.RELEASED);
        assertThat(restoreLedgerCount("prelock-race-acquire")).isEqualTo(1);
    }

    private void awaitMenuHoldLockWait() {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(LOCK_WAIT_OBSERVATION_TIMEOUT_MILLIS);
        try (Connection monitoringConnection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                "root",
                MYSQL.getPassword()
        )) {
            while (System.nanoTime() < deadlineNanos) {
                if (hasMenuHoldLockWait(monitoringConnection)) {
                    return;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(LOCK_WAIT_OBSERVATION_POLL_MILLIS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "interrupted while observing menu hold lock wait",
                            exception
                    );
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "unable to observe menu hold lock waits with MySQL root connection",
                    exception
            );
        }
        throw new AssertionError("release never entered a MySQL row-lock wait for menu_holds");
    }

    private static boolean hasMenuHoldLockWait(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) "
                        + "FROM performance_schema.data_lock_waits lock_wait "
                        + "JOIN performance_schema.data_locks requested_lock "
                        + "ON requested_lock.engine = lock_wait.engine "
                        + "AND requested_lock.engine_lock_id "
                        + "= lock_wait.requesting_engine_lock_id "
                        + "WHERE requested_lock.object_schema = DATABASE() "
                        + "AND requested_lock.object_name = 'menu_holds'"
        ); ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() && resultSet.getInt(1) > 0;
        }
    }

    @Test
    @DisplayName("해제 뒤 호출자 실패는 홀드 상태와 수량 복구 원장을 모두 롤백한다")
    void callerFailureRollsBackReleaseStateInventoryAndLedger() {
        MenuInventoryBucket bucket = transactions.execute(status ->
                bucketRepository.saveAndFlush(bucket(1)));
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status -> service.create(
                command(reservation.getId(), 1, "rollback-release-acquire")));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            service.release(new MenuHoldReleaseCommand(
                    reservation.getId(), "rollback-release-operation"));
            throw new IllegalStateException("caller failure");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("caller failure");

        assertThat(holdFor(reservation.getId()).getStatus())
                .isEqualTo(MenuHoldStatus.CONFIRMED);
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow()
                .getOnlineHoldRemaining()).isZero();
        assertThat(restoreLedgerCount("rollback-release-acquire")).isZero();
    }

    @Test
    void callerFailureRollsBackFulfillState() {
        MenuInventoryBucket bucket = transactions.execute(status ->
                bucketRepository.saveAndFlush(bucket(1)));
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status -> service.create(
                command(reservation.getId(), 1, "rollback-fulfill-acquire")));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            service.fulfill(new MenuHoldFulfillCommand(
                    reservation.getId(), "rollback-fulfill-operation"));
            throw new IllegalStateException("caller failure");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("caller failure");

        assertThat(holdFor(reservation.getId()).getStatus())
                .isEqualTo(MenuHoldStatus.CONFIRMED);
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow()
                .getOnlineHoldRemaining()).isZero();
        assertThat(restoreLedgerCount("rollback-fulfill-acquire")).isZero();
    }

    @Test
    void callerFailureRollsBackForfeitState() {
        MenuInventoryBucket bucket = transactions.execute(status ->
                bucketRepository.saveAndFlush(bucket(1)));
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status -> service.create(
                command(reservation.getId(), 1, "rollback-forfeit-acquire")));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            service.forfeit(new MenuHoldForfeitCommand(
                    reservation.getId(), "rollback-forfeit-operation"));
            throw new IllegalStateException("caller failure");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("caller failure");

        assertThat(holdFor(reservation.getId()).getStatus())
                .isEqualTo(MenuHoldStatus.CONFIRMED);
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow()
                .getOnlineHoldRemaining()).isZero();
        assertThat(restoreLedgerCount("rollback-forfeit-acquire")).isZero();
    }

    @Test
    @DisplayName("수량 복구 오류는 원형 전달되고 홀드 상태도 롤백된다")
    void inventoryRestoreFailurePropagatesAndRollsBackHoldState() {
        MenuInventoryBucket bucket = transactions.execute(status ->
                bucketRepository.saveAndFlush(bucket(1)));
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status -> service.create(
                command(reservation.getId(), 1, "restore-failure-acquire")));
        ServiceException failure = new ServiceException(MenuHoldErrorCode.BUCKET_NOT_FOUND);
        transactions.executeWithoutResult(status ->
                doThrow(failure).when(inventoryService).restoreInventory(
                        org.mockito.ArgumentMatchers.any()));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                service.release(new MenuHoldReleaseCommand(
                        reservation.getId(), "restore-failure-release"))))
                .isSameAs(failure);

        assertThat(holdFor(reservation.getId()).getStatus())
                .isEqualTo(MenuHoldStatus.CONFIRMED);
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow()
                .getOnlineHoldRemaining()).isZero();
        assertThat(restoreLedgerCount("restore-failure-acquire")).isZero();
    }

    @Test
    @DisplayName("동시 해제는 둘 다 멱등 성공하고 수량은 한 번만 복구한다")
    void concurrentReleaseIsIdempotentAndRestoresOnce() throws Exception {
        MenuInventoryBucket bucket = transactions.execute(status ->
                bucketRepository.saveAndFlush(bucket(1)));
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status -> service.create(
                command(reservation.getId(), 1, "concurrent-release-acquire")));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> terminalConcurrently(
                    reservation.getId(), "concurrent-release-1", true, ready, start));
            Future<Object> second = executor.submit(() -> terminalConcurrently(
                    reservation.getId(), "concurrent-release-2", true, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)))
                    .containsOnly(com.miriyum.domain.menuhold.dto.MenuHoldCommandResult
                            .Outcome.RELEASED);
        }
        assertThat(holdFor(reservation.getId()).getStatus()).isEqualTo(MenuHoldStatus.RELEASED);
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow()
                .getOnlineHoldRemaining()).isEqualTo(1);
        assertThat(restoreLedgerCount("concurrent-release-acquire")).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 몰수는 둘 다 멱등 성공하고 수량과 복구 원장을 변경하지 않는다")
    void concurrentForfeitIsIdempotentWithoutRestore() throws Exception {
        MenuInventoryBucket bucket = transactions.execute(status ->
                bucketRepository.saveAndFlush(bucket(1)));
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status -> service.create(
                command(reservation.getId(), 1, "concurrent-forfeit-acquire")));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> forfeitConcurrently(
                    reservation.getId(), "concurrent-forfeit-1", ready, start));
            Future<Object> second = executor.submit(() -> forfeitConcurrently(
                    reservation.getId(), "concurrent-forfeit-2", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)))
                    .containsOnly(MenuHoldCommandResult.Outcome.FORFEITED);
        }
        assertThat(holdFor(reservation.getId()).getStatus())
                .isEqualTo(MenuHoldStatus.FORFEITED);
        assertThat(bucketRepository.findById(bucket.getId()).orElseThrow()
                .getOnlineHoldRemaining()).isZero();
        assertThat(restoreLedgerCount("concurrent-forfeit-acquire")).isZero();
    }

    @Test
    @DisplayName("해제와 이행 경합은 하나의 최종 상태만 확정한다")
    void concurrentReleaseAndFulfillCommitOneTerminalState() throws Exception {
        MenuInventoryBucket bucket = transactions.execute(status ->
                bucketRepository.saveAndFlush(bucket(1)));
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status -> service.create(
                command(reservation.getId(), 1, "terminal-race-acquire")));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Object> results;

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> release = executor.submit(() -> terminalConcurrently(
                    reservation.getId(), "terminal-race-release", true, ready, start));
            Future<Object> fulfill = executor.submit(() -> terminalConcurrently(
                    reservation.getId(), "terminal-race-fulfill", false, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            results = List.of(release.get(20, TimeUnit.SECONDS),
                    fulfill.get(20, TimeUnit.SECONDS));
        }

        assertThat(results).contains(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        MenuHoldStatus finalStatus = holdFor(reservation.getId()).getStatus();
        if (finalStatus == MenuHoldStatus.RELEASED) {
            assertThat(results).contains(
                    com.miriyum.domain.menuhold.dto.MenuHoldCommandResult.Outcome.RELEASED);
            assertThat(bucketRepository.findById(bucket.getId()).orElseThrow()
                    .getOnlineHoldRemaining()).isEqualTo(1);
        } else {
            assertThat(finalStatus).isEqualTo(MenuHoldStatus.FULFILLED);
            assertThat(results).contains(
                    com.miriyum.domain.menuhold.dto.MenuHoldCommandResult.Outcome.FULFILLED);
            assertThat(bucketRepository.findById(bucket.getId()).orElseThrow()
                    .getOnlineHoldRemaining()).isZero();
        }
    }

    @Test
    @DisplayName("해제·이행·몰수 경합은 정확히 하나의 최종 상태만 확정한다")
    void concurrentReleaseFulfillAndForfeitCommitOneTerminalState() throws Exception {
        MenuInventoryBucket bucket = transactions.execute(status ->
                bucketRepository.saveAndFlush(bucket(1)));
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status -> service.create(
                command(reservation.getId(), 1, "three-way-terminal-race-acquire")));
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Object> results;

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<Object> release = executor.submit(() -> terminalConcurrently(
                    reservation.getId(), "three-way-release", true, ready, start));
            Future<Object> fulfill = executor.submit(() -> terminalConcurrently(
                    reservation.getId(), "three-way-fulfill", false, ready, start));
            Future<Object> forfeit = executor.submit(() -> forfeitConcurrently(
                    reservation.getId(), "three-way-forfeit", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            results = List.of(release.get(20, TimeUnit.SECONDS),
                    fulfill.get(20, TimeUnit.SECONDS),
                    forfeit.get(20, TimeUnit.SECONDS));
        }

        assertThat(results.stream()
                .filter(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT::equals)
                .count()).isEqualTo(2L);
        MenuHoldStatus finalStatus = holdFor(reservation.getId()).getStatus();
        if (finalStatus == MenuHoldStatus.RELEASED) {
            assertThat(results).contains(MenuHoldCommandResult.Outcome.RELEASED);
            assertThat(bucketRepository.findById(bucket.getId()).orElseThrow()
                    .getOnlineHoldRemaining()).isEqualTo(1);
            assertThat(restoreLedgerCount("three-way-terminal-race-acquire")).isEqualTo(1);
        } else if (finalStatus == MenuHoldStatus.FULFILLED) {
            assertThat(results).contains(MenuHoldCommandResult.Outcome.FULFILLED);
            assertThat(bucketRepository.findById(bucket.getId()).orElseThrow()
                    .getOnlineHoldRemaining()).isZero();
            assertThat(restoreLedgerCount("three-way-terminal-race-acquire")).isZero();
        } else {
            assertThat(finalStatus).isEqualTo(MenuHoldStatus.FORFEITED);
            assertThat(results).contains(MenuHoldCommandResult.Outcome.FORFEITED);
            assertThat(bucketRepository.findById(bucket.getId()).orElseThrow()
                    .getOnlineHoldRemaining()).isZero();
            assertThat(restoreLedgerCount("three-way-terminal-race-acquire")).isZero();
        }
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
    @DisplayName("V21과 V34의 FK·CHECK·유일 인덱스가 실제 MySQL에 정확히 생성된다")
    void mysqlSchemaDefinesExactConstraintsAndIndexOrder() {
        assertThat(constraintNames("menu_holds", "FOREIGN KEY"))
                .containsExactlyInAnyOrder(
                        "fk_menu_holds_reservation",
                        "fk_menu_holds_store",
                        "fk_menu_holds_consumer",
                        "fk_menu_holds_reservation_hold_expiration");
        assertThat(constraintNames("menu_hold_items", "FOREIGN KEY"))
                .containsExactlyInAnyOrder(
                        "fk_menu_hold_items_hold",
                        "fk_menu_hold_items_menu",
                        "fk_menu_hold_items_bucket");
        assertThat(constraintNames("menu_holds", "CHECK"))
                .containsExactlyInAnyOrder(
                        "ck_menu_holds_service_interval",
                        "ck_menu_holds_parent_and_status",
                        "ck_menu_holds_status_version");
        assertThat(constraintNames("menu_hold_items", "CHECK"))
                .containsExactlyInAnyOrder(
                        "ck_menu_hold_items_versions",
                        "ck_menu_hold_items_name_snapshot",
                        "ck_menu_hold_items_unit_price_snapshot",
                        "ck_menu_hold_items_quantity");

        assertThat(indexColumns("menu_holds", "uk_menu_holds_reservation"))
                .isEqualTo("reservation_id");
        assertThat(indexColumns("menu_holds", "uk_menu_holds_acquire_operation"))
                .isEqualTo("acquire_operation_id");
        assertThat(indexColumns("menu_holds", "uk_menu_holds_reservation_hold"))
                .isEqualTo("reservation_hold_id");
        assertThat(indexColumns("menu_hold_items", "uk_menu_hold_items_hold_bucket"))
                .isEqualTo("menu_hold_id,menu_inventory_bucket_id");
        assertThat(indexColumns("menu_hold_items", "idx_menu_hold_items_bucket"))
                .isEqualTo("menu_inventory_bucket_id,menu_hold_item_id");
        assertThat(constraintNames("menu_hold_transition_audits", "FOREIGN KEY"))
                .containsExactly("fk_menu_hold_transition_hold");
        assertThat(constraintNames("menu_hold_transition_audits", "CHECK"))
                .containsExactlyInAnyOrder(
                        "ck_menu_hold_transition_event_type",
                        "ck_menu_hold_transition_result_version",
                        "ck_menu_hold_transition_shape");
        assertThat(indexColumns(
                "menu_hold_transition_audits", "uk_menu_hold_transition_version"))
                .isEqualTo("menu_hold_id,result_version");
    }

    @Test
    @DisplayName("임시 MenuHold의 부모·만료·상태 계약을 실제 MySQL이 강제한다")
    void mysqlSchemaDefinesTemporaryParentAndStateConstraints() {
        String operationPrefix = "temporary-schema-" + consumerId + "-";
        long firstParentId = insertReservationHoldParent(
                operationPrefix + "parent-1",
                LocalDateTime.of(2026, 8, 10, 3, 0));
        long secondParentId = insertReservationHoldParent(
                operationPrefix + "parent-2",
                LocalDateTime.of(2026, 8, 10, 4, 0));
        LocalDateTime firstExpiry = reservationHoldExpiry(firstParentId);
        LocalDateTime secondExpiry = reservationHoldExpiry(secondParentId);

        assertThat(constraintNames("menu_holds", "FOREIGN KEY"))
                .as("the composite ReservationHold parent FK must exist")
                .contains("fk_menu_holds_reservation_hold_expiration");
        assertThat(foreignKeyColumnMapping(
                "menu_holds", "fk_menu_holds_reservation_hold_expiration"))
                .as("the FK must bind both parent identity and exact expiry")
                .isEqualTo("reservation_hold_id->reservation_hold_id,expires_at->expires_at");
        assertThat(indexColumns("menu_holds", "uk_menu_holds_reservation_hold"))
                .as("one ReservationHold must have at most one temporary MenuHold")
                .isEqualTo("reservation_hold_id");
        assertThat(constraintNames("menu_holds", "CHECK"))
                .as("the parent/state/nullability CHECK must replace the legacy status CHECK")
                .contains("ck_menu_holds_parent_and_status")
                .doesNotContain("ck_menu_holds_status");

        assertThatThrownBy(() -> insertMenuHold(
                null,
                firstParentId,
                firstExpiry.plusNanos(1_000),
                operationPrefix + "mismatched-expiry",
                "ACTIVE"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_menu_holds_reservation_hold_expiration");

        insertMenuHold(
                null,
                firstParentId,
                firstExpiry,
                operationPrefix + "valid-temporary",
                "ACTIVE");

        assertThatThrownBy(() -> insertMenuHold(
                null,
                firstParentId,
                firstExpiry,
                operationPrefix + "duplicate-parent",
                "ACTIVE"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_menu_holds_reservation_hold");

        assertThatThrownBy(() -> insertMenuHold(
                null,
                null,
                null,
                operationPrefix + "missing-parent",
                "ACTIVE"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_menu_holds_parent_and_status");
        assertThatThrownBy(() -> insertMenuHold(
                null,
                secondParentId,
                null,
                operationPrefix + "missing-expiry",
                "ACTIVE"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_menu_holds_parent_and_status");
        assertThatThrownBy(() -> insertMenuHold(
                null,
                secondParentId,
                secondExpiry,
                operationPrefix + "unlinked-confirmed",
                "CONFIRMED"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_menu_holds_parent_and_status");
        assertThatThrownBy(() -> insertMenuHold(
                null,
                secondParentId,
                secondExpiry,
                operationPrefix + "unlinked-forfeited",
                "FORFEITED"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_menu_holds_parent_and_status");

        Reservation linkedTemporaryReservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        insertMenuHold(
                linkedTemporaryReservation.getId(),
                secondParentId,
                secondExpiry,
                operationPrefix + "linked-forfeited",
                "FORFEITED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM menu_holds WHERE reservation_id = ?",
                String.class,
                linkedTemporaryReservation.getId())).isEqualTo("FORFEITED");

        Reservation legacyReservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        insertMenuHold(
                legacyReservation.getId(),
                null,
                null,
                operationPrefix + "legacy-confirmed",
                "CONFIRMED");
        jdbcTemplate.update(
                "UPDATE menu_holds SET status = 'RELEASED' WHERE reservation_id = ?",
                legacyReservation.getId());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM menu_holds WHERE reservation_id = ?",
                String.class,
                legacyReservation.getId())).isEqualTo("RELEASED");
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
    @DisplayName("빈 메뉴명과 음수 단가는 실제 MySQL 스냅샷 CHECK 제약으로 거부된다")
    void mysqlRejectsInvalidMenuDisplaySnapshot() {
        transactions.execute(status -> bucketRepository.saveAndFlush(bucket(1)));
        Reservation reservation = transactions.execute(status ->
                reservationRepository.saveAndFlush(reservation()));
        transactions.executeWithoutResult(status ->
                service.create(command(reservation.getId(), 1, "check-display-snapshot")));
        Long itemId = jdbcTemplate.queryForObject(
                "SELECT menu_hold_item_id FROM menu_hold_items WHERE menu_hold_id = "
                        + "(SELECT menu_hold_id FROM menu_holds WHERE reservation_id = ?)",
                Long.class, reservation.getId());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE menu_hold_items SET menu_name_snapshot = ' ' "
                        + "WHERE menu_hold_item_id = ?", itemId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_menu_hold_items_name_snapshot");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE menu_hold_items SET unit_price_snapshot = -1 "
                        + "WHERE menu_hold_item_id = ?", itemId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_menu_hold_items_unit_price_snapshot");
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

    private String foreignKeyColumnMapping(String tableName, String constraintName) {
        return jdbcTemplate.queryForObject("""
                SELECT GROUP_CONCAT(
                           CONCAT(column_name, '->', referenced_column_name)
                           ORDER BY ordinal_position SEPARATOR ','
                       )
                  FROM information_schema.key_column_usage
                 WHERE table_schema = DATABASE()
                   AND table_name = ?
                   AND constraint_name = ?
                """, String.class, tableName, constraintName);
    }

    private LocalDateTime reservationHoldExpiry(long reservationHoldId) {
        return jdbcTemplate.queryForObject(
                "SELECT expires_at FROM reservation_holds WHERE reservation_hold_id = ?",
                LocalDateTime.class,
                reservationHoldId);
    }

    private long insertReservationHoldParent(String commandId, LocalDateTime createdAt) {
        LocalDateTime expiresAt = createdAt.plusMinutes(10);
        jdbcTemplate.update("""
                INSERT INTO reservation_holds (
                    consumer_account_id,
                    store_id,
                    store_name_snapshot,
                    service_date,
                    start_at,
                    service_end_at,
                    occupancy_end_at,
                    time_zone_id_snapshot,
                    start_offset_seconds,
                    service_end_offset_seconds,
                    occupancy_end_offset_seconds,
                    slot_interval_minutes,
                    service_duration_minutes,
                    turnover_duration_minutes,
                    reservation_time_policy_store_id,
                    reservation_policy_version,
                    adult_count,
                    child_count,
                    infant_count,
                    notification_target_reference,
                    contact_available_at_confirmation,
                    capacity_policy_version,
                    cancellation_policy_version,
                    status,
                    status_version,
                    creation_command_id,
                    created_at,
                    expires_at
                ) VALUES (
                    ?, ?, 'store', ?, ?, ?, ?, 'Asia/Seoul',
                    32400, 32400, 32400, 30, 60, 0, ?, 1,
                    2, 0, 0, ?, TRUE, 1, 1, 'ACTIVE', 0, ?, ?, ?
                )
                """,
                consumerId,
                storeId,
                LocalDate.of(2026, 8, 10),
                LocalDateTime.of(2026, 8, 10, 3, 0),
                LocalDateTime.of(2026, 8, 10, 4, 0),
                LocalDateTime.of(2026, 8, 10, 4, 0),
                storeId,
                "consumer:" + consumerId,
                commandId,
                createdAt,
                expiresAt);
        return jdbcTemplate.queryForObject("""
                SELECT reservation_hold_id
                  FROM reservation_holds
                 WHERE consumer_account_id = ?
                   AND creation_command_id = ?
                """, Long.class, consumerId, commandId);
    }

    private void insertMenuHold(
            Long reservationId,
            Long reservationHoldId,
            LocalDateTime expiresAt,
            String operationId,
            String status
    ) {
        jdbcTemplate.update("""
                INSERT INTO menu_holds (
                    reservation_id,
                    reservation_hold_id,
                    expires_at,
                    store_id,
                    consumer_account_id,
                    service_date,
                    start_time,
                    end_date,
                    end_time,
                    acquire_operation_id,
                    status,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                reservationId,
                reservationHoldId,
                expiresAt,
                storeId,
                consumerId,
                LocalDate.of(2026, 8, 10),
                LocalTime.NOON,
                LocalDate.of(2026, 8, 10),
                LocalTime.of(13, 0),
                operationId,
                status);
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
                1L, new ReservationCancellationPolicyVersion(1L),
                Instant.parse("2026-08-01T00:00:00Z"));
    }

    private MenuHoldCreateCommand command(long reservationId, int quantity, String operationId) {
        return new MenuHoldCreateCommand(reservationId, storeId,
                consumerId, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                Instant.parse("2026-08-10T03:00:00Z"),
                Instant.parse("2026-08-10T04:00:00Z"), operationId,
                List.of(new MenuSelection(menuId, quantity)));
    }

    private MenuInventoryBucket bucket(int online) {
        return bucket(menuId, online);
    }

    private Object terminalConcurrently(
            long reservationId,
            String operationId,
            boolean release,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                return AssertionError.class;
            }
            return transactions.execute(status -> release
                    ? service.release(new MenuHoldReleaseCommand(reservationId, operationId))
                            .outcome()
                    : service.fulfill(new MenuHoldFulfillCommand(reservationId, operationId))
                            .outcome());
        } catch (ServiceException exception) {
            return exception.getErrorCode();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return InterruptedException.class;
        }
    }

    private Object forfeitConcurrently(
            long reservationId,
            String operationId,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                return AssertionError.class;
            }
            return transactions.execute(status -> service.forfeit(
                    new MenuHoldForfeitCommand(reservationId, operationId)).outcome());
        } catch (ServiceException exception) {
            return exception.getErrorCode();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return InterruptedException.class;
        }
    }

    private MenuHold holdFor(long reservationId) {
        return holdRepository.findAll().stream()
                .filter(hold -> Long.valueOf(reservationId).equals(hold.getReservationId()))
                .findFirst()
                .orElseThrow();
    }

    private int restoreLedgerCount(String sourceAcquireOperationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM menu_inventory_ledger
                 WHERE source_operation_id = ?
                   AND operation_type = 'RESTORE'
                """, Integer.class, sourceAcquireOperationId);
    }

    private MenuInventoryBucket bucket(long selectedMenuId, int online) {
        return MenuInventoryBucket.create(selectedMenuId,
                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0), "Asia/Seoul", 1L,
                online, online, 0, 0, false);
    }

    private static MenuContent menuContent() {
        return menuContent("Americano", 5_000);
    }

    private static MenuContent menuContent(String name, int price) {
        return new MenuContent(name, "", price, false, "BEVERAGE", List.of(),
                List.of(), true, true, DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosure(AllergenIngredientCode.MILK,
                        AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.NOT_APPLICABLE, List.of(), false);
    }
}
