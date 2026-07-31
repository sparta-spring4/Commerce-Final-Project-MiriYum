package com.miriyum.domain.auth.logindelay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.exception.AuthErrorCode;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * AUTH-006의 계정 단위 로그인 지연을 실제 MySQL에서 검증한다.
 *
 * <p>실패 기록은 {@code INSERT IGNORE} + {@code SELECT ... FOR UPDATE}로 갱신되는 MySQL 전용
 * 경로이고, 정책은 여러 인증 인스턴스가 동시에 실패를 기록해도 5회 기준이 우회되지 않도록
 * 요구하므로 H2가 아니라 Testcontainers MySQL을 쓴다.</p>
 */
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class LoginDelayIntegrationTest {

    private static final String EMAIL = "delay-test@example.com";
    private static final String RAW_PASSWORD = "Password123!";
    private static final String WRONG_PASSWORD = "WrongPassword123!";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private ConsumerAuthService consumerAuthService;

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

        // then: 결과는 정확히 5회·1단계여야 한다. 앞의 5건이 5회 기준을 채워 1분 지연을 만들고,
        // 나머지 요청은 잠금 안에서 "지연 중"으로 판정돼 상태를 바꾸지 않는다. 단계가 2·3으로
        // 올라가면 지연이 살아있는 동안의 실패로 단계가 뛴 것이므로 정책 위반이다.
        assertThat(consecutiveFailures()).isEqualTo(5);
        assertThat(delayStage()).isEqualTo(1);

        // then: 지연이 걸린 뒤의 요청은 비밀번호 비교에 도달하지 않아야 한다. 지연은 추측 속도를
        // 늦추는 장치이므로, 동시 요청이 지연을 우회해 해시 비교를 계속 수행하면 목적이 무너진다.
        verify(passwordEncoder, times(5)).matches(any(), any());
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
