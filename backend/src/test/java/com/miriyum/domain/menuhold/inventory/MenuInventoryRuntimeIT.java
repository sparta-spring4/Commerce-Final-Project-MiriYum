package com.miriyum.domain.menuhold.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.dto.InventoryAcquireRequest;
import com.miriyum.domain.menuhold.inventory.dto.InventoryAllocationResult;
import com.miriyum.domain.menuhold.inventory.dto.InventoryRestoreRequest;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryLedgerRepository;
import com.miriyum.domain.menuhold.service.MenuHoldService;
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
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.menu.schedule.enabled=false"
        })
class MenuInventoryRuntimeIT {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private MenuHoldService menuHoldService;

    @Autowired
    private MenuInventoryBucketRepository bucketRepository;

    @Autowired
    private MenuInventoryLedgerRepository ledgerRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private long menuId;

    @BeforeEach
    void setUpMenu() {
        int sequence = SEQUENCE.incrementAndGet();
        menuId = transactionTemplate.execute(status -> {
            long operatorId = operatorRepository.saveAndFlush(
                    StoreOperatorAccount.create(
                            "inventory-owner-" + sequence + "@example.com", "hashed", "owner"))
                    .getId();
            Store store = storeRepository.saveAndFlush(Store.create(
                    operatorId, String.format("%010d", sequence), BusinessType.CAFE, "store", "",
                    Region.SEOUL, "address", "CAFE_BAKERY", Set.of(),
                    true, true, true, "Asia/Seoul",
                    LocalDateTime.of(2026, 8, 1, 9, 0),
                    "STORE_ONBOARDING_REQUIRED_TERMS_V1"));
            return menuRepository.saveAndFlush(Menu.create(
                    store.getId(), menuContent(), operatorId,
                    Instant.parse("2026-08-01T00:00:00Z"))).getId();
        });
    }

    @Test
    void acquireAndDistinctRestoreOperationsRestoreSourceOnlyOnce() {
        long ledgerCountBefore = ledgerRepository.count();
        MenuInventoryBucket bucket = transactionTemplate.execute(status ->
                bucketRepository.saveAndFlush(bucket(menuId, 2, 3)));
        InventoryAcquireRequest acquire = new InventoryAcquireRequest(
                "reservation:77:create", List.of(selection(menuId, 4)));

        List<InventoryAllocationResult> acquired = transactionTemplate.execute(status ->
                menuHoldService.acquireInventory(acquire));
        transactionTemplate.executeWithoutResult(status -> menuHoldService.restoreInventory(
                new InventoryRestoreRequest("reservation:77:cancel:1", acquire.operationId())));
        transactionTemplate.executeWithoutResult(status -> menuHoldService.restoreInventory(
                new InventoryRestoreRequest("reservation:77:cancel:2", acquire.operationId())));

        MenuInventoryBucket restored = bucketRepository.findById(bucket.getId()).orElseThrow();
        assertThat(acquired).containsExactly(
                new InventoryAllocationResult(bucket.getId(), 2, 2));
        assertThat(restored.getOnlineHoldRemaining()).isEqualTo(2);
        assertThat(restored.getSharedRemaining()).isEqualTo(3);
        assertThat(ledgerRepository.count()).isEqualTo(ledgerCountBefore + 4);
    }

    @Test
    void operationIdsDifferingOnlyByCaseRemainDistinct() {
        long ledgerCountBefore = ledgerRepository.count();
        MenuInventoryBucket bucket = transactionTemplate.execute(status ->
                bucketRepository.saveAndFlush(bucket(menuId, 2, 0)));

        transactionTemplate.execute(status -> menuHoldService.acquireInventory(
                new InventoryAcquireRequest(
                        "reservation:case:A", List.of(selection(menuId, 1)))));
        transactionTemplate.execute(status -> menuHoldService.acquireInventory(
                new InventoryAcquireRequest(
                        "reservation:case:a", List.of(selection(menuId, 1)))));

        MenuInventoryBucket depleted = bucketRepository.findById(bucket.getId()).orElseThrow();
        assertThat(depleted.getOnlineHoldRemaining()).isZero();
        assertThat(ledgerRepository.count()).isEqualTo(ledgerCountBefore + 2);
    }

