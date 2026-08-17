package com.miriyum.domain.reservation.waiting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.*;
import com.miriyum.domain.reservation.waiting.entity.*;
import com.miriyum.domain.reservation.waiting.repository.*;
import com.miriyum.domain.reservation.waiting.service.*;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-b")
@Timeout(30)
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.store.schedule.activation-enabled=false",
        "miriyum.reservation.time-policy.activation-enabled=false",
        "miriyum.waiting.closure.initial-delay-ms=600000"
})
class WaitingSettingConcurrencyIT {
    @Container static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired WaitingSettingService service;
    @Autowired WaitingSettingRepository settings;
    @Autowired WaitingSettingAuditRepository audits;
    @Autowired WaitingCreationService creationService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean WaitingStoreAuthorityPort authority;
    @MockitoBean WaitingClosureService closures;
    @MockitoBean WaitingOperatingIntervalPort intervalPort;

    @BeforeEach
    void fixture() {
        given(intervalPort.lockCurrent(anyLong(), any(LocalDate.class), any(Instant.class)))
                .willAnswer(invocation -> List.of(openInterval(
                        invocation.getArgument(0), invocation.getArgument(1))));
        jdbc.execute("DELETE FROM waiting_status_events");
        jdbc.execute("DELETE FROM waiting_transition_audits");
        jdbc.execute("DELETE FROM waiting_active_memberships");
        jdbc.execute("DELETE FROM waiting_teams");
        jdbc.execute("DELETE FROM waiting_queue_sequences");
        jdbc.execute("DELETE FROM waiting_setting_audits");
        jdbc.execute("DELETE FROM waiting_settings");
        jdbc.execute("DELETE FROM idempotency_commands");
        jdbc.execute("DELETE FROM store_tag_assignment");
        jdbc.execute("DELETE FROM stores");
        jdbc.execute("DELETE FROM store_operator_accounts");
        jdbc.execute("DELETE FROM consumer_accounts");
        insertStoreFixture();
        jdbc.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name, status, created_at, updated_at
                ) VALUES (41, 'setting-race-consumer@example.com', 'hash', '설정 경합 사용자',
                    'ACTIVE', NOW(6), NOW(6))
                """);
        given(authority.requireRead(anyLong(), anyLong()))
                .willAnswer(invocation -> new WaitingStoreAuthority(
                        invocation.getArgument(1), ZoneId.of("Asia/Seoul")));
        given(authority.requireMutation(anyLong(), anyLong()))
                .willAnswer(invocation -> new WaitingStoreAuthority(
                        invocation.getArgument(1), ZoneId.of("Asia/Seoul")));
    }

    @Test
    void sameExpectedVersionAllowsExactlyOneConcurrentReplacement() throws Exception {
        service.replace(31L, 22L, key(1), new WaitingSettingUpdateRequest(
                0L, true, WaitingReceptionMode.AUTO, 60, null));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Attempt>> futures = List.of(
                    executor.submit(() -> attempt(ready, start, key(2), WaitingReceptionMode.MANUAL, 30)),
                    executor.submit(() -> attempt(ready, start, key(3), WaitingReceptionMode.PAUSED, 40)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Attempt> results = futures.stream().map(WaitingSettingConcurrencyIT::get).toList();

            assertThat(results).filteredOn(Attempt::succeeded).hasSize(1);
            assertThat(results).filteredOn(result -> !result.succeeded()).hasSize(1)
                    .allMatch(result -> result.failure() instanceof ServiceException);
        }

        assertThat(settings.findByStoreId(22L).orElseThrow().getVersion()).isEqualTo(2L);
        assertThat(audits.countByStoreId(22L)).isEqualTo(2L);
    }

    @Test
    void committedDisableFencesWaitingCreationThatStartedWhileTheSettingWasLocked() throws Exception {
        service.replace(31L, 22L, key(10), new WaitingSettingUpdateRequest(
                0L, true, WaitingReceptionMode.MANUAL, 60, null));
        CountDownLatch disabledButUncommitted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> disabler = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        WaitingSetting setting = settings.findByStoreIdForUpdate(22L).orElseThrow();
                        setting.replace(1L, false, WaitingReceptionMode.PAUSED, 60, Instant.now());
                        settings.saveAndFlush(setting);
                        disabledButUncommitted.countDown();
                        await(allowCommit);
                    }));
            assertThat(disabledButUncommitted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<RuntimeException> creator = executor.submit(() -> {
                try {
                    creationService.create(22L, 41L, LocalDate.of(2026, 8, 16), 2,
                            WaitingSource.REMOTE, key(11));
                    return null;
                } catch (RuntimeException failure) {
                    return failure;
                }
            });
            assertThatThrownBy(() -> creator.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowCommit.countDown();
            disabler.get(5, TimeUnit.SECONDS);
            RuntimeException failure = creator.get(5, TimeUnit.SECONDS);
            assertThat(failure).isInstanceOfSatisfying(ServiceException.class, exception ->
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ReservationErrorCode.WAITING_RECEPTION_CLOSED));
        } finally {
            allowCommit.countDown();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waiting_teams", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM waiting_active_memberships", Long.class)).isZero();
        assertThat(settings.findByStoreId(22L).orElseThrow().isEnabled()).isFalse();
    }

    private Attempt attempt(CountDownLatch ready, CountDownLatch start, IdempotencyKey key,
            WaitingReceptionMode mode, int minutes) {
        ready.countDown();
        await(start);
        try {
            return new Attempt(service.replace(31L, 22L, key,
                    new WaitingSettingUpdateRequest(1L, true, mode, minutes, null)), null);
        } catch (RuntimeException failure) {
            return new Attempt(null, failure);
        }
    }

    private static Attempt get(Future<Attempt> future) {
        try { return future.get(10, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static IdempotencyKey key(int suffix) {
        return IdempotencyKey.parse(String.format("550e8400-e29b-41d4-a716-%012d", suffix));
    }

    private WaitingOperatingInterval openInterval(long storeId, LocalDate businessDate) {
        return new WaitingOperatingInterval(
                storeId,
                "setting-concurrency-" + storeId,
                1L,
                businessDate,
                businessDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),
                Instant.now().plus(Duration.ofHours(1)),
                "Asia/Seoul");
    }

    private void insertStoreFixture() {
        jdbc.update("""
                INSERT INTO store_operator_accounts (
                    store_operator_account_id, email, password_hash, display_name, status,
                    created_at, updated_at
                ) VALUES (31, 'waiting-concurrency@example.com', 'hash', '웨이팅 경합', 'ACTIVE',
                    NOW(6), NOW(6))
                """);
        jdbc.update("""
                INSERT INTO stores (
                    store_id, store_operator_account_id, business_registration_number,
                    business_type, name, description, region, address, store_category_code,
                    time_zone_id, verification_status, operation_status,
                    reservation_enabled, menu_hold_enabled, pickup_enabled,
                    applicant_self_attested_at, required_terms_agreed_at, required_terms_version,
                    created_at, updated_at
                ) VALUES (
                    22, 31, '2710000003', 'CAFE', '웨이팅 경합 매장', '테스트', 'SEOUL',
                    '서울시 테스트로 3', 'CAFE_BAKERY', 'Asia/Seoul', 'APPROVED', 'OPEN',
                    TRUE, TRUE, TRUE, NOW(6), NOW(6),
                    'STORE_ONBOARDING_REQUIRED_TERMS_V1', NOW(6), NOW(6))
                """);
    }

    private record Attempt(WaitingSettingCommandResult result, RuntimeException failure) {
        boolean succeeded() { return result != null; }
    }
}
