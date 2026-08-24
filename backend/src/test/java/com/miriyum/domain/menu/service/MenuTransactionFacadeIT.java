package com.miriyum.domain.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.menu.dto.contract.MenuTransactionEligibility;
import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.model.AllergenDisclosure;
import com.miriyum.domain.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.menu.model.AllergenIngredientCode;
import com.miriyum.domain.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.menu.model.MenuContent;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.menu.schedule.enabled=false"
        })
class MenuTransactionFacadeIT {

    private static final long LOCK_WAIT_TIMEOUT_MILLIS = 5_000;
    private static final long LOCK_WAIT_POLL_MILLIS = 50;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add(
                "spring.datasource.hikari.connection-init-sql",
                () -> "SET SESSION innodb_lock_wait_timeout = 10");
    }

    @Autowired
    private MenuTransactionFacade service;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.execute("DELETE FROM menu_version_origin_disclosures");
        jdbcTemplate.execute("DELETE FROM menu_version_allergen_disclosures");
        jdbcTemplate.execute("DELETE FROM menu_version_local_tags");
        jdbcTemplate.execute("DELETE FROM menu_version_secondary_categories");
        jdbcTemplate.execute("DELETE FROM menu_versions");
        jdbcTemplate.execute("DELETE FROM menus");
        jdbcTemplate.execute("DELETE FROM store_tag_assignment");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
    }

    @Test
    void rejectsInvocationWithoutCallerTransaction() {
        Fixture fixture = createFixture();

        assertThatThrownBy(() -> service.requireTransactionEligibility(
                fixture.storeId(), fixture.menuId()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void returnsPublishedSnapshotInsideCallerTransaction() {
        Fixture fixture = createFixture();

        MenuTransactionEligibility result = transactions.execute(ignored ->
                service.requireTransactionEligibility(fixture.storeId(), fixture.menuId()));

        assertThat(result).isEqualTo(new MenuTransactionEligibility(
                fixture.storeId(),
                fixture.menuId(),
                1,
                "Americano",
                5_000,
                true,
                true));
    }

    @Test
    void locksStoreBeforeWaitingForMenuRow() throws Exception {
        Fixture fixture = createFixture();
        CountDownLatch menuLocked = new CountDownLatch(1);
        CountDownLatch releaseMenu = new CountDownLatch(1);
        CountDownLatch serviceStarted = new CountDownLatch(1);
        CountDownLatch storeLockStarted = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> menuHolder = executor.submit(() ->
                    transactions.executeWithoutResult(ignored -> {
                        menuRepository.findByIdForUpdate(fixture.menuId()).orElseThrow();
                        menuLocked.countDown();
                        await(releaseMenu);
                    }));
            assertThat(menuLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<MenuTransactionEligibility> transaction = executor.submit(() ->
                    transactions.execute(ignored -> {
                        serviceStarted.countDown();
                        return service.requireTransactionEligibility(
                                fixture.storeId(), fixture.menuId());
                    }));
            assertThat(serviceStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitRowLockWait("menus");

            Future<?> storeContender = executor.submit(() ->
                    transactions.executeWithoutResult(ignored -> {
                        storeLockStarted.countDown();
                        storeRepository.findByIdForUpdate(fixture.storeId()).orElseThrow();
                    }));
            assertThat(storeLockStarted.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                awaitRowLockWait("stores");
                assertThat(storeContender.isDone()).isFalse();
            } finally {
                releaseMenu.countDown();
            }

            menuHolder.get(10, TimeUnit.SECONDS);
            assertThat(transaction.get(10, TimeUnit.SECONDS).menuId())
                    .isEqualTo(fixture.menuId());
            storeContender.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void callerFailureRollsBackChangesAfterEligibilityCheck() {
        Fixture fixture = createFixture();

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            service.requireTransactionEligibility(fixture.storeId(), fixture.menuId());
            storeRepository.findById(fixture.storeId()).orElseThrow().close();
            throw new IllegalStateException("force caller rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force caller rollback");

        assertThat(storeRepository.findById(fixture.storeId()).orElseThrow()
                .getOperationStatus()).isEqualTo(OperationStatus.OPEN);
    }

    private Fixture createFixture() {
        return transactions.execute(ignored -> {
            long operatorId = operatorRepository.saveAndFlush(
                    StoreOperatorAccount.create(
                            "menu-transaction-owner@example.com",
                            "hashed",
                            "owner")).getId();
            long storeId = storeRepository.saveAndFlush(Store.create(
                    operatorId,
                    "1234567890",
                    "Transaction Store",
                    "",
                    Region.SEOUL,
                    "Seoul",
                    "CAFE_BAKERY",
                    Set.of(),
                    true,
                    true,
                    true,
                    "Asia/Seoul",
                    LocalDateTime.of(2026, 8, 11, 9, 0),
                    "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
            Menu menu = Menu.create(
                    storeId,
                    menuContent(),
                    operatorId,
                    Instant.parse("2026-08-11T00:00:00Z"));
            menu.publish(Instant.parse("2026-08-11T00:00:01Z"));
            long menuId = menuRepository.saveAndFlush(menu).getId();
            return new Fixture(storeId, menuId);
        });
    }

    private MenuContent menuContent() {
        return new MenuContent(
                "Americano",
                "",
                5_000,
                true,
                "BEVERAGE",
                List.of(),
                List.of("signature"),
                true,
                true,
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosure(
                        AllergenIngredientCode.MILK,
                        AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.NOT_APPLICABLE,
                List.of(),
                false);
    }

    private void awaitRowLockWait(String tableName) {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(LOCK_WAIT_TIMEOUT_MILLIS);
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            while (System.nanoTime() < deadlineNanos) {
                if (hasRowLockWait(connection, tableName)) {
                    return;
                }
                TimeUnit.MILLISECONDS.sleep(LOCK_WAIT_POLL_MILLIS);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("unable to observe MySQL row-lock waits", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while observing MySQL row-lock waits", exception);
        }
        throw new AssertionError("no MySQL row-lock wait observed for " + tableName);
    }

    private boolean hasRowLockWait(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) "
                        + "FROM performance_schema.data_lock_waits lock_wait "
                        + "JOIN performance_schema.data_locks requested_lock "
                        + "ON requested_lock.engine = lock_wait.engine "
                        + "AND requested_lock.engine_lock_id = lock_wait.requesting_engine_lock_id "
                        + "WHERE requested_lock.object_schema = DATABASE() "
                        + "AND requested_lock.object_name = ? "
                        + "AND requested_lock.index_name = 'PRIMARY'")) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private record Fixture(long storeId, long menuId) {
    }
}
