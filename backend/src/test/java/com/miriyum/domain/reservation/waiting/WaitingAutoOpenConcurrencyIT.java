package com.miriyum.domain.reservation.waiting;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.waiting.entity.WaitingAutoOpenJob;
import com.miriyum.domain.reservation.waiting.entity.WaitingAutoOpenJobStatus;
import com.miriyum.domain.reservation.waiting.entity.WaitingReceptionMode;
import com.miriyum.domain.reservation.waiting.entity.WaitingSetting;
import com.miriyum.domain.reservation.waiting.repository.WaitingAutoOpenJobRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingReceptionWindowRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingSettingRepository;
import com.miriyum.domain.reservation.waiting.service.WaitingAutoOpenClaim;
import com.miriyum.domain.reservation.waiting.service.WaitingAutoOpenIdempotencyKey;
import com.miriyum.domain.reservation.waiting.service.WaitingAutoOpenService;
import com.miriyum.domain.reservation.waiting.service.WaitingOperatingInterval;
import com.miriyum.domain.reservation.waiting.service.WaitingOperatingIntervalPort;
import com.miriyum.domain.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.schedule.entity.StoreScheduleState;
import com.miriyum.domain.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.schedule.model.WeeklyInterval;
import com.miriyum.domain.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.menu.schedule.enabled=false",
            "miriyum.reservation.hold-expiration.enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false",
            "miriyum.waiting.compensation.enabled=false"
        })
class WaitingAutoOpenConcurrencyIT {

    private static final Instant OPEN_AT = Instant.parse("2026-08-16T23:00:00Z");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired WaitingAutoOpenService service;
    @Autowired WaitingAutoOpenJobRepository jobRepository;
    @Autowired WaitingReceptionWindowRepository windowRepository;
    @Autowired WaitingSettingRepository settingRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired StoreOperatorAccountRepository operatorRepository;
    @Autowired StoreScheduleStateRepository stateRepository;
    @Autowired OperatingScheduleVersionRepository operatingRepository;
    @Autowired WaitingOperatingIntervalPort intervalPort;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.execute("DELETE FROM waiting_reception_windows");
        jdbcTemplate.execute("DELETE FROM waiting_auto_open_jobs");
        jdbcTemplate.execute("DELETE FROM waiting_setting_audits");
        jdbcTemplate.execute("DELETE FROM waiting_settings");
        jdbcTemplate.execute("DELETE FROM store_schedule_state");
        jdbcTemplate.execute("DELETE FROM store_temporary_closures");
        jdbcTemplate.execute("DELETE FROM store_regular_closure_entries");
        jdbcTemplate.execute("DELETE FROM store_regular_closure_versions");
        jdbcTemplate.execute("DELETE FROM store_operating_schedule_entries");
        jdbcTemplate.execute("DELETE FROM store_operating_schedule_versions");
        storeRepository.deleteAll();
        operatorRepository.deleteAll();
    }

    @Test
    void simultaneousWorkersProduceOneClaimAndOneOpenEffect() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<WaitingAutoOpenClaim>>> futures = new ArrayList<>();
            for (String owner : List.of("worker-a", "worker-b")) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.claimDue(owner, OPEN_AT, Duration.ofSeconds(30), 1);
                }));
            }
            ready.await();
            start.countDown();
            List<WaitingAutoOpenClaim> claims = new ArrayList<>();
            for (Future<List<WaitingAutoOpenClaim>> future : futures) {
                claims.addAll(future.get());
            }

            assertThat(claims).singleElement();
            assertThat(service.execute(claims.getFirst(), OPEN_AT))
                    .isEqualTo(WaitingAutoOpenService.ExecutionResult.COMPLETED);
        } finally {
            executor.shutdownNow();
        }

        assertThat(windowRepository.count()).isEqualTo(1L);
        assertThat(jobRepository.findById(fixture.jobId()).orElseThrow().getStatus())
                .isEqualTo(WaitingAutoOpenJobStatus.COMPLETED);
    }

    @Test
    void leaseExpiryReclaimsWithHigherFenceAndStaleWorkerCannotComplete() {
        Fixture fixture = fixture();
        WaitingAutoOpenClaim oldClaim = service.claimDue(
                "worker-a", OPEN_AT, Duration.ofSeconds(30), 1).getFirst();
        Instant afterExpiry = OPEN_AT.plusSeconds(31);
        WaitingAutoOpenClaim currentClaim = service.claimDue(
                "worker-b", afterExpiry, Duration.ofSeconds(30), 1).getFirst();

        assertThat(currentClaim.fencingToken()).isGreaterThan(oldClaim.fencingToken());
        assertThat(service.execute(oldClaim, afterExpiry))
                .isEqualTo(WaitingAutoOpenService.ExecutionResult.STALE_CLAIM);
        assertThat(service.execute(currentClaim, afterExpiry))
                .isEqualTo(WaitingAutoOpenService.ExecutionResult.COMPLETED);

        WaitingAutoOpenJob job = jobRepository.findById(fixture.jobId()).orElseThrow();
        assertThat(job.getAttemptCount()).isEqualTo(2);
        assertThat(job.getStatus()).isEqualTo(WaitingAutoOpenJobStatus.COMPLETED);
        assertThat(windowRepository.count()).isEqualTo(1L);
    }

    private Fixture fixture() {
        long operatorId = operatorRepository.saveAndFlush(StoreOperatorAccount.create(
                "waiting-concurrency@example.com", "hashed", "운영자")).getId();
        Store store = storeRepository.saveAndFlush(Store.create(
                operatorId, "1234567890", BusinessType.CAFE, "경합 매장", "",
                Region.SEOUL, "서울시 중구", "CAFE_BAKERY", Set.of(),
                true, true, true, "Asia/Seoul",
                LocalDateTime.of(2026, 8, 16, 12, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1"));
        OperatingScheduleVersion version = OperatingScheduleVersion.createDraft(
                store.getId(), 1L, "Asia/Seoul",
                List.of(new WeeklyInterval(
                        DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0),
                        false, ScheduleIntervalKind.BUSINESS_HOURS, 540, 1080)));
        version.activate(Instant.parse("2026-08-16T00:00:00Z"), "test activation");
        operatingRepository.saveAndFlush(version);
        StoreScheduleState state = StoreScheduleState.initialize(store.getId());
        state.activateOperating(version.getId());
        stateRepository.saveAndFlush(state);
        settingRepository.saveAndFlush(WaitingSetting.create(
                store.getId(), true, WaitingReceptionMode.AUTO, 60,
                OPEN_AT.minusSeconds(3600)));
        WaitingOperatingInterval interval = intervalPort.findUpcoming(
                Set.of(store.getId()), OPEN_AT, OPEN_AT.plus(Duration.ofHours(12)))
                .getFirst();
        WaitingAutoOpenJob job = jobRepository.saveAndFlush(WaitingAutoOpenJob.pending(
                store.getId(), interval.businessIntervalKey(), interval.businessDate(),
                interval.startsAt(), interval.endsAt(), OPEN_AT, 1L, 60,
                WaitingAutoOpenIdempotencyKey.from(
                        store.getId(), interval.businessIntervalKey(), 1L),
                OPEN_AT.minusSeconds(60)));
        return new Fixture(job.getId());
    }

    private record Fixture(long jobId) {
    }
}
