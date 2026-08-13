package com.miriyum.domain.auth.social.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.dto.KakaoIdentityFingerprint;
import com.miriyum.domain.auth.social.entity.SocialLoginLink;
import com.miriyum.domain.auth.social.enums.KakaoLinkResult;
import com.miriyum.domain.auth.social.enums.SocialLoginProvider;
import com.miriyum.domain.auth.social.repository.SocialLoginLinkRepository;
import com.miriyum.domain.consumer.dto.auth.ConsumerKakaoSignUpRequest;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.consumer.service.ConsumerKakaoAuthService;
import com.miriyum.domain.consumer.service.ConsumerKakaoLinkTransactionService;
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
            "miriyum.kakao.identity-fingerprint-active-key-version=v2",
            "miriyum.kakao.identity-fingerprint-active-secret=test-only-active-fingerprint-secret",
            "miriyum.kakao.identity-fingerprint-previous-key-version=v1",
            "miriyum.kakao.identity-fingerprint-previous-secret=test-only-previous-fingerprint-secret"
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
    private KakaoIdentityFingerprintGenerator fingerprintGenerator;

    @Autowired
    private SocialLoginLinkRepository socialLoginLinkRepository;

    @Autowired
    private ConsumerKakaoLinkTransactionService consumerKakaoLinkTransactionService;

    @Autowired
    private ConsumerKakaoAuthService consumerKakaoAuthService;

    @Autowired
    private KakaoSignUpTicketService kakaoSignUpTicketService;

    @Autowired
    private ConsumerAccountRepository consumerAccountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM social_login_links");
        jdbcTemplate.update("DELETE FROM consumer_accounts");
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

    @Test
    @DisplayName("소비자 카카오 연결 진입 경로도 같은 식별자를 한 계정에만 연결한다")
    void allowsOnlyOneAccountThroughConsumerLinkTransactionBoundary() throws Exception {
        ConsumerAccount first = consumerAccountRepository.saveAndFlush(
                ConsumerAccount.create("first@example.com", "{sha256-bcrypt}hash", "첫번째"));
        ConsumerAccount second = consumerAccountRepository.saveAndFlush(
                ConsumerAccount.create("second@example.com", "{sha256-bcrypt}hash", "두번째"));

        List<Attempt> attempts = runConcurrently(
                () -> consumerKakaoLinkTransactionService.linkActiveAccount(first.getId(), "same-kakao-subject"),
                () -> consumerKakaoLinkTransactionService.linkActiveAccount(second.getId(), "same-kakao-subject"));

        assertThat(rowCount()).isOne();
        assertThat(attempts).filteredOn(attempt -> attempt.result() == KakaoLinkResult.CREATED).hasSize(1);
        assertThat(attempts).filteredOn(Attempt::isKakaoConflict).hasSize(1);
    }

    @Test
    @DisplayName("소비자 카카오 연결 진입 경로는 한 계정에 서로 다른 식별자를 하나만 연결한다")
    void allowsOnlyOneKakaoIdentityThroughConsumerLinkTransactionBoundary() throws Exception {
        ConsumerAccount account = consumerAccountRepository.saveAndFlush(
                ConsumerAccount.create("user@example.com", "{sha256-bcrypt}hash", "사용자"));

        List<Attempt> attempts = runConcurrently(
                () -> consumerKakaoLinkTransactionService.linkActiveAccount(account.getId(), "first-kakao-subject"),
                () -> consumerKakaoLinkTransactionService.linkActiveAccount(account.getId(), "second-kakao-subject"));

        assertThat(rowCount()).isOne();
        assertThat(attempts).filteredOn(attempt -> attempt.result() == KakaoLinkResult.CREATED).hasSize(1);
        assertThat(attempts).filteredOn(Attempt::isKakaoConflict).hasSize(1);
    }

    @Test
    @DisplayName("소비자 카카오 가입 진입 경로도 같은 티켓으로 계정과 연결을 하나만 만든다")
    void createsOnlyOneAccountThroughConsumerSignUpTransactionBoundary() throws Exception {
        String ticket = kakaoSignUpTicketService.create(TokenNamespace.CONSUMER, "v2", "c".repeat(64));

        List<Attempt> attempts = runConcurrently(
                () -> signUp(ticket, "first@example.com", "010-1111-1111", "첫번째"),
                () -> signUp(ticket, "second@example.com", "010-2222-2222", "두번째"));

        assertThat(rowCount()).isOne();
        assertThat(consumerAccountRepository.count()).isOne();
        assertThat(attempts).filteredOn(attempt -> attempt.result() == KakaoLinkResult.CREATED).hasSize(1);
        assertThat(attempts).filteredOn(Attempt::isKakaoConflict).hasSize(1);
    }

    @Test
    @DisplayName("키 교체 전 가입 티켓은 현재 키 연결이 생겨도 새 계정을 만들지 못한다")
    void rejectsPreviousKeyTicketBeforeCreatingAnotherAccount() {
        String kakaoSubject = "rotated-kakao-subject";
        KakaoIdentityFingerprint previous = fingerprintGenerator.generatePrevious(kakaoSubject).orElseThrow();
        KakaoIdentityFingerprint active = fingerprintGenerator.generateActive(kakaoSubject);
        ConsumerAccount linkedAccount = consumerAccountRepository.saveAndFlush(
                ConsumerAccount.create("linked@example.com", "{sha256-bcrypt}hash", "기존 사용자"));
        kakaoSocialLoginLinkService.linkFingerprint(TokenNamespace.CONSUMER, linkedAccount.getId(), active);
        String previousTicket = kakaoSignUpTicketService.create(
                TokenNamespace.CONSUMER, previous.keyVersion(), previous.value());

        assertThatThrownBy(() -> signUp(previousTicket, "new@example.com", "010-3333-3333", "새 사용자"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.KAKAO_OAUTH_INVALID));

        assertThat(consumerAccountRepository.count()).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("현재 fingerprint 충돌 시 이전 fingerprint 조회는 현재 연결로 수렴하고 기존 행을 유지한다")
    void rejectsLoginWhenActiveAndPreviousFingerprintsPointToDifferentAccounts() {
        String kakaoSubject = "fingerprint-collision-subject";
        KakaoIdentityFingerprint previous = fingerprintGenerator.generatePrevious(kakaoSubject).orElseThrow();
        KakaoIdentityFingerprint active = fingerprintGenerator.generateActive(kakaoSubject);
        SocialLoginLink previousLink = socialLoginLinkRepository.saveAndFlush(SocialLoginLink.create(
                TokenNamespace.CONSUMER, 101L, SocialLoginProvider.KAKAO, previous.keyVersion(), previous.value()));
        socialLoginLinkRepository.saveAndFlush(SocialLoginLink.create(
                TokenNamespace.CONSUMER, 202L, SocialLoginProvider.KAKAO, active.keyVersion(), active.value()));

        assertThatThrownBy(() -> kakaoSocialLoginLinkService.findLinkedAccountId(
                        TokenNamespace.CONSUMER, kakaoSubject))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.KAKAO_OAUTH_INVALID));
        assertThat(socialLoginLinkRepository.findLink(
                TokenNamespace.CONSUMER, SocialLoginProvider.KAKAO, previous.keyVersion(), previous.value()))
                .hasValueSatisfying(link -> assertThat(link.getAccountId()).isEqualTo(101L));
        assertThat(socialLoginLinkRepository.findLink(
                TokenNamespace.CONSUMER, SocialLoginProvider.KAKAO, active.keyVersion(), active.value()))
                .hasValueSatisfying(link -> assertThat(link.getAccountId()).isEqualTo(202L));
    }

    @Test
    @DisplayName("이전 fingerprint 키로 찾은 로그인 연결은 현재 키 값으로 갱신한다")
    void migratesPreviousFingerprintToActiveKeyOnLogin() {
        String kakaoSubject = "legacy-kakao-subject";
        KakaoIdentityFingerprint previous = fingerprintGenerator.generatePrevious(kakaoSubject).orElseThrow();
        KakaoIdentityFingerprint active = fingerprintGenerator.generateActive(kakaoSubject);
        socialLoginLinkRepository.saveAndFlush(SocialLoginLink.create(
                TokenNamespace.CONSUMER,
                101L,
                SocialLoginProvider.KAKAO,
                previous.keyVersion(),
                previous.value()));

        Long accountId = kakaoSocialLoginLinkService.findLinkedAccountId(TokenNamespace.CONSUMER, kakaoSubject);

        assertThat(accountId).isEqualTo(101L);
        assertThat(socialLoginLinkRepository.findLink(
                TokenNamespace.CONSUMER,
                SocialLoginProvider.KAKAO,
                active.keyVersion(),
                active.value())).isPresent();
        assertThat(socialLoginLinkRepository.findLink(
                TokenNamespace.CONSUMER,
                SocialLoginProvider.KAKAO,
                previous.keyVersion(),
                previous.value())).isEmpty();
    }

    private KakaoLinkResult signUp(String ticket, String email, String phoneNumber, String nickname) {
        consumerKakaoAuthService.signUp(new ConsumerKakaoSignUpRequest(ticket, email, phoneNumber, true, nickname));
        return KakaoLinkResult.CREATED;
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
