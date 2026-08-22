package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.waiting.entity.WaitingAutoOpenJob;
import com.miriyum.domain.reservation.waiting.entity.WaitingAutoOpenJobStatus;
import com.miriyum.domain.reservation.waiting.entity.WaitingReceptionMode;
import com.miriyum.domain.reservation.waiting.entity.WaitingReceptionWindow;
import com.miriyum.domain.reservation.waiting.entity.WaitingSetting;
import com.miriyum.domain.reservation.waiting.repository.WaitingAutoOpenJobRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingReceptionWindowRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingSettingRepository;
import com.miriyum.domain.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.schedule.entity.StoreScheduleState;
import com.miriyum.domain.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.schedule.model.WeeklyInterval;
import com.miriyum.domain.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.OperationStatus;
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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
class WaitingAutoOpenServiceIT {

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
    @Autowired WaitingAutoOpenPlanner planner;
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
    void exactAutoSettingsOpenOnceWithoutChangingPublicVersion() {
        Fixture fixture = fixture();
        WaitingAutoOpenClaim claim = service.claimDue(
                "worker-a", OPEN_AT, Duration.ofSeconds(30), 10).getFirst();

        assertThat(service.execute(claim, OPEN_AT))
                .isEqualTo(WaitingAutoOpenService.ExecutionResult.COMPLETED);

        assertThat(windowRepository.count()).isEqualTo(1L);
        assertThat(jobRepository.findById(fixture.jobId()).orElseThrow().getStatus())
                .isEqualTo(WaitingAutoOpenJobStatus.COMPLETED);
        assertThat(settingRepository.findByStoreId(fixture.storeId()).orElseThrow().getVersion())
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT lock_version FROM waiting_settings WHERE store_id = ?",
                Long.class,
                fixture.storeId())).isEqualTo(1L);
    }

    @Test
    void settingsVersionChangeAfterClaimInvalidatesOldJob() {
        Fixture fixture = fixture();
        WaitingAutoOpenClaim claim = service.claimDue(
                "worker-a", OPEN_AT, Duration.ofSeconds(30), 10).getFirst();
        WaitingSetting changed = settingRepository.findByStoreId(fixture.storeId()).orElseThrow();
        changed.replace(
                1L,
                true,
                WaitingReceptionMode.PAUSED,
                60,
                OPEN_AT.plusSeconds(1));
        settingRepository.saveAndFlush(changed);

        assertThat(service.execute(claim, OPEN_AT.plusSeconds(2)))
                .isEqualTo(WaitingAutoOpenService.ExecutionResult.INVALIDATED);
        assertThat(windowRepository.count()).isZero();
        assertThat(jobRepository.findById(fixture.jobId()).orElseThrow().getFailureCode())
                .isEqualTo("STALE_SETTINGS");
    }

    @Test
    void failedWindowInsertRollsBackSettingsFenceAndJobCompletion() {
        Fixture fixture = fixture();
        WaitingAutoOpenClaim claim = service.claimDue(
                "worker-a", OPEN_AT, Duration.ofSeconds(30), 10).getFirst();
        windowRepository.saveAndFlush(WaitingReceptionWindow.opened(
                fixture.jobId(),
                fixture.storeId(),
                fixture.interval().businessIntervalKey(),
                fixture.interval().businessDate(),
                OPEN_AT,
                fixture.interval().endsAt(),
                1L,
                OPEN_AT));

        assertThatThrownBy(() -> service.execute(claim, OPEN_AT.plusSeconds(1)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT lock_version FROM waiting_settings WHERE store_id = ?",
                Long.class,
                fixture.storeId())).isZero();
        assertThat(jobRepository.findById(fixture.jobId()).orElseThrow().getStatus())
                .isEqualTo(WaitingAutoOpenJobStatus.PROCESSING);
        assertThat(windowRepository.count()).isEqualTo(1L);
    }

    @Test
    void repeatedPlanningKeepsDueJobClaimableWithoutRollbackOnlyFailure() {
        PlanningFixture fixture = planningFixture();
        Instant beforeDue = OPEN_AT.minusSeconds(1);

        assertThat(planner.plan(beforeDue, Duration.ofHours(1), 10)).isEqualTo(1);
        assertThat(planner.plan(OPEN_AT, Duration.ofHours(1), 10)).isZero();

        assertThat(service.claimDue(
                "worker-a", OPEN_AT, Duration.ofSeconds(30), 10))
                .singleElement()
                .satisfies(claim -> {
                    assertThat(claim.storeId()).isEqualTo(fixture.storeId());
                    assertThat(claim.businessIntervalKey())
                            .isEqualTo(fixture.interval().businessIntervalKey());
                });
    }

    @Test
    void staleIntervalJobRearmsWithNewFenceAfterStoreReopens() {
        Fixture fixture = fixture();
        WaitingAutoOpenClaim staleClaim = service.claimDue(
                "worker-a", OPEN_AT, Duration.ofSeconds(30), 10).getFirst();
        Store store = storeRepository.findById(fixture.storeId()).orElseThrow();
        store.update(
                null, null, null, null, null, null, null, null, null,
                OperationStatus.TEMPORARILY_CLOSED);
        storeRepository.saveAndFlush(store);

        assertThat(service.execute(staleClaim, OPEN_AT.plusSeconds(1)))
                .isEqualTo(WaitingAutoOpenService.ExecutionResult.INVALIDATED);
        WaitingAutoOpenJob invalidated = jobRepository.findById(fixture.jobId()).orElseThrow();
        assertThat(invalidated.getFailureCode()).isEqualTo("STALE_INTERVAL");

        store.update(
                null, null, null, null, null, null, null, null, null,
                OperationStatus.OPEN);
        storeRepository.saveAndFlush(store);
        Instant recoveredAt = OPEN_AT.plusSeconds(2);

        assertThat(planner.plan(recoveredAt, Duration.ofHours(1), 10)).isEqualTo(1);
        WaitingAutoOpenClaim recoveredClaim = service.claimDue(
                "worker-b", recoveredAt, Duration.ofSeconds(30), 10).getFirst();
        assertThat(recoveredClaim.jobId()).isEqualTo(fixture.jobId());
        assertThat(recoveredClaim.fencingToken()).isGreaterThan(staleClaim.fencingToken());
        assertThat(service.execute(recoveredClaim, recoveredAt))
                .isEqualTo(WaitingAutoOpenService.ExecutionResult.COMPLETED);

        assertThat(windowRepository.count()).isEqualTo(1L);
        assertThat(service.recordFailure(
                staleClaim,
                recoveredAt.plusSeconds(1),
                new org.springframework.dao.CannotAcquireLockException("late"),
                4,
                Duration.ofSeconds(2),
                Duration.ofMinutes(1))).isFalse();
        assertThat(jobRepository.findById(fixture.jobId()).orElseThrow().getStatus())
                .isEqualTo(WaitingAutoOpenJobStatus.COMPLETED);
    }

    private Fixture fixture() {
        PlanningFixture fixture = planningFixture();
        WaitingOperatingInterval interval = fixture.interval();
        WaitingAutoOpenJob job = jobRepository.saveAndFlush(WaitingAutoOpenJob.pending(
                fixture.storeId(),
                interval.businessIntervalKey(),
                interval.businessDate(),
                interval.startsAt(),
                interval.endsAt(),
                OPEN_AT,
                1L,
                60,
                WaitingAutoOpenIdempotencyKey.from(
                        fixture.storeId(), interval.businessIntervalKey(), 1L),
                OPEN_AT.minusSeconds(60)));
        return new Fixture(fixture.storeId(), job.getId(), interval);
    }

    private PlanningFixture planningFixture() {
        long operatorId = operatorRepository.saveAndFlush(StoreOperatorAccount.create(
                "waiting-auto-open@example.com",
                "hashed",
                "운영자")).getId();
        Store store = storeRepository.saveAndFlush(Store.create(
                operatorId,
                "1234567890",
                "웨이팅 매장",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                Set.of(),
                true,
                true,
                true,
                "Asia/Seoul",
                LocalDateTime.of(2026, 8, 16, 12, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1"));
        OperatingScheduleVersion version = OperatingScheduleVersion.createDraft(
                store.getId(),
                1L,
                "Asia/Seoul",
                List.of(new WeeklyInterval(
                        DayOfWeek.MONDAY,
                        LocalTime.of(9, 0),
                        LocalTime.of(18, 0),
                        false,
                        ScheduleIntervalKind.BUSINESS_HOURS,
                        540,
                        1080)));
        version.activate(Instant.parse("2026-08-16T00:00:00Z"), "test activation");
        operatingRepository.saveAndFlush(version);
        StoreScheduleState state = StoreScheduleState.initialize(store.getId());
        state.activateOperating(version.getId());
        stateRepository.saveAndFlush(state);
        settingRepository.saveAndFlush(WaitingSetting.create(
                store.getId(),
                true,
                WaitingReceptionMode.AUTO,
                60,
                OPEN_AT.minusSeconds(3_600)));
        WaitingOperatingInterval interval = intervalPort.findUpcoming(
                        Set.of(store.getId()),
                        OPEN_AT,
                        OPEN_AT.plus(Duration.ofHours(12)))
                .getFirst();
        return new PlanningFixture(store.getId(), interval);
    }

    private record Fixture(
            long storeId,
            long jobId,
            WaitingOperatingInterval interval
    ) {
    }

    private record PlanningFixture(
            long storeId,
            WaitingOperatingInterval interval
    ) {
    }
}
