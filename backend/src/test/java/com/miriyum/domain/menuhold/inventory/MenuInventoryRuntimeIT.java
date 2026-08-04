package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.dto.InventoryAcquireRequest;
import com.miriyum.domain.menuhold.inventory.dto.InventoryAllocationResult;
import com.miriyum.domain.menuhold.inventory.dto.InventoryRestoreRequest;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import com.miriyum.domain.menuhold.inventory.model.InventoryLedgerOperation;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryLedgerRepository;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryPolicyAuditRepository;
import com.miriyum.domain.menuhold.inventory.dto.InventoryPolicyChange;
import com.miriyum.domain.menuhold.inventory.dto.InventoryBucketCreateCommand;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.core.service.StoreScheduleAuthority;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.menu.entity.Menu;
import com.miriyum.domain.store.menu.dto.ManagedMenuResponse;
import com.miriyum.domain.store.menu.dto.MenuTransactionEligibility;
import com.miriyum.domain.store.menu.enums.MenuSellingStatus;
import com.miriyum.domain.store.menu.enums.MenuVisibility;
import com.miriyum.domain.store.menu.model.AllergenDisclosure;
import com.miriyum.domain.store.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.store.menu.model.AllergenIngredientCode;
import com.miriyum.domain.store.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.store.menu.model.MenuContent;
import com.miriyum.domain.store.menu.repository.MenuRepository;
import com.miriyum.domain.store.menu.service.MenuQueryService;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.idempotency.IdempotencyKey;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
    private MenuInventoryService menuHoldService;

    @Autowired
    private MenuInventoryBucketRepository bucketRepository;

    @Autowired
    private MenuInventoryPolicyService policyService;

    @Autowired
    private MenuInventoryAdminCommandService adminCommandService;

    @Autowired
    private MenuInventoryPolicyAuditRepository policyAuditRepository;

    @MockitoSpyBean
    private StoreService storeService;

    @MockitoSpyBean
    private MenuQueryService menuQueryService;

    @MockitoSpyBean
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
    private long operatorId;
    private long storeId;

    @BeforeEach
    void setUpMenu() {
        int sequence = SEQUENCE.incrementAndGet();
        menuId = transactionTemplate.execute(status -> {
            operatorId = operatorRepository.saveAndFlush(
                    StoreOperatorAccount.create(
                            "inventory-owner-" + sequence + "@example.com", "hashed", "owner"))
                    .getId();
            Store store = storeRepository.saveAndFlush(Store.create(
                    operatorId, String.format("%010d", sequence), BusinessType.CAFE, "store", "",
                    Region.SEOUL, "address", "CAFE_BAKERY", Set.of(),
                    true, true, true, "Asia/Seoul",
                    LocalDateTime.of(2026, 8, 1, 9, 0),
                    "STORE_ONBOARDING_REQUIRED_TERMS_V1"));
            storeId = store.getId();
            return menuRepository.saveAndFlush(Menu.create(
                    store.getId(), menuContent(), operatorId,
                    Instant.parse("2026-08-01T00:00:00Z"))).getId();
        });
    }

    @Test
    void repeatedCreateKeyReplaysWithoutDuplicateBucketOrAudit() {
        stubAdminContracts();
        long bucketCountBefore = bucketRepository.count();
        long auditCountBefore = policyAuditRepository.count();
        IdempotencyKey key = IdempotencyKey.parse(
                "123e4567-e89b-12d3-a456-426614174101");
        InventoryBucketCreateCommand request = createCommand(5);

        MenuInventoryCommandResult first = adminCommandService.create(
                operatorId, storeId, key, request);
        MenuInventoryCommandResult replay = adminCommandService.create(
                operatorId, storeId, key, request);

        assertThat(replay.data().inventoryBucketId())
                .isEqualTo(first.data().inventoryBucketId());
        assertThat(bucketRepository.count()).isEqualTo(bucketCountBefore + 1);
        assertThat(policyAuditRepository.count()).isEqualTo(auditCountBefore + 1);
    }

    @Test
    void reusedCreateKeyWithDifferentFingerprintIsRejected() {
        stubAdminContracts();
        IdempotencyKey key = IdempotencyKey.parse(
                "123e4567-e89b-12d3-a456-426614174102");
        adminCommandService.create(operatorId, storeId, key, createCommand(5));

        assertThatThrownBy(() -> adminCommandService.create(
                operatorId, storeId, key, createCommand(6)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void repeatedUpdateKeyReplaysWithoutAnotherPolicyOrAudit() {
        stubAdminContracts();
        MenuInventoryBucket current = transactionTemplate.execute(status ->
                bucketRepository.saveAndFlush(bucket(menuId, 1L, 5, 3)));
        long bucketCountBefore = bucketRepository.count();
        long auditCountBefore = policyAuditRepository.count();
        IdempotencyKey key = IdempotencyKey.parse(
                "123e4567-e89b-12d3-a456-426614174103");

        MenuInventoryCommandResult first = adminCommandService.update(
                operatorId, storeId, current.getId(), key, policyChange());
        MenuInventoryCommandResult replay = adminCommandService.update(
                operatorId, storeId, current.getId(), key, policyChange());

        assertThat(replay.data().inventoryBucketId())
                .isEqualTo(first.data().inventoryBucketId());
        assertThat(bucketRepository.count()).isEqualTo(bucketCountBefore + 1);
        assertThat(policyAuditRepository.count()).isEqualTo(auditCountBefore + 1);
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
    void restoringAnOlderPolicyAllocationAlsoRestoresTheCurrentPolicy() {
        MenuInventoryBucket first = transactionTemplate.execute(status ->
                bucketRepository.saveAndFlush(bucket(menuId, 1L, 5, 0)));
        InventoryAcquireRequest acquire = new InventoryAcquireRequest(
                "reservation:policy-restore:create",
                List.of(selection(menuId, 2)));
        transactionTemplate.execute(status -> menuHoldService.acquireInventory(acquire));

        MenuInventoryBucket second = policyService.publishNextPolicy(
                7L,
                "MENU_INVENTORY_UPDATE",
                "123e4567-e89b-12d3-a456-426614174110",
                first.getId(),
                new InventoryPolicyChange(
                        5, 5, 0, 0, true,
                        com.miriyum.domain.menuhold.inventory.model
                                .InventoryAvailabilityStatus.AVAILABLE));

        transactionTemplate.executeWithoutResult(status -> menuHoldService.restoreInventory(
                new InventoryRestoreRequest(
                        "reservation:policy-restore:cancel",
                        acquire.operationId())));

        MenuInventoryBucket restoredFirst = bucketRepository.findById(first.getId()).orElseThrow();
        MenuInventoryBucket restoredCurrent = bucketRepository.findById(second.getId()).orElseThrow();
        assertThat(restoredFirst.getOnlineHoldRemaining()).isEqualTo(5);
        assertThat(restoredCurrent.getOnlineHoldRemaining()).isEqualTo(5);
        assertThat(ledgerRepository.findAll().stream()
                .filter(event -> event.getOperationType()
                        == InventoryLedgerOperation.RESTORE)
                .filter(event -> acquire.operationId().equals(
                        event.getSourceOperationId()))
                .mapToInt(event -> event.getQuantityDelta())
                .sum()).isEqualTo(2);
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
    void locksAndReturnsTheHighestPolicyVersionForAnInterval() {
        transactionTemplate.executeWithoutResult(status -> {
            bucketRepository.saveAndFlush(bucket(menuId, 1L, 2, 0));
            bucketRepository.saveAndFlush(bucket(menuId, 2L, 3, 0));
        });

        MenuInventoryBucket current = transactionTemplate.execute(status ->
                bucketRepository.findCurrentForUpdate(
                                menuId,
                                LocalDate.of(2026, 8, 10),
                                LocalTime.of(12, 0),
                                LocalDate.of(2026, 8, 10),
                                LocalTime.of(13, 0))
                        .orElseThrow());

        assertThat(current.getInventoryPolicyVersion()).isEqualTo(2L);
        assertThat(current.getOnlineHoldCapacity()).isEqualTo(3);
    }

    @Test
    void rejectsAcquisitionAgainstAnOlderPolicyVersion() {
        MenuInventoryBucket first = transactionTemplate.execute(status ->
                bucketRepository.saveAndFlush(bucket(menuId, 1L, 5, 0)));
        MenuInventoryBucket second = policyService.publishNextPolicy(
                7L,
                "MENU_INVENTORY_UPDATE",
                "123e4567-e89b-12d3-a456-426614174111",
                first.getId(),
                new InventoryPolicyChange(
                        5, 5, 0, 0, true,
                        com.miriyum.domain.menuhold.inventory.model
                                .InventoryAvailabilityStatus.AVAILABLE));
        InventoryAcquireRequest staleAcquire = new InventoryAcquireRequest(
                "reservation:stale-policy:create",
                List.of(selection(menuId, 1L, 5)));

        assertThatThrownBy(() -> transactionTemplate.execute(status ->
                menuHoldService.acquireInventory(staleAcquire)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);

        assertThat(bucketRepository.findById(first.getId()).orElseThrow()
                .getOnlineHoldRemaining()).isEqualTo(5);
        assertThat(bucketRepository.findById(second.getId()).orElseThrow()
                .getOnlineHoldRemaining()).isEqualTo(5);
    }

    @Test
    void crossedStaleMultiMenuAcquisitionsAreBothRejectedAsPolicyConflicts()
            throws Exception {
        long secondMenuId = transactionTemplate.execute(status -> menuRepository.saveAndFlush(
                Menu.create(
                        storeRepository.findAll().getFirst().getId(),
                        menuContent(),
                        operatorRepository.findAll().getFirst().getId(),
                        Instant.parse("2026-08-01T00:00:02Z"))).getId());
        transactionTemplate.executeWithoutResult(status -> {
            bucketRepository.saveAndFlush(bucket(menuId, 1L, 5, 0));
            bucketRepository.saveAndFlush(bucket(secondMenuId, 1L, 5, 0));
            bucketRepository.saveAndFlush(bucket(secondMenuId, 2L, 5, 0));
            bucketRepository.saveAndFlush(bucket(menuId, 2L, 5, 0));
        });
        InventoryAcquireRequest first = new InventoryAcquireRequest(
                "reservation:crossed-stale:first",
                List.of(selection(menuId, 1L, 1),
                        selection(secondMenuId, 2L, 1)));
        InventoryAcquireRequest second = new InventoryAcquireRequest(
                "reservation:crossed-stale:second",
                List.of(selection(secondMenuId, 1L, 1),
                        selection(menuId, 2L, 1)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> firstResult = executor.submit(() ->
                    acquireConcurrently(first, ready, start));
            Future<Object> secondResult = executor.submit(() ->
                    acquireConcurrently(second, ready, start));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    firstResult.get(20, TimeUnit.SECONDS),
                    secondResult.get(20, TimeUnit.SECONDS)))
                    .containsOnly(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        }
    }

    @Test
    void olderPolicyMultiMenuRestoreAndCurrentAcquireCompleteWithoutDeadlock()
            throws Exception {
        long secondMenuId = transactionTemplate.execute(status -> menuRepository.saveAndFlush(
                Menu.create(
                        storeRepository.findAll().getFirst().getId(),
                        menuContent(),
                        operatorRepository.findAll().getFirst().getId(),
                        Instant.parse("2026-08-01T00:00:03Z"))).getId());
        List<MenuInventoryBucket> originals = transactionTemplate.execute(status -> List.of(
                bucketRepository.saveAndFlush(bucket(menuId, 1L, 5, 0)),
                bucketRepository.saveAndFlush(bucket(secondMenuId, 1L, 5, 0))));
        InventoryAcquireRequest originalAcquire = new InventoryAcquireRequest(
                "reservation:restore-current-lock:create",
                List.of(selection(menuId, 1L, 1), selection(secondMenuId, 1L, 1)));
        transactionTemplate.execute(status -> menuHoldService.acquireInventory(originalAcquire));
        List<MenuInventoryBucket> currents = transactionTemplate.execute(status -> List.of(
                bucketRepository.saveAndFlush(bucket(secondMenuId, 2L, 4, 0)),
                bucketRepository.saveAndFlush(bucket(menuId, 2L, 4, 0))));
        InventoryAcquireRequest currentAcquire = new InventoryAcquireRequest(
                "reservation:restore-current-lock:latest",
                List.of(selection(menuId, 2L, 1), selection(secondMenuId, 2L, 1)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> restoreResult = executor.submit(() ->
                    restoreConcurrently(
                            "reservation:restore-current-lock:cancel",
                            originalAcquire.operationId(), ready, start));
            Future<Object> acquireResult = executor.submit(() ->
                    acquireConcurrently(currentAcquire, ready, start));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(restoreResult.get(20, TimeUnit.SECONDS)).isEqualTo(Boolean.TRUE);
            assertThat(acquireResult.get(20, TimeUnit.SECONDS)).isNull();
        }

        assertThat(originals)
                .allSatisfy(bucket -> assertThat(bucketRepository.findById(bucket.getId())
                        .orElseThrow().getOnlineHoldRemaining()).isEqualTo(5));
        assertThat(currents)
                .allSatisfy(bucket -> assertThat(bucketRepository.findById(bucket.getId())
                        .orElseThrow().getOnlineHoldRemaining()).isEqualTo(4));
    }

    @Test
    void listsOnlyCurrentPoliciesForTheManagedMenuIds() {
        transactionTemplate.executeWithoutResult(status -> {
            bucketRepository.saveAndFlush(bucket(menuId, 1L, 2, 0));
            bucketRepository.saveAndFlush(bucket(menuId, 2L, 3, 0));
        });

        var page = bucketRepository.findCurrentPage(
                List.of(menuId),
                LocalDate.of(2026, 8, 10),
                menuId,
                PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().getInventoryPolicyVersion())
                .isEqualTo(2L);
    }

    @Test
    void publishesNextPolicyAndAuditInOneTransaction() {
        MenuInventoryBucket current = transactionTemplate.execute(status ->
                bucketRepository.saveAndFlush(bucket(menuId, 1L, 5, 3)));
        long auditCountBefore = policyAuditRepository.count();

        MenuInventoryBucket next = policyService.publishNextPolicy(
                7L,
                "MENU_INVENTORY_UPDATE",
                "123e4567-e89b-12d3-a456-426614174000",
                current.getId(),
                new InventoryPolicyChange(
                        12, 6, 2, 4, true,
                        com.miriyum.domain.menuhold.inventory.model
                                .InventoryAvailabilityStatus.SOLD_OUT));

        assertThat(next.getInventoryPolicyVersion()).isEqualTo(2L);
        assertThat(bucketRepository.findById(current.getId()).orElseThrow()
                .getInventoryPolicyVersion()).isEqualTo(1L);
        assertThat(policyAuditRepository.count()).isEqualTo(auditCountBefore + 1);
        assertThat(policyAuditRepository.findAll().getLast().getBucketId())
                .isEqualTo(next.getId());
    }

    @Test
    void concurrentUpdatesPublishOnlyOneNextPolicyAndAudit() throws Exception {
        MenuInventoryBucket current = transactionTemplate.execute(status ->
                bucketRepository.saveAndFlush(bucket(menuId, 1L, 5, 3)));
        long bucketCountBefore = bucketRepository.count();
        long auditCountBefore = policyAuditRepository.count();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> publishConcurrently(
                    current.getId(),
                    "123e4567-e89b-12d3-a456-426614174001", ready, start));
            Future<Boolean> second = executor.submit(() -> publishConcurrently(
                    current.getId(),
                    "123e4567-e89b-12d3-a456-426614174002", ready, start));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }

        assertThat(bucketRepository.count()).isEqualTo(bucketCountBefore + 1);
        assertThat(policyAuditRepository.count()).isEqualTo(auditCountBefore + 1);
    }

    @Test
    void auditConflictRollsBackTheNewPolicyVersion() {
        MenuInventoryBucket first = transactionTemplate.execute(status ->
                bucketRepository.saveAndFlush(bucket(menuId, 1L, 5, 3)));
        String key = "123e4567-e89b-12d3-a456-426614174003";
        MenuInventoryBucket second = policyService.publishNextPolicy(
                7L, "MENU_INVENTORY_UPDATE", key, first.getId(),
                policyChange());
        long bucketCountBefore = bucketRepository.count();
        long auditCountBefore = policyAuditRepository.count();

        assertThatThrownBy(() -> policyService.publishNextPolicy(
                7L, "MENU_INVENTORY_UPDATE", key, second.getId(),
                policyChange()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(bucketRepository.count()).isEqualTo(bucketCountBefore);
        assertThat(policyAuditRepository.count()).isEqualTo(auditCountBefore);
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

        CountDownLatch bothObservedNoRestore = new CountDownLatch(2);
        CountDownLatch allowBucketLock = new CountDownLatch(1);
        willAnswer(invocation -> {
            boolean exists = (Boolean) invocation.callRealMethod();
            if (!exists) {
                bothObservedNoRestore.countDown();
                if (!allowBucketLock.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("restore race barrier timed out");
                }
            }
            return exists;
        }).given(ledgerRepository).existsRestoreForSourceOperation(acquire.operationId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> restoreConcurrently(
                    "reservation:99:cancel:1", acquire.operationId()));
            Future<Boolean> second = executor.submit(() -> restoreConcurrently(
                    "reservation:99:cancel:2", acquire.operationId()));

            assertThat(bothObservedNoRestore.await(10, TimeUnit.SECONDS)).isTrue();
            allowBucketLock.countDown();

            assertThat(first.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(20, TimeUnit.SECONDS)).isTrue();
        }

        MenuInventoryBucket restored = bucketRepository.findById(bucket.getId()).orElseThrow();
        assertThat(restored.getOnlineHoldRemaining()).isEqualTo(2);
        assertThat(ledgerRepository.count()).isEqualTo(ledgerCountBefore + 2);
    }

    private boolean restoreConcurrently(
            String operationId,
            String sourceOperationId
    ) {
        try {
            transactionTemplate.executeWithoutResult(status -> menuHoldService.restoreInventory(
                    new InventoryRestoreRequest(operationId, sourceOperationId)));
            return true;
        } catch (ServiceException exception) {
            return false;
        }
    }

    private static InventoryAcquireRequest.Selection selection(long selectedMenuId, int quantity) {
        return selection(selectedMenuId, 1L, quantity);
    }

    private Object acquireConcurrently(
            InventoryAcquireRequest request,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                return AssertionError.class;
            }
            transactionTemplate.execute(status -> menuHoldService.acquireInventory(request));
            return null;
        } catch (ServiceException exception) {
            return exception.getErrorCode();
        } catch (RuntimeException exception) {
            return exception.getClass();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return InterruptedException.class;
        }
    }

    private Object restoreConcurrently(
            String operationId,
            String sourceOperationId,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                return AssertionError.class;
            }
            transactionTemplate.executeWithoutResult(status -> menuHoldService.restoreInventory(
                    new InventoryRestoreRequest(operationId, sourceOperationId)));
            return Boolean.TRUE;
        } catch (ServiceException exception) {
            return exception.getErrorCode();
        } catch (RuntimeException exception) {
            return exception.getClass();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return InterruptedException.class;
        }
    }

    private static InventoryAcquireRequest.Selection selection(
            long selectedMenuId,
            long policyVersion,
            int quantity
    ) {
        return new InventoryAcquireRequest.Selection(
                selectedMenuId,
                LocalDate.of(2026, 8, 10),
                LocalTime.of(12, 0),
                LocalDate.of(2026, 8, 10),
                LocalTime.of(13, 0),
                policyVersion,
                quantity);
    }

    private static MenuInventoryBucket bucket(long selectedMenuId, int online, int shared) {
        return bucket(selectedMenuId, 1L, online, shared);
    }

    private boolean publishConcurrently(
            long bucketId,
            String key,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                return false;
            }
            policyService.publishNextPolicy(
                    7L, "MENU_INVENTORY_UPDATE", key, bucketId,
                    policyChange());
            return true;
        } catch (ServiceException exception) {
            if (exception.getErrorCode()
                    == MenuHoldErrorCode.INVENTORY_STATE_CONFLICT) {
                return false;
            }
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static InventoryPolicyChange policyChange() {
        return new InventoryPolicyChange(
                12, 6, 2, 4, true,
                com.miriyum.domain.menuhold.inventory.model
                        .InventoryAvailabilityStatus.AVAILABLE);
    }

    private void stubAdminContracts() {
        ManagedMenuResponse menu = new ManagedMenuResponse(
                String.valueOf(menuId), String.valueOf(storeId),
                MenuVisibility.VISIBLE, MenuSellingStatus.SELLING,
                false, null, null, null);
        willReturn(menu).given(menuQueryService)
                .get(operatorId, storeId, menuId);
        willReturn(new StoreScheduleAuthority(storeId, "Asia/Seoul"))
                .given(storeService)
                .requireSchedulePublicationAuthority(operatorId, storeId);
        willReturn(new MenuTransactionEligibility(
                storeId, menuId, 1, "Americano", 5_000, true, false))
                .given(storeService)
                .requireMenuTransactionEligibility(storeId, menuId);
    }

    private InventoryBucketCreateCommand createCommand(int totalSupply) {
        return new InventoryBucketCreateCommand(
                menuId,
                LocalDate.of(2026, 8, 12),
                LocalTime.of(12, 0),
                LocalDate.of(2026, 8, 12),
                LocalTime.of(13, 0),
                totalSupply,
                3,
                1,
                1,
                true,
                com.miriyum.domain.menuhold.inventory.model
                        .InventoryAvailabilityStatus.AVAILABLE);
    }

    private static MenuInventoryBucket bucket(
            long selectedMenuId,
            long policyVersion,
            int online,
            int shared
    ) {
        return MenuInventoryBucket.create(
                selectedMenuId,
                LocalDate.of(2026, 8, 10),
                LocalTime.of(12, 0),
                LocalDate.of(2026, 8, 10),
                LocalTime.of(13, 0),
                "Asia/Seoul",
                policyVersion,
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
