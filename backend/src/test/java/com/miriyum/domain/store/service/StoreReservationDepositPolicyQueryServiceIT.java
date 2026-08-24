package com.miriyum.domain.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.store.dto.contract.StoreReservationDepositPolicy;
import com.miriyum.domain.store.dto.contract.StoreReservationDepositPolicy.Status;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.store.repository.StoreReservationDepositPolicyRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import java.time.LocalDateTime;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Tag("integration-shard-c")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.task.scheduling.enabled=false",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.reservation.hold-expiration.enabled=false",
            "miriyum.store.schedule.activation-enabled=false"
        })
class StoreReservationDepositPolicyQueryServiceIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private StoreReservationDepositPolicyQueryService queryService;

    @Autowired
    private StoreTransactionEligibilityService eligibilityService;

    @Autowired
    private StoreReservationDepositPolicyRepository policyRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanRows() {
        policyRepository.deleteAll();
        storeRepository.deleteAll();
        operatorRepository.deleteAll();
    }

    @Test
    @DisplayName("caller transaction 없이 현재 예약금 정책을 조회할 수 없다")
    void rejectsInvocationWithoutCallerTransaction() {
        long storeId = createStore();

        assertThatThrownBy(() -> queryService.getCurrent(storeId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("Store 잠금 뒤 같은 caller transaction에서 현재 정책을 조회한다")
    void joinsTransactionAfterStoreSerializationLock() {
        long storeId = createStore();
        transactionTemplate.executeWithoutResult(ignored -> policyRepository.saveAndFlush(
                com.miriyum.domain.store.entity.StoreReservationDepositPolicy.create(
                        storeId, true, 20)));

        StoreReservationDepositPolicy result = transactionTemplate.execute(ignored -> {
            eligibilityService.requireReservationTransactionEligibility(storeId);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return queryService.getCurrent(storeId);
        });

        assertThat(result).isEqualTo(new StoreReservationDepositPolicy(
                storeId, Status.ENABLED, OptionalInt.of(20), OptionalLong.of(1L)));
    }

    private long createStore() {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create(
                        "deposit-query@example.com",
                        "hashed",
                        "운영자")).getId();
        return storeRepository.saveAndFlush(Store.create(
                operatorId,
                "1234567890",
                "예약금 조회 매장",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                Set.of(),
                true,
                true,
                true,
                "Asia/Seoul",
                LocalDateTime.of(2026, 8, 14, 12, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
    }
}