    @Test
    void insufficientSecondBucketRollsBackTheFirstBucketAndLedger() {
        MenuInventoryBucket first = transactionTemplate.execute(status ->
                bucketRepository.saveAndFlush(bucket(menuId, 2, 0)));
        long secondMenuId = transactionTemplate.execute(status -> menuRepository.saveAndFlush(
                Menu.create(
                        storeRepository.findAll().getFirst().getId(),
                        menuContent(),
                        operatorRepository.findAll().getFirst().getId(),
                        Instant.parse("2026-08-01T00:00:01Z"))).getId());
        transactionTemplate.executeWithoutResult(status ->
                bucketRepository.saveAndFlush(bucket(secondMenuId, 1, 0)));
        InventoryAcquireRequest request = new InventoryAcquireRequest(
                "reservation:88:create",
                List.of(selection(menuId, 2), selection(secondMenuId, 2)));
        long ledgerCountBefore = ledgerRepository.count();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                menuHoldService.acquireInventory(request)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(MenuHoldErrorCode.INSUFFICIENT_QUANTITY);

        assertThat(bucketRepository.findById(first.getId()).orElseThrow()
                .getOnlineHoldRemaining()).isEqualTo(2);
        assertThat(ledgerRepository.count()).isEqualTo(ledgerCountBefore);
    }

    @Test
    void concurrentDistinctRestoreOperationsConvergeOnSingleSourceRestore() throws Exception {
        long ledgerCountBefore = ledgerRepository.count();
        MenuInventoryBucket bucket = transactionTemplate.execute(status ->
                bucketRepository.saveAndFlush(bucket(menuId, 2, 0)));
        InventoryAcquireRequest acquire = new InventoryAcquireRequest(
                "reservation:99:create", List.of(selection(menuId, 2)));
        transactionTemplate.execute(status -> menuHoldService.acquireInventory(acquire));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> restoreConcurrently(
                    "reservation:99:cancel:1", acquire.operationId(), ready, start));
            Future<Boolean> second = executor.submit(() -> restoreConcurrently(
                    "reservation:99:cancel:2", acquire.operationId(), ready, start));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(20, TimeUnit.SECONDS)).isTrue();
        }

        MenuInventoryBucket restored = bucketRepository.findById(bucket.getId()).orElseThrow();
        assertThat(restored.getOnlineHoldRemaining()).isEqualTo(2);
        assertThat(ledgerRepository.count()).isEqualTo(ledgerCountBefore + 2);
    }

    private boolean restoreConcurrently(
            String operationId,
            String sourceOperationId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            return false;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> menuHoldService.restoreInventory(
                    new InventoryRestoreRequest(operationId, sourceOperationId)));
            return true;
        } catch (ServiceException exception) {
            return false;
        }
    }

    private static InventoryAcquireRequest.Selection selection(long selectedMenuId, int quantity) {
        return new InventoryAcquireRequest.Selection(
                selectedMenuId,
                LocalDate.of(2026, 8, 10),
                LocalTime.of(12, 0),
                LocalDate.of(2026, 8, 10),
                LocalTime.of(13, 0),
                1L,
                quantity);
    }

    private static MenuInventoryBucket bucket(long selectedMenuId, int online, int shared) {
        return MenuInventoryBucket.create(
                selectedMenuId,
                LocalDate.of(2026, 8, 10),
                LocalTime.of(12, 0),
                LocalDate.of(2026, 8, 10),
                LocalTime.of(13, 0),
                "Asia/Seoul",
                1L,
                online + shared,
                online,
                0,
                shared,
                true);
    }

    private static MenuContent menuContent() {
        return new MenuContent(
                "Americano", "", 5_000, false, "BEVERAGE",
                List.of(), List.of(), true, true,
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosure(
                        AllergenIngredientCode.MILK,
                        AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.NOT_APPLICABLE,
                List.of(), false);
    }
}
