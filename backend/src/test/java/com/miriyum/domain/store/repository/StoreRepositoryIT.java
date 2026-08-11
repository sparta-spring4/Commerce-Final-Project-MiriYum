package com.miriyum.domain.store.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.store.dto.storeoperator.StoreUpdateRequest;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.service.StoreCommandResult;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class StoreRepositoryIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository storeOperatorAccountRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoSpyBean
    private IdempotencyExecutor idempotencyExecutor;

    @Autowired
    private StoreService storeService;

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.execute("DELETE FROM idempotency_commands");
        jdbcTemplate.execute("DELETE FROM store_tag_assignment");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        entityManager.clear();
    }

    @Test
    @Transactional
    @DisplayName("Flyway 스키마와 JPA 매핑으로 매장과 태그를 저장한다")
    void storesStoreAndTagsWithFlywaySchema() {
        long operatorId = createOperator("owner@example.com");

        Store saved = storeRepository.saveAndFlush(store(
                operatorId, "1234567890", Set.of("DATE", "QUIET")));
        entityManager.clear();

        Store found = storeRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getTagCodes()).containsExactlyInAnyOrder("DATE", "QUIET");
        assertThat(found.getApplicantSelfAttestedAt())
                .isEqualTo(LocalDateTime.of(2026, 7, 31, 12, 0));
        assertThat(found.getRequiredTermsAgreedAt())
                .isEqualTo(LocalDateTime.of(2026, 7, 31, 12, 0));
        assertThat(found.getRequiredTermsVersion())
                .isEqualTo("STORE_ONBOARDING_REQUIRED_TERMS_V1");
    }

    @Test
    @DisplayName("같은 활성 사업자등록번호는 DB 유일 제약으로 한 건만 저장된다")
    void duplicateActiveBusinessNumberIsRejected() {
        long firstOperator = createOperator("first@example.com");
        long secondOperator = createOperator("second@example.com");
        storeRepository.saveAndFlush(store(firstOperator, "1234567890", Set.of()));

        assertThatThrownBy(() ->
                storeRepository.saveAndFlush(store(secondOperator, "1234567890", Set.of())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_stores_active_business_number");
    }

    @Test
    @DisplayName("폐점된 매장의 사업자등록번호도 일반 등록 경로에서 재귀속할 수 없다")
    void closedStoreKeepsBusinessNumberOwnership() {
        long firstOperator = createOperator("first@example.com");
        long secondOperator = createOperator("second@example.com");
        Store closed = storeRepository.saveAndFlush(
                store(firstOperator, "1234567890", Set.of()));
        closed.close();
        storeRepository.saveAndFlush(closed);

        assertThatThrownBy(() ->
                storeRepository.saveAndFlush(
                        store(secondOperator, "1234567890", Set.of())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_stores_active_business_number");
    }

    @Test
    @DisplayName("매장이 참조하는 운영자 계정은 연쇄 삭제되지 않는다")
    void referencedOperatorCannotBeDeleted() {
        long operatorId = createOperator("owner@example.com");
        storeRepository.saveAndFlush(store(operatorId, "1234567890", Set.of()));

        assertThatThrownBy(() -> {
            storeOperatorAccountRepository.deleteById(operatorId);
            storeOperatorAccountRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(storeRepository.count()).isOne();
    }

    @Test
    @DisplayName("승인 catalog에 없는 태그 코드는 DB 외래 키로 거부한다")
    void unknownTagCodeIsRejectedByForeignKey() {
        long operatorId = createOperator("owner@example.com");

        assertThatThrownBy(() ->
                storeRepository.saveAndFlush(store(
                        operatorId, "1234567890", Set.of("UNKNOWN_TAG"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("동시에 같은 사업자등록번호를 등록해도 활성 매장은 하나만 생성된다")
    void concurrentRegistrationCreatesExactlyOneActiveStore() throws Exception {
        long firstOperator = createOperator("first@example.com");
        long secondOperator = createOperator("second@example.com");
        CountDownLatch startGate = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<RegistrationResult> first = executor.submit(
                    () -> registerAfterGate(startGate, firstOperator));
            Future<RegistrationResult> second = executor.submit(
                    () -> registerAfterGate(startGate, secondOperator));

            startGate.countDown();
            List<RegistrationResult> results = List.of(first.get(), second.get());

            assertThat(results).filteredOn(RegistrationResult::success).hasSize(1);
            assertThat(results)
                    .filteredOn(result ->
                            result.failure() instanceof DataIntegrityViolationException)
                    .hasSize(1);
        }

        Integer activeCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM stores
                        WHERE business_registration_number = ?
                        """,
                Integer.class,
                "1234567890");
        assertThat(activeCount).isOne();
    }

    @Test
    @DisplayName("매장 등록 트랜잭션 실패 시 매장과 멱등 기록이 함께 롤백된다")
    void storeAndIdempotencyRecordRollBackTogether() {
        long operatorId = createOperator("owner@example.com");
        IdempotencyCommand command = new IdempotencyCommand(
                "store-operator",
                operatorId,
                "STORE_REGISTER",
                "550e8400-e29b-41d4-a716-446655440000",
                "a".repeat(64));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(ignored ->
                idempotencyExecutor.execute(command, () -> {
                    storeRepository.saveAndFlush(
                            store(operatorId, "1234567890", Set.of()));
                    throw new IllegalStateException("force rollback");
                })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        Integer storeCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM stores
                        WHERE business_registration_number = ?
                        """,
                Integer.class,
                "1234567890");
        Integer commandCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM idempotency_commands
                        WHERE command_type = 'STORE_REGISTER'
                        """,
                Integer.class);
        assertThat(storeCount).isZero();
        assertThat(commandCount).isZero();
    }

    @Test
    @DisplayName("서로 다른 필드의 동시 PATCH는 두 변경을 모두 보존한다")
    void concurrentDifferentFieldUpdatesPreserveBothChanges() throws Exception {
        long operatorId = createOperator("owner@example.com");
        Store saved = storeRepository.saveAndFlush(
                store(operatorId, "1234567890", Set.of()));
        long storeId = saved.getId();
        CountDownLatch bothAtIdempotencyBoundary = new CountDownLatch(2);
        IdempotencyExecutor target =
                AopTestUtils.getUltimateTargetObject(idempotencyExecutor);

        doAnswer(invocation -> {
            bothAtIdempotencyBoundary.countDown();
            if (!bothAtIdempotencyBoundary.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("두 PATCH가 멱등 실행 경계에 도달하지 못했습니다.");
            }
            return invocation.callRealMethod();
        }).when(target).execute(any(), any());

        StoreUpdateRequest rename = new StoreUpdateRequest(
                "새 이름", null, null, null, null, null, null, null);
        StoreUpdateRequest relocate = new StoreUpdateRequest(
                null, null, null, "새 주소", null, null, null, null);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<StoreCommandResult> first = executor.submit(() ->
                    storeService.update(
                            operatorId,
                            storeId,
                            IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440001"),
                            rename));
            Future<StoreCommandResult> second = executor.submit(() ->
                    storeService.update(
                            operatorId,
                            storeId,
                            IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440002"),
                            relocate));

            assertThat(first.get(10, TimeUnit.SECONDS).httpStatus()).isEqualTo(200);
            assertThat(second.get(10, TimeUnit.SECONDS).httpStatus()).isEqualTo(200);
        }

        entityManager.clear();
        Store updated = storeRepository.findById(storeId).orElseThrow();
        assertThat(updated.getName()).isEqualTo("새 이름");
        assertThat(updated.getAddress()).isEqualTo("새 주소");
    }

    private RegistrationResult registerAfterGate(
            CountDownLatch startGate,
            long operatorId
    ) throws InterruptedException {
        startGate.await();
        try {
            transactionTemplate.executeWithoutResult(ignored ->
                    storeRepository.saveAndFlush(
                            store(operatorId, "1234567890", Set.of())));
            return new RegistrationResult(true, null);
        } catch (RuntimeException exception) {
            return new RegistrationResult(false, exception);
        }
    }

    private long createOperator(String email) {
        return storeOperatorAccountRepository.saveAndFlush(
                StoreOperatorAccount.create(email, "hashed", "운영자")).getId();
    }

    private Store store(long operatorId, String businessNumber, Set<String> tags) {
        return Store.create(
                operatorId,
                businessNumber,
                BusinessType.CAFE,
                "미리윰",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                tags,
                true,
                true,
                true,
                "Asia/Seoul",
                LocalDateTime.of(2026, 7, 31, 12, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1");
    }

    private record RegistrationResult(
            boolean success,
            RuntimeException failure
    ) {
    }
}
