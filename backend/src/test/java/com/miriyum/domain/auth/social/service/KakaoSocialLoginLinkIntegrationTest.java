package com.miriyum.domain.auth.social.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.dto.KakaoIdentityFingerprint;
import com.miriyum.domain.auth.social.enums.KakaoLinkResult;
import com.miriyum.global.exception.ServiceException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.kakao.identity-fingerprint-active-key-version=v1",
            "miriyum.kakao.identity-fingerprint-active-secret=test-only-fingerprint-secret"
        })
class KakaoSocialLoginLinkIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private KakaoSocialLoginLinkService kakaoSocialLoginLinkService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM social_login_links");
    }

    @Test
    @DisplayName("같은 카카오 식별자를 서로 다른 계정에 동시에 연결하면 하나만 생성된다")
    void allowsOnlyOneAccountForConcurrentSameKakaoIdentity() throws Exception {
        KakaoIdentityFingerprint fingerprint = new KakaoIdentityFingerprint("v1", "a".repeat(64));

        List<Attempt> attempts = runConcurrently(
                () -> kakaoSocialLoginLinkService.linkFingerprint(TokenNamespace.CONSUMER, 101L, fingerprint),
                () -> kakaoSocialLoginLinkService.linkFingerprint(TokenNamespace.CONSUMER, 202L, fingerprint));

        assertThat(rowCount()).isOne();
        assertThat(attempts).filteredOn(attempt -> attempt.result() == KakaoLinkResult.CREATED).hasSize(1);
        assertThat(attempts).filteredOn(Attempt::isKakaoConflict).hasSize(1);
    }

    @Test
    @DisplayName("같은 계정에 서로 다른 카카오 식별자를 동시에 연결하면 하나만 생성된다")
    void allowsOnlyOneKakaoIdentityForConcurrentSameAccount() throws Exception {
        List<Attempt> attempts = runConcurrently(
                () -> kakaoSocialLoginLinkService.linkFingerprint(
                        TokenNamespace.CONSUMER, 101L, new KakaoIdentityFingerprint("v1", "a".repeat(64))),
                () -> kakaoSocialLoginLinkService.linkFingerprint(
                        TokenNamespace.CONSUMER, 101L, new KakaoIdentityFingerprint("v1", "b".repeat(64))));

        assertThat(rowCount()).isOne();
        assertThat(attempts).filteredOn(attempt -> attempt.result() == KakaoLinkResult.CREATED).hasSize(1);
        assertThat(attempts).filteredOn(Attempt::isKakaoConflict).hasSize(1);
    }

    private List<Attempt> runConcurrently(ThrowingSupplier first, ThrowingSupplier second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Attempt> firstAttempt = executor.submit(() -> runAfterStart(first, ready, start));
            Future<Attempt> secondAttempt = executor.submit(() -> runAfterStart(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(firstAttempt.get(10, TimeUnit.SECONDS), secondAttempt.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private Attempt runAfterStart(ThrowingSupplier supplier, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent test did not start in time");
        }
        try {
            return new Attempt(supplier.get(), null);
        } catch (ServiceException exception) {
            return new Attempt(null, exception);
        }
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM social_login_links", Integer.class);
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        KakaoLinkResult get();
    }

    private record Attempt(KakaoLinkResult result, ServiceException exception) {

        private boolean isKakaoConflict() {
            return exception != null && exception.getErrorCode() == AuthErrorCode.KAKAO_ALREADY_LINKED;
        }
    }
}
