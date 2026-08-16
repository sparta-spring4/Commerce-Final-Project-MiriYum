package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanction;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseStatus;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseType;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberIdentityVerificationRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportCaseRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.platform-operator.enabled=true",
        "miriyum.platform-operator.reauthentication-fingerprint-secret=test-only-fingerprint-secret-at-least-32-characters",
        "miriyum.platform-operator.temporary-password.validity=PT10M",
        "miriyum.platform-operator.temporary-password.max-failures=3",
        "miriyum.member-support.enabled=true",
        "miriyum.member-support.dev-stub-enabled=true",
        "miriyum.member-support.proof-digest-secret=test-only-member-proof-secret",
        "miriyum.member-support.pii-encryption-active-key-version=1",
        "miriyum.member-support.pii-encryption-active-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "miriyum.store.schedule.activation-enabled=false",
        "miriyum.reservation.hold-expiration.enabled=false",
        "miriyum.menu.schedule.enabled=false"
})
@AutoConfigureMockMvc
class PublicMemberSupportHttpIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40");
    @Container static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired ConsumerAccountRepository accounts;
    @Autowired MemberIdentityVerificationRepository verifications;
    @Autowired MemberSupportCaseRepository cases;
    @Autowired MemberSanctionRepository sanctions;
    @Autowired PlatformOperatorAccountRepository operators;

    @BeforeEach
    void seed() {
        sanctions.deleteAll();
        cases.deleteAll();
        verifications.deleteAll();
        operators.deleteAll();
        accounts.deleteAll();
        accounts.saveAndFlush(ConsumerAccount.createWithContact(
                "old@example.com", "hash", "consumer", "+821012345678", "contact-ref"));
    }

    @Test
    void sequentialRecoverySubmissionsHaveUniformResponsesAndOneActiveCase() throws Exception {
        var valid = mvc.perform(post("/api/v1/consumers/account-recovery-verifications")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"oldEmail":"old@example.com","registeredPhone":"+821012345678",
                                 "newEmail":"new@example.com"}
                                """))
                .andExpect(status().isAccepted()).andReturn();
        var secondValid = mvc.perform(post("/api/v1/consumers/account-recovery-verifications")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"oldEmail":"old@example.com","registeredPhone":"+821012345678",
                                 "newEmail":"new@example.com"}
                                """))
                .andExpect(status().isAccepted()).andReturn();
        var missing = mvc.perform(post("/api/v1/consumers/account-recovery-verifications")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"oldEmail":"missing@example.com","registeredPhone":"+821099999999",
                                 "newEmail":"new@example.com"}
                                """))
                .andExpect(status().isAccepted()).andReturn();
        assertThat(valid.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString());
        String validHeader = valid.getResponse().getHeader("Set-Cookie");
        String missingHeader = missing.getResponse().getHeader("Set-Cookie");
        assertThat(validHeader).hasSameSizeAs(missingHeader)
                .contains("HttpOnly", "Secure", "SameSite=Strict");
        assertThat(verifications.count()).isEqualTo(2);

        mvc.perform(post("/api/v1/consumers/account-recovery-cases")
                        .cookie(cookie(validHeader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"new@example.com\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/consumers/account-recovery-cases")
                        .cookie(cookie(secondValid.getResponse().getHeader("Set-Cookie")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"new@example.com\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/consumers/account-recovery-cases")
                        .cookie(cookie(missingHeader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"new@example.com\"}"))
                .andExpect(status().isAccepted());
        assertThat(cases.count()).isEqualTo(1);
    }

    @Test
    void concurrentRecoverySubmissionsBothReturnAcceptedAndCreateOneActiveCase() throws Exception {
        String firstCookie = issueRecoveryProof();
        String secondCookie = issueRecoveryProof();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> submitRecoveryAfterBarrier(firstCookie, ready, start));
            var second = executor.submit(() -> submitRecoveryAfterBarrier(secondCookie, ready, start));
            ready.await();
            start.countDown();
            assertThat(first.get()).isEqualTo(202);
            assertThat(second.get()).isEqualTo(202);
        }
        assertThat(cases.count()).isEqualTo(1);
    }

    @Test
    void appealsAreIdempotentPerSanctionButIndependentAcrossSanctions() throws Exception {
        ConsumerAccount account = accounts.findAll().getFirst();
        PlatformOperatorAccount operator = operators.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "appeal-operator@example.com", "hash", "appeal-operator", Instant.now().plusSeconds(600)));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MemberSanction first = createAppliedSanction(
                account.getId(), operator.getId(), MemberSanctionLevel.TEMPORARY_SUSPENSION, now);
        MemberSanction second = createAppliedSanction(
                account.getId(), operator.getId(), MemberSanctionLevel.FEATURE_RESTRICTION, now);

        submitAppeal(first.getPublicId()).andExpect(status().isAccepted());
        submitAppeal(first.getPublicId()).andExpect(status().isAccepted());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> submitAppealAfterBarrier(second.getPublicId(), ready, start));
            var right = executor.submit(() -> submitAppealAfterBarrier(second.getPublicId(), ready, start));
            ready.await();
            start.countDown();
            assertThat(left.get()).isEqualTo(202);
            assertThat(right.get()).isEqualTo(202);
        }

        assertThat(cases.findAll().stream()
                .filter(candidate -> candidate.getCaseType() == MemberSupportCaseType.ACCOUNT_APPEAL))
                .hasSize(2)
                .extracting(MemberSupportCase::getSourceSanctionId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    private String issueRecoveryProof() throws Exception {
        return mvc.perform(post("/api/v1/consumers/account-recovery-verifications")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"oldEmail":"old@example.com","registeredPhone":"+821012345678",
                                 "newEmail":"new@example.com"}
                                """))
                .andExpect(status().isAccepted()).andReturn().getResponse().getHeader("Set-Cookie");
    }

    private int submitRecoveryAfterBarrier(
            String setCookie, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return mvc.perform(post("/api/v1/consumers/account-recovery-cases")
                        .cookie(cookie(setCookie))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"new@example.com\"}"))
                .andReturn().getResponse().getStatus();
    }

    private MemberSanction createAppliedSanction(
            long accountId, long operatorId, MemberSanctionLevel level, LocalDateTime now) {
        MemberSupportCase enforcement = MemberSupportCase.enforcement(
                MemberAccountType.CONSUMER, accountId, 0, "ABUSE", now);
        enforcement.assign();
        enforcement.decide(MemberSupportCaseStatus.APPROVED, "APPROVED", now);
        enforcement = cases.saveAndFlush(enforcement);
        return sanctions.saveAndFlush(MemberSanction.propose(
                enforcement, level, Set.of(), "ABUSE", "v1", operatorId, now));
    }

    private org.springframework.test.web.servlet.ResultActions submitAppeal(String sanctionId) throws Exception {
        return mvc.perform(post("/api/v1/consumers/account-sanction-appeals")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sanctionId":"%s","verificationChannel":"REGISTERED_EMAIL",
                         "contact":"old@example.com","statement":"please review"}
                        """.formatted(sanctionId)));
    }

    private int submitAppealAfterBarrier(
            String sanctionId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return submitAppeal(sanctionId).andReturn().getResponse().getStatus();
    }

    private Cookie cookie(String setCookie) {
        String[] pair = setCookie.substring(0, setCookie.indexOf(';')).split("=", 2);
        return new Cookie(pair[0], pair[1]);
    }
}
