package com.miriyum.global.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
@SpringBootTest(
        properties = {
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.jwt.issuer=miriyum"
        })
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
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS idempotency_business_probe "
                        + "(probe_id BIGINT NOT NULL PRIMARY KEY)");
        jdbcTemplate.execute("TRUNCATE TABLE idempotency_commands");
        jdbcTemplate.execute("TRUNCATE TABLE idempotency_business_probe");
        runner.resetCallbackCount();
    }

    @Test
    @DisplayName("업무 유일키에 유일 제약이 걸려 있다")
    void uniqueConstraintOnBusinessKey() {
        // given
        insertRaw(FINGERPRINT, IdempotencyStatus.SUCCEEDED);

        // when & then
        assertThatThrownBy(() -> insertRaw("b".repeat(64), IdempotencyStatus.SUCCEEDED))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("신규 선점은 콜백을 1회 실행하고 SUCCEEDED로 확정한다")
    void fresh_runsCallbackOnceAndSucceeds() {
        // when
        IdempotentOutcome outcome = runner.run(command(FINGERPRINT), result());

        // then
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
        // when
        IdempotentOutcome first = runner.run(command(FINGERPRINT), result());
        IdempotentOutcome second = runner.run(command(FINGERPRINT), result());

        // then
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.payloadJson()).isEqualTo(first.payloadJson());
        assertThat(second.httpStatus()).isEqualTo(201);
        assertThat(runner.callbackCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 키·다른 지문은 COMMON_007로 거절하고 기존 결과를 바꾸지 않는다")
    void sameKeyDifferentFingerprint_conflicts() {
        // given
        runner.run(command(FINGERPRINT), result());

        // when & then
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
        // when & then
        assertThatThrownBy(() -> runner.runThrowing(command(FINGERPRINT)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        assertThat(rowCount()).isZero();
        assertThat(businessRowCount()).isZero();
    }

    @Test
    @DisplayName("잘못된 업무 결과는 업무 변경과 멱등 선점을 함께 롤백한다")
    void invalidBusinessResult_rollsBackEverything() {
        // when & then
        assertThatThrownBy(() -> runner.runWithInvalidResult(command(FINGERPRINT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("httpStatus");

        assertThat(rowCount()).isZero();
        assertThat(businessRowCount()).isZero();
    }

    @Test
    @DisplayName("트랜잭션 없이 호출하면 MANDATORY로 거부한다")
    void withoutTransaction_rejected() {
        // when & then
        assertThatThrownBy(() -> runner.runWithoutTransaction(command(FINGERPRINT), result()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("커밋된 PROCESSING 발견은 불변식 위반으로 콜백 없이 실패한다")
    void committedProcessing_invariantViolation() {
        // given
        insertRaw(FINGERPRINT, IdempotencyStatus.PROCESSING);

        // when & then
        assertThatThrownBy(() -> runner.run(command(FINGERPRINT), result()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(runner.callbackCount()).isZero();
    }

    @Test
    @DisplayName("결과가 누락된 SUCCEEDED 발견은 불변식 위반으로 실패한다")
    void succeededWithoutResult_invariantViolation() {
        // given
        insertRaw(FINGERPRINT, IdempotencyStatus.SUCCEEDED);

        // when & then
        assertThatThrownBy(() -> runner.run(command(FINGERPRINT), result()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(runner.callbackCount()).isZero();
    }

    @Test
    @DisplayName("동시 같은 키·지문은 콜백 1회, 두 호출 모두 동일 결과를 반환한다")
    void concurrentSameKey_runsCallbackOnce() throws Exception {
        // given
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch winnerEnteredCallback = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        List<IdempotentOutcome> outcomes;

        try {
            Future<IdempotentOutcome> winner = pool.submit(
                    () -> runner.runHolding(
                            command(FINGERPRINT), result(), winnerEnteredCallback, releaseWinner));
            if (!winnerEnteredCallback.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("선점 트랜잭션이 업무 콜백에 진입하지 못했습니다.");
            }

            Future<IdempotentOutcome> contender = pool.submit(
                    () -> runner.runSignallingStart(
                            command(FINGERPRINT), result(), contenderStarted));
            if (!contenderStarted.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("경합 트랜잭션이 시작되지 못했습니다.");
            }

            // when & then
            assertThatThrownBy(() -> contender.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseWinner.countDown();
            outcomes = List.of(
                    winner.get(30, TimeUnit.SECONDS),
                    contender.get(30, TimeUnit.SECONDS));
        } finally {
            releaseWinner.countDown();
            pool.shutdownNow();
        }

        // then
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
        jdbcTemplate.update(
                "INSERT INTO idempotency_commands "
                        + "(principal_namespace, principal_id, command_type, idempotency_key, request_fingerprint, "
                        + "processing_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                NS, PRINCIPAL_ID, COMMAND, KEY, fingerprint, statusValue.name());
    }

    private String status() {
        return jdbcTemplate.queryForObject(
                "SELECT processing_status FROM idempotency_commands", String.class);
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotency_commands", Integer.class);
    }

    private int businessRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_business_probe", Integer.class);
    }

    record TestData(String value) {
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        TestCommandRunner testCommandRunner(
                IdempotencyExecutor executor,
                JdbcTemplate jdbcTemplate
        ) {
            return new TestCommandRunner(executor, jdbcTemplate);
        }
    }

    static class TestCommandRunner {
        private final IdempotencyExecutor executor;
        private final JdbcTemplate jdbcTemplate;
        private final AtomicInteger callbackCount = new AtomicInteger();

        TestCommandRunner(IdempotencyExecutor executor, JdbcTemplate jdbcTemplate) {
            this.executor = executor;
            this.jdbcTemplate = jdbcTemplate;
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
                jdbcTemplate.update(
                        "INSERT INTO idempotency_business_probe (probe_id) VALUES (1)");
                throw new RuntimeException("boom");
            });
        }

        @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
        void runWithInvalidResult(IdempotencyCommand command) {
            executor.execute(command, () -> {
                callbackCount.incrementAndGet();
                jdbcTemplate.update(
                        "INSERT INTO idempotency_business_probe (probe_id) VALUES (2)");
                return new BusinessResult<>(500, "SUCCESS", "STORE", "store-1", null);
            });
        }

        @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
        IdempotentOutcome runHolding(
                IdempotencyCommand command,
                BusinessResult<TestData> result,
                CountDownLatch callbackEntered,
                CountDownLatch releaseCallback
        ) {
            return executor.execute(command, () -> {
                callbackCount.incrementAndGet();
                callbackEntered.countDown();
                await(releaseCallback, "선점 트랜잭션 해제 신호를 받지 못했습니다.");
                return result;
            });
        }

        @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
        IdempotentOutcome runSignallingStart(
                IdempotencyCommand command,
                BusinessResult<TestData> result,
                CountDownLatch started
        ) {
            started.countDown();
            return executor.execute(command, () -> {
                callbackCount.incrementAndGet();
                return result;
            });
        }

        IdempotentOutcome runWithoutTransaction(IdempotencyCommand command, BusinessResult<TestData> result) {
            return executor.execute(command, () -> result);
        }

        private static void await(CountDownLatch latch, String timeoutMessage) {
            try {
                if (!latch.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(timeoutMessage);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("동시성 테스트 대기가 중단되었습니다.", e);
            }
        }
    }
}
