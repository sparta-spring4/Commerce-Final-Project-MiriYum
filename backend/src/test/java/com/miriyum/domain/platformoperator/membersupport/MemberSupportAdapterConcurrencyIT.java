package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.enums.ConsumerAccountStatus;
import com.miriyum.domain.consumer.membersupport.ConsumerMemberSupportAdapter;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("integration")
@Tag("integration-shard-c")
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
class MemberSupportAdapterConcurrencyIT {
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

    @Autowired ConsumerMemberSupportAdapter adapter;
    @Autowired ConsumerAccountRepository accounts;

    @BeforeEach
    void clean() {
        accounts.deleteAll();
    }

    @Test
    void recoveryAndFeatureRestrictionHaveOneValidTransitionAndOneStateConflict() throws Exception {
        ConsumerAccount account = accounts.saveAndFlush(ConsumerAccount.createWithContact(
                "race@example.com", "hash", "race", "+821012345678", "contact-ref"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var recovery = executor.submit(() -> race(ready, start,
                    () -> adapter.approveRecovery(account.getId(), 0, "recovered@example.com")));
            var restriction = executor.submit(() -> race(ready, start,
                    () -> adapter.advanceSupportVersion(account.getId(), 0)));
            ready.await();
            start.countDown();

            assertThat(List.of(recovery.get(), restriction.get()))
                    .containsExactlyInAnyOrder("SUCCESS", AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT.getCode());
        }

        ConsumerAccount committed = accounts.findById(account.getId()).orElseThrow();
        assertThat(committed.getSupportVersion()).isEqualTo(1);
        boolean recovered = committed.isPasswordResetRequired()
                && committed.getEmail().equals("recovered@example.com")
                && committed.getStatus() == ConsumerAccountStatus.ACTIVE;
        boolean restricted = !committed.isPasswordResetRequired()
                && committed.getEmail().equals("race@example.com")
                && committed.getStatus() == ConsumerAccountStatus.ACTIVE;
        assertThat(recovered || restricted).isTrue();
    }

    private String race(CountDownLatch ready, CountDownLatch start, Transition transition) throws Exception {
        ready.countDown();
        start.await();
        try {
            transition.apply();
            return "SUCCESS";
        } catch (ServiceException exception) {
            return exception.getErrorCode().getCode();
        }
    }

    @FunctionalInterface
    private interface Transition {
        long apply();
    }
}
