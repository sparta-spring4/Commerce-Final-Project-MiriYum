package com.miriyum.domain.auth.logindelay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.jwt.TokenPair;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.consumer.service.ConsumerAuthService;
import com.miriyum.global.exception.ServiceException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies account-level login delay and attempt ownership with real MySQL.
 *
 * <p>Only one request may hold an account's short-lived password-comparison lease. Contending
 * requests must not reach BCrypt and are exposed as the same AUTH_005 response as all other
 * credential failures.</p>
 */
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            // 커넥션을 오래 쥐면 곧바로 드러나도록 풀을 작게 잡는다. 해시 비교가 트랜잭션 안에 있었다면
            // 서로 다른 계정 8건이 동시에 계산에 들어가 이 풀을 모두 점유했을 것이다.
            "spring.datasource.hikari.maximum-pool-size=2"
        })
class LoginDelayIntegrationTest {

    private static final String EMAIL = "delay-test@example.com";
    private static final String RAW_PASSWORD = "Password123!";
    private static final String WRONG_PASSWORD = "WrongPassword123!";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private ConsumerAuthService consumerAuthService;

    @Autowired
    private LoginDelayGuard loginDelayGuard;

    @Autowired
    private ConsumerAccountRepository consumerAccountRepository;

    /**
     * 지연이 걸린 뒤의 동시 요청이 해시 비교까지 도달하는지 세려면 실제 호출 횟수를 관찰해야 하므로
     * 실 구현을 감싼 spy를 쓴다.
     */
    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long accountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM login_failure_delays");
        consumerAccountRepository.deleteAll();
        consumerAccountRepository.flush();
        ConsumerAccount account = ConsumerAccount.create(
                EMAIL, passwordEncoder.encode(RAW_PASSWORD), "지연테스트");
        accountId = consumerAccountRepository.saveAndFlush(account).getId();
        // 준비 과정의 encode() 호출이 뒤의 matches() 검증에 섞이지 않게 비운다.
        clearInvocations(passwordEncoder);
    }

    @Test
    @DisplayName("연속 5회 실패하면 6번째 시도는 올바른 비밀번호여도 거절한다")
    void sixthAttemptIsRejectedEvenWithCorrectPassword() {
        // given: 연속 5회 실패
        failLogin(5);

        // when & then: 비밀번호가 맞아도 지연 중이라 거절된다
        assertThatThrownBy(() -> consumerAuthService.login(new LoginRequest(EMAIL, RAW_PASSWORD)))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);

        assertThat(delayStage()).isEqualTo(1);
    }

    @Test
    @DisplayName("4회까지는 지연 단계가 오르지 않는다")
    void doesNotDelayBeforeFifthFailure() {
        // given & when
        failLogin(4);

        // then
        assertThat(delayStage()).isZero();
        assertThat(consecutiveFailures()).isEqualTo(4);
    }

    @Test
    @DisplayName("지연 중 실패는 응답으로 지연 상태를 구분할 수 없게 AUTH_005로 동일하게 응답한다")
    void delayedResponseIsIndistinguishableFromNormalFailure() {
        // given: 지연 전 실패와 지연 후 실패의 오류 코드를 비교한다
        ServiceException beforeDelay = catchLoginFailure(EMAIL);
        failLogin(5);
        ServiceException whileDelayed = catchLoginFailure(EMAIL);

        // then: 존재하지 않는 계정의 실패까지 셋 다 같은 코드여야 한다
        ServiceException unknownAccount = catchLoginFailure("no-such-user@example.com");
        assertThat(beforeDelay.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
        assertThat(whileDelayed.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
        assertThat(unknownAccount.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("로그인에 성공하면 실패 횟수와 지연 단계를 초기화한다")
    void successfulLoginResetsFailureState() {
        // given: 지연 직전까지 실패를 쌓아둔 상태
        failLogin(4);
        assertThat(consecutiveFailures()).isEqualTo(4);

        // when
        consumerAuthService.login(new LoginRequest(EMAIL, RAW_PASSWORD));

        // then: 기록이 사라져 다음 실패는 처음부터 다시 센다
        assertThat(failureRowCount()).isZero();
    }

    @Test
    @DisplayName("같은 계정의 동시 성공 요청은 하나만 비교하고 나머지는 AUTH_005로 숨긴다")
    void concurrentSuccessfulLoginsDoNotCountAsFailures() throws Exception {
        // given: Hold BCrypt long enough for concurrent requests to observe the active lease.
        int threadCount = 6;
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<TokenPair>> futures = new ArrayList<>();
        boolean completedInTime;

        willAnswer(invocation -> {
            Thread.sleep(80);
            return invocation.callRealMethod();
        }).given(passwordEncoder).matches(eq(RAW_PASSWORD), anyString());

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            for (int attempt = 0; attempt < threadCount; attempt++) {
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    startLatch.await();
                    return consumerAuthService.login(new LoginRequest(EMAIL, RAW_PASSWORD));
                }));
            }

            readyLatch.await();
            startLatch.countDown();
            executor.shutdown();
            completedInTime = executor.awaitTermination(30, TimeUnit.SECONDS);
        }

        // then: One owner succeeds; contending requests are indistinguishable from credential failures.
        assertThat(completedInTime).as("동시 로그인 %d건이 30초 안에 끝나지 않았습니다", threadCount).isTrue();
        int successCount = 0;
        int busyCount = 0;
        for (Future<TokenPair> future : futures) {
            try {
                TokenPair tokenPair = future.get();
                assertThat(tokenPair.accessToken()).isNotBlank();
                assertThat(tokenPair.refreshToken()).isNotBlank();
                successCount++;
            } catch (ExecutionException exception) {
                assertThat(exception.getCause())
                        .isInstanceOf(ServiceException.class)
                        .extracting(cause -> ((ServiceException) cause).getErrorCode())
                        .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
                busyCount++;
            }
        }
        assertThat(successCount).isEqualTo(1);
        assertThat(busyCount).isEqualTo(threadCount - 1);
        assertThat(failureRowCount()).isZero();
    }

    @Test
    @DisplayName("비밀번호 비교가 예외로 중단되면 예약 시도는 실패로 남지 않는다")
    void passwordComparisonExceptionDoesNotLeaveReservedFailure() {
        // given: 비교 중 예외가 터져도 확정 실패가 아니므로 카운트가 남으면 안 된다
        AtomicBoolean firstFailure = new AtomicBoolean(true);
        willAnswer(invocation -> {
            if (firstFailure.getAndSet(false)) {
                throw new IllegalStateException("encoder failed");
            }
            return invocation.callRealMethod();
        }).given(passwordEncoder).matches(eq(WRONG_PASSWORD), anyString());

        // when & then
        assertThatThrownBy(() -> consumerAuthService.login(new LoginRequest(EMAIL, WRONG_PASSWORD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("encoder failed");

        assertThat(failureRowCount()).isZero();
        assertThat(catchLoginFailure(EMAIL).getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
        assertThat(consecutiveFailures()).isEqualTo(1);
    }

    @Test
    void returnsFalseWhenLeaseRowIsDeletedBeforeAttemptCompletion() {
        LoginAttempt attempt = loginDelayGuard.tryAcquireAttempt(TokenNamespace.CONSUMER, accountId);
        jdbcTemplate.update("DELETE FROM login_failure_delays WHERE account_id = ?", accountId);

        assertThat(loginDelayGuard.completeAttempt(
                TokenNamespace.CONSUMER, accountId, attempt, true)).isFalse();
    }

    @Test
    @DisplayName("비밀번호 해시 비교는 트랜잭션·DB 커넥션 밖에서 수행한다")
    void passwordComparisonRunsOutsideTransaction() {
        // given: 해시 비교가 실행되는 순간의 트랜잭션 활성 여부를 기록한다
        AtomicBoolean transactionActiveDuringComparison = new AtomicBoolean(true);
        willAnswer(invocation -> {
            transactionActiveDuringComparison.set(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).given(passwordEncoder).matches(any(), any());

        // when
        catchLoginFailure(EMAIL);

        // then: 해시 계산 동안 트랜잭션을 쥐고 있으면 DB 커넥션과 행 잠금도 함께 점유한다.
        // 서로 다른 계정을 동시에 시도하면 각 요청이 커넥션을 물고 나란히 계산에 들어가 풀이 고갈되고,
        // 인증과 무관한 DB 요청까지 지연된다. 그래서 비교는 반드시 트랜잭션 밖이어야 한다.
        assertThat(transactionActiveDuringComparison).isFalse();
    }

    @Test
    @DisplayName("서로 다른 계정으로 풀 크기 이상 동시 로그인해도 다른 DB 요청이 막히지 않는다")
    void concurrentLoginsOnDistinctAccountsDoNotStarveOtherQueries() throws Exception {
        // given: 커넥션 풀(테스트 설정 2개)보다 많은 계정으로 동시에 로그인 실패를 만든다
        int accountCount = 8;
        for (int index = 0; index < accountCount; index++) {
            consumerAccountRepository.saveAndFlush(ConsumerAccount.create(
                    "starve-" + index + "@example.com", passwordEncoder.encode(RAW_PASSWORD), "동시" + index));
        }
        CountDownLatch readyLatch = new CountDownLatch(accountCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> logins = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(accountCount)) {
            for (int index = 0; index < accountCount; index++) {
                String email = "starve-" + index + "@example.com";
                logins.add(executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        catchLoginFailure(email);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            readyLatch.await();
            startLatch.countDown();

            // when: 로그인들이 진행되는 동안 인증과 무관한 조회를 수행한다
            for (int probe = 0; probe < 20; probe++) {
                assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            }

            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS))
                    .as("동시 로그인 %d건이 30초 안에 끝나지 않았습니다", accountCount)
                    .isTrue();
        }

        // then: 모든 로그인이 예외 없이 끝나야 한다(커넥션 획득 실패는 여기서 드러난다)
        for (Future<?> login : logins) {
            login.get();
        }
    }

    @Test
    @DisplayName("동시에 실패해도 5회 기준이 우회되지 않고 실패 횟수가 유실되지 않는다")
    void concurrentFailuresDoNotLoseCount() throws InterruptedException, ExecutionException {
        // given: 스레드 10개가 동시에 같은 계정으로 실패를 시도
        int threadCount = 10;
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        boolean completedInTime;

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            for (int attempt = 0; attempt < threadCount; attempt++) {
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        failLogin(1);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            readyLatch.await();
            startLatch.countDown();
            executor.shutdown();
            completedInTime = executor.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(completedInTime).as("스레드 %d개가 30초 안에 끝나지 않았습니다", threadCount).isTrue();
        for (Future<?> future : futures) {
            future.get();
        }

        // then: Only the lease owner reaches BCrypt and records one confirmed mismatch.
        assertThat(consecutiveFailures()).isEqualTo(1);
        assertThat(delayStage()).isZero();

        // Contending requests must not consume additional password guesses.
        verify(passwordEncoder, times(1)).matches(any(), any());
    }

    /** 틀린 비밀번호로 지정한 횟수만큼 실패시킨다. 던져진 오류를 확인할 필요가 없는 준비 단계용이다. */
    private void failLogin(int times) {
        for (int attempt = 0; attempt < times; attempt++) {
            catchLoginFailure(EMAIL);
        }
    }

    /** 틀린 비밀번호로 로그인해 던져진 오류를 돌려준다. 오류 코드를 비교해야 할 때 쓴다. */
    private ServiceException catchLoginFailure(String email) {
        try {
            consumerAuthService.login(new LoginRequest(email, WRONG_PASSWORD));
            throw new AssertionError("로그인이 실패해야 하는데 성공했습니다: " + email);
        } catch (ServiceException exception) {
            return exception;
        }
    }

    private int consecutiveFailures() {
        return jdbcTemplate.queryForObject(
                "SELECT consecutive_failures FROM login_failure_delays "
                        + "WHERE account_namespace = 'consumer' AND account_id = ?",
                Integer.class, accountId);
    }

    private int delayStage() {
        return jdbcTemplate.queryForObject(
                "SELECT delay_stage FROM login_failure_delays "
                        + "WHERE account_namespace = 'consumer' AND account_id = ?",
                Integer.class, accountId);
    }

    private int failureRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM login_failure_delays WHERE account_id = ?", Integer.class, accountId);
    }
}
