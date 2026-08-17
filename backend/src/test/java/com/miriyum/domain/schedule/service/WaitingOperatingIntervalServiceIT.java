package com.miriyum.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.schedule.closure.entity.TemporaryClosure;
import com.miriyum.domain.schedule.closure.model.TemporaryClosureReason;
import com.miriyum.domain.schedule.closure.repository.TemporaryClosureRepository;
import com.miriyum.domain.schedule.dto.contract.WaitingOperatingIntervalSnapshot;
import com.miriyum.domain.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.schedule.entity.StoreScheduleState;
import com.miriyum.domain.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.schedule.model.WeeklyInterval;
import com.miriyum.domain.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
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
@Tag("integration-shard-c")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.menu.schedule.enabled=false"
        })
class WaitingOperatingIntervalServiceIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private WaitingOperatingIntervalService service;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private StoreScheduleStateRepository stateRepository;

    @Autowired
    private OperatingScheduleVersionRepository operatingRepository;

    @Autowired
    private TemporaryClosureRepository temporaryClosureRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanRows() {
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
    void resolvesAndLocksThePersistedActiveOperatingOccurrence() {
        Store store = createStore();
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

        WaitingOperatingIntervalSnapshot interval = service.findWaitingOperatingIntervals(
                        Set.of(store.getId()),
                        Instant.parse("2026-08-16T15:00:00Z"),
                        Instant.parse("2026-08-18T15:00:00Z"))
                .getFirst();

        assertThat(interval.startsAt()).isEqualTo(Instant.parse("2026-08-17T00:00:00Z"));
        assertThat(interval.endsAt()).isEqualTo(Instant.parse("2026-08-17T09:00:00Z"));
        assertThat(service.lockCurrentWaitingOperatingInterval(
                store.getId(),
                interval.businessIntervalKey(),
                interval.startsAt(),
                interval.endsAt(),
                Instant.parse("2026-08-17T00:30:00Z"))).contains(interval);

        temporaryClosureRepository.saveAndFlush(TemporaryClosure.create(
                store.getId(),
                Instant.parse("2026-08-17T03:00:00Z"),
                Instant.parse("2026-08-17T04:00:00Z"),
                "Asia/Seoul",
                TemporaryClosureReason.MAINTENANCE,
                null));

        assertThat(service.findWaitingOperatingIntervals(
                Set.of(store.getId()),
                Instant.parse("2026-08-16T15:00:00Z"),
                Instant.parse("2026-08-18T15:00:00Z"))).containsExactly(interval);
        assertThat(service.findWaitingOperatingIntervals(
                Set.of(store.getId()),
                Instant.parse("2026-08-17T03:00:00Z"),
                Instant.parse("2026-08-17T10:00:00Z"))).isEmpty();
        assertThat(service.findWaitingOperatingIntervals(
                Set.of(store.getId()),
                Instant.parse("2026-08-17T04:00:00Z"),
                Instant.parse("2026-08-17T10:00:00Z"))).containsExactly(interval);
        assertThat(service.lockCurrentWaitingOperatingInterval(
                store.getId(), interval.businessIntervalKey(), interval.startsAt(), interval.endsAt(),
                Instant.parse("2026-08-17T02:59:59Z"))).contains(interval);
        assertThat(service.lockCurrentWaitingOperatingInterval(
                store.getId(), interval.businessIntervalKey(), interval.startsAt(), interval.endsAt(),
                Instant.parse("2026-08-17T03:00:00Z"))).isEmpty();
        assertThat(service.lockCurrentWaitingOperatingInterval(
                store.getId(), interval.businessIntervalKey(), interval.startsAt(), interval.endsAt(),
                Instant.parse("2026-08-17T03:59:59Z"))).isEmpty();
        assertThat(service.lockCurrentWaitingOperatingInterval(
                store.getId(), interval.businessIntervalKey(), interval.startsAt(), interval.endsAt(),
                Instant.parse("2026-08-17T04:00:00Z"))).contains(interval);
    }

    @Test
    void persistedOvernightIntervalRecoversAtTemporaryClosureEnd() {
        Store store = createStore();
        OperatingScheduleVersion version = OperatingScheduleVersion.createDraft(
                store.getId(),
                1L,
                "Asia/Seoul",
                List.of(new WeeklyInterval(
                        DayOfWeek.MONDAY,
                        LocalTime.of(22, 0),
                        LocalTime.of(2, 0),
                        true,
                        ScheduleIntervalKind.BUSINESS_HOURS,
                        1320,
                        1560)));
        version.activate(Instant.parse("2026-08-16T00:00:00Z"), "test activation");
        operatingRepository.saveAndFlush(version);
        StoreScheduleState state = StoreScheduleState.initialize(store.getId());
        state.activateOperating(version.getId());
        stateRepository.saveAndFlush(state);
        WaitingOperatingIntervalSnapshot interval = service.findWaitingOperatingIntervals(
                        Set.of(store.getId()),
                        Instant.parse("2026-08-17T12:00:00Z"),
                        Instant.parse("2026-08-17T18:00:00Z"))
                .getFirst();
        temporaryClosureRepository.saveAndFlush(TemporaryClosure.create(
                store.getId(),
                Instant.parse("2026-08-17T15:30:00Z"),
                Instant.parse("2026-08-17T16:30:00Z"),
                "Asia/Seoul",
                TemporaryClosureReason.MAINTENANCE,
                null));

        assertThat(interval.businessDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 17));
        assertThat(service.lockCurrentWaitingOperatingInterval(
                store.getId(), interval.businessIntervalKey(), interval.startsAt(), interval.endsAt(),
                Instant.parse("2026-08-17T15:29:59Z"))).contains(interval);
        assertThat(service.lockCurrentWaitingOperatingInterval(
                store.getId(), interval.businessIntervalKey(), interval.startsAt(), interval.endsAt(),
                Instant.parse("2026-08-17T15:30:00Z"))).isEmpty();
        assertThat(service.lockCurrentWaitingOperatingInterval(
                store.getId(), interval.businessIntervalKey(), interval.startsAt(), interval.endsAt(),
                Instant.parse("2026-08-17T16:30:00Z"))).contains(interval);
    }

    @Test
    void failsClosedAfterStoreBecomesTemporarilyClosed() {
        Store store = createStore();
        store.update(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                OperationStatus.TEMPORARILY_CLOSED);
        storeRepository.saveAndFlush(store);

        assertThat(service.findWaitingOperatingIntervals(
                Set.of(store.getId()),
                Instant.parse("2026-08-16T15:00:00Z"),
                Instant.parse("2026-08-18T15:00:00Z"))).isEmpty();
    }

    private Store createStore() {
        long operatorId = operatorRepository.saveAndFlush(StoreOperatorAccount.create(
                "waiting-interval@example.com",
                "hashed",
                "운영자")).getId();
        return storeRepository.saveAndFlush(Store.create(
                operatorId,
                "1234567890",
                BusinessType.CAFE,
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
    }
}
