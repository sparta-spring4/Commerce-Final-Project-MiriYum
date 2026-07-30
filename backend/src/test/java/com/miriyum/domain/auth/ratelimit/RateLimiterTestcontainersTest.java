package com.miriyum.domain.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제 MySQL(Testcontainers)에서 (1) 여러 인스턴스·스레드가 동시에 요청 제한 카운터를
 * 갱신해도 한도를 넘지 않는지, (2) V1 migration의 이메일 유일 제약이 실제로 걸리는지를
 * 검증한다. H2는 MySQL 전용 원자적 upsert 문법을 지원하지 않아 이 두 가지의 증거로
 * 쓸 수 없다({@code docs/service-policies/18-scale-reliability.md} SCALE-014, 이슈 #63).
 */
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.rate-limit.sign-up.max-requests=5",
            "miriyum.rate-limit.sign-up.window-seconds=600",
            "miriyum.rate-limit.login.max-requests=20",
            "miriyum.rate-limit.login.window-seconds=600",
            "miriyum.rate-limit.token-refresh.max-requests=30",
            "miriyum.rate-limit.token-refresh.window-seconds=60",
            "miriyum.rate-limit.csrf-preparation.max-requests=60",
            "miriyum.rate-limit.csrf-preparation.window-seconds=60"
        })
class RateLimiterTestcontainersTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private ConsumerAccountRepository consumerAccountRepository;

    @Test
    @DisplayName("여러 스레드가 동시에 같은 키를 요청해도 허용 횟수가 한도를 넘지 않는다")
    void concurrentRequestsForSameKeyNeverExceedTheLimit() throws InterruptedException {
        // given: LOGIN 한도는 20, 스레드 50개가 동시에 같은 키로 요청
        int threadCount = 50;
        String key = "concurrency-test-ip";
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger allowedCount = new AtomicInteger();
        boolean completedInTime;

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        if (rateLimiter.tryConsume(RateLimitCategory.LOGIN, key).allowed()) {
                            allowedCount.incrementAndGet();
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            // when: 모든 스레드가 준비된 뒤 동시에 시작
            readyLatch.await();
            startLatch.countDown();
            executor.shutdown();
            completedInTime = executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        // then: 제한 시간 안에 스레드 50개가 다 끝나야 아래 카운트 검증이 의미 있다
        assertThat(completedInTime)
                .as("스레드 %d개가 10초 안에 끝나지 않았습니다", threadCount)
                .isTrue();

        // then: 한도(20)를 넘는 허용은 없어야 한다
        assertThat(allowedCount.get()).isLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("V1 migration의 이메일 유일 제약이 실제 MySQL에서 걸린다")
    void v1MigrationEnforcesUniqueEmailConstraint() {
        // given
        String duplicateEmail = "duplicate-constraint-test@example.com";
        consumerAccountRepository.saveAndFlush(ConsumerAccount.create(duplicateEmail, "hashed", "첫계정"));

        // when & then: 같은 이메일로 두 번째 계정을 만들면 실제 유일 제약 위반으로 거부된다
        assertThatThrownBy(() ->
                consumerAccountRepository.saveAndFlush(ConsumerAccount.create(duplicateEmail, "hashed", "둘째계정")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
