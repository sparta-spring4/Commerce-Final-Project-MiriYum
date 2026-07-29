package com.miriyum.global.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 MySQL에서 멱등 선점·재생·충돌·롤백·MANDATORY·동시성을 검증한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Import(IdempotencyExecutorIT.TestConfig.class)
class IdempotencyExecutorIT {

    private static final String NS = "STORE_OPERATOR";
    private static final long PRINCIPAL_ID = 42L;
    private static final String COMMAND = "STORE_REGISTER";
    private static final String KEY = "123e4567-e89b-12d3-a456-426614174000";
    private static final String FINGERPRINT = "a".repeat(64);

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.40"));

    @Autowired
    private TestCommandRunner runner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE TABLE idempotency_commands");
        runner.resetCallbackCount();
    }

    @Test
    @DisplayName("업무 유일키에 유일 제약이 걸려 있다")
    void uniqueConstraintOnBusinessKey() {
        insertRaw(FINGERPRINT, IdempotencyStatus.SUCCEEDED);
        assertThatThrownBy(() -> insertRaw("b".repeat(64), IdempotencyStatus.SUCCEEDED))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("신규 선점은 콜백을 1회 실행하고 SUCCEEDED로 확정한다")
    void fresh_runsCallbackOnceAndSucceeds() {
        IdempotentOutcome outcome = runner.run(command(FINGERPRINT), result());

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.httpStatus()).isEqualTo(201);
        assertThat(outcome.responseCode()).isEqualTo("SUCCESS");
        assertThat(outcome.payloadJson()).isEqualTo("{\"value\":\"hello\"}");
        assertThat(runner.callbackCount()).isEqualTo(1);
        assertThat(status()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("같은 키·지문 재요청은 콜백 없이 저장된 결과를 재생한다")
    void sameKeySameFingerprint_replays() {
        IdempotentOutcome first = runner.run(command(FINGERPRINT), result());
        IdempotentOutcome second = runner.run(command(FINGERPRINT), result());

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.payloadJson()).isEqualTo(first.payloadJson());
        assertThat(second.httpStatus()).isEqualTo(201);
        assertThat(runner.callbackCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 키·다른 지문은 COMMON_007로 거절하고 기존 결과를 바꾸지 않는다")
    void sameKeyDifferentFingerprint_conflicts() {
        runner.run(command(FINGERPRINT), result());

        assertThatThrownBy(() -> runner.run(command("c".repeat(64)), result()))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);

        assertThat(runner.callbackCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT request_fingerprint FROM idempotency_commands", String.class))
                .isEqualTo(FINGERPRINT);
    }

    @Test
    @DisplayName("업무 콜백 예외는 멱등 기록까지 전체 롤백한다")
    void businessFailure_rollsBackEverything() {
        assertThatThrownBy(() -> runner.runThrowing(command(FINGERPRINT)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("트랜잭션 없이 호출하면 MANDATORY로 거부한다")
    void withoutTransaction_rejected() {
        assertThatThrownBy(() -> runner.runWithoutTransaction(command(FINGERPRINT), result()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("커밋된 PROCESSING 발견은 불변식 위반으로 콜백 없이 실패한다")
    void committedProcessing_invariantViolation() {
        insertRaw(FINGERPRINT, IdempotencyStatus.PROCESSING);

        assertThatThrownBy(() -> runner.run(command(FINGERPRINT), result()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(runner.callbackCount()).isZero();
    }

    @Test
    @DisplayName("동시 같은 키·지문은 콜백 1회, 두 호출 모두 동일 결과를 반환한다")
    void concurrentSameKey_runsCallbackOnce() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<IdempotentOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                outcomes.add(runner.run(command(FINGERPRINT), result()));
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(runner.callbackCount()).isEqualTo(1);
        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).filteredOn(o -> !o.replayed()).hasSize(1);
        assertThat(outcomes).allSatisfy(o -> {
            assertThat(o.responseCode()).isEqualTo("SUCCESS");
            assertThat(o.payloadJson()).isEqualTo("{\"value\":\"hello\"}");
        });
    }

    private IdempotencyCommand command(String fingerprint) {
        return new IdempotencyCommand(NS, PRINCIPAL_ID, COMMAND, KEY, fingerprint);
    }

    private BusinessResult<TestData> result() {
        return new BusinessResult<>(201, "SUCCESS", "STORE", "store-1", new TestData("hello"));
    }

    private void insertRaw(String fingerprint, IdempotencyStatus statusValue) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO idempotency_commands "
                        + "(principal_namespace, principal_id, command_type, idempotency_key, request_fingerprint, "
                        + "processing_status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                NS, PRINCIPAL_ID, COMMAND, KEY, fingerprint, statusValue.name(), now, now);
    }

    private String status() {
        return jdbcTemplate.queryForObject(
                "SELECT processing_status FROM idempotency_commands", String.class);
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotency_commands", Integer.class);
    }

    record TestData(String value) {
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        TestCommandRunner testCommandRunner(IdempotencyExecutor executor) {
            return new TestCommandRunner(executor);
        }
    }

    static class TestCommandRunner {
        private final IdempotencyExecutor executor;
        private final AtomicInteger callbackCount = new AtomicInteger();

        TestCommandRunner(IdempotencyExecutor executor) {
            this.executor = executor;
        }

        // 프록시 경유 메서드 호출로 target의 카운터에 접근한다(필드 직접 접근은 프록시에서 null).
        int callbackCount() {
            return callbackCount.get();
        }

        void resetCallbackCount() {
            callbackCount.set(0);
        }

        @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
        IdempotentOutcome run(IdempotencyCommand command, BusinessResult<TestData> result) {
            return executor.execute(command, () -> {
                callbackCount.incrementAndGet();
                return result;
            });
        }

        @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
        void runThrowing(IdempotencyCommand command) {
            executor.execute(command, () -> {
                callbackCount.incrementAndGet();
                throw new RuntimeException("boom");
            });
        }

        IdempotentOutcome runWithoutTransaction(IdempotencyCommand command, BusinessResult<TestData> result) {
            return executor.execute(command, () -> result);
        }
    }
}
