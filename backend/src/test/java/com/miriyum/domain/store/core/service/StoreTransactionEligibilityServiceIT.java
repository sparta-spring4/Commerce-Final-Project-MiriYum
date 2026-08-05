package com.miriyum.domain.store.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
            "miriyum.store.schedule.activation-enabled=false"
        })
class StoreTransactionEligibilityServiceIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add(
                "spring.datasource.hikari.connection-init-sql",
                () -> "SET SESSION innodb_lock_wait_timeout = 1");
    }

    @Autowired
    private StoreTransactionEligibilityService eligibilityService;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanRows() {
        storeRepository.deleteAll();
        operatorRepository.deleteAll();
    }

    @Test
    void reservationGateRejectsInvocationWithoutCreationTransaction() {
        long storeId = createStore();

        assertThatThrownBy(() ->
                eligibilityService.requireReservationTransactionEligibility(storeId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void pickupGateRejectsInvocationWithoutCreationTransaction() {
        long storeId = createStore();

        assertThatThrownBy(() ->
                eligibilityService.requirePickupTransactionEligibility(storeId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void reservationGateSerializesFollowingTerminalStateChange() throws Exception {
        long storeId = createStore();
        CountDownLatch gateLocked = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);
        CountDownLatch stateChangeAttempted = new CountDownLatch(1);
        CountDownLatch stateChangeLocked = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> gate = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(ignored -> {
                        eligibilityService.requireReservationTransactionEligibility(storeId);
                        gateLocked.countDown();
                        await(releaseGate);
                    }));
            Future<?> stateChange = executor.submit(() -> {
                await(gateLocked);
                transactionTemplate.executeWithoutResult(ignored -> {
                    stateChangeAttempted.countDown();
                    Store store = storeRepository.findByIdForUpdate(storeId).orElseThrow();
                    stateChangeLocked.countDown();
                    store.close();
                });
            });

            assertThat(gateLocked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(stateChangeAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(stateChangeLocked.await(300, TimeUnit.MILLISECONDS)).isFalse();
            } finally {
                releaseGate.countDown();
            }
            gate.get(5, TimeUnit.SECONDS);
            stateChange.get(5, TimeUnit.SECONDS);
        }

        assertThat(storeRepository.findById(storeId).orElseThrow().getOperationStatus())
                .isEqualTo(OperationStatus.CLOSED);
    }

    @Test
    void reservationGateRejectsStateChangeThatCommitsFirst() throws Exception {
        long storeId = createStore();
        CountDownLatch stateChangeLocked = new CountDownLatch(1);
        CountDownLatch releaseStateChange = new CountDownLatch(1);
        CountDownLatch gateAttempted = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> stateChange = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(ignored -> {
                        Store store = storeRepository.findByIdForUpdate(storeId).orElseThrow();
                        store.close();
                        stateChangeLocked.countDown();
                        await(releaseStateChange);
                    }));
            Future<ErrorCode> gate = executor.submit(() -> {
                await(stateChangeLocked);
                gateAttempted.countDown();
                try {
                    transactionTemplate.executeWithoutResult(ignored ->
                            eligibilityService.requireReservationTransactionEligibility(storeId));
                    return null;
                } catch (ServiceException exception) {
                    return exception.getErrorCode();
                }
            });

            assertThat(stateChangeLocked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(gateAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(gate.isDone()).isFalse();
            } finally {
                releaseStateChange.countDown();
            }
            stateChange.get(5, TimeUnit.SECONDS);
            assertThat(gate.get(5, TimeUnit.SECONDS))
                    .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT);
        }
    }

    private long createStore() {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create(
                        "transaction-gate@example.com",
                        "hashed",
                        "운영자")).getId();
        return storeRepository.saveAndFlush(Store.create(
                operatorId,
                "1234567890",
                BusinessType.CAFE,
                "거래 자격 매장",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                Set.of(),
                true,
                true,
                true,
                "Asia/Seoul",
                LocalDateTime.of(2026, 7, 31, 12, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
