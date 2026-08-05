package com.miriyum.domain.store.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.schedule.dto.DailyOperatingScheduleRequest;
import com.miriyum.domain.store.schedule.dto.TimeRangeRequest;
import com.miriyum.domain.store.schedule.dto.WeeklyOperatingHoursRequest;
import com.miriyum.domain.store.schedule.dto.SchedulePublicationRequest;
import com.miriyum.domain.store.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.store.schedule.entity.StoreScheduleState;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.store.schedule.model.PublicationMode;
import com.miriyum.domain.store.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.store.schedule.model.WeeklyInterval;
import com.miriyum.domain.store.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.store.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false"
        })
class StoreSchedulePublicationIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add(
                "spring.datasource.hikari.connection-init-sql",
                () -> "SET SESSION innodb_lock_wait_timeout = 1");
    }

    @Autowired
    private StoreScheduleService scheduleService;

    @Autowired
    private StoreScheduleCommandFacade commandFacade;

    @Autowired
    private StoreService storeService;

    @Autowired
    private StoreScheduleStateRepository stateRepository;

    @Autowired
    private OperatingScheduleVersionRepository operatingRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private IdempotencyExecutor idempotencyExecutor;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.execute("DELETE FROM store_schedule_audit_events");
        jdbcTemplate.execute("DELETE FROM store_schedule_state");
        jdbcTemplate.execute("DELETE FROM store_reservation_schedule_entries");
        jdbcTemplate.execute("DELETE FROM store_reservation_schedule_versions");
        jdbcTemplate.execute("DELETE FROM store_operating_schedule_entries");
        jdbcTemplate.execute("DELETE FROM store_operating_schedule_versions");
        jdbcTemplate.execute("DELETE FROM idempotency_commands");
        storeRepository.deleteAll();
        operatorRepository.deleteAll();
    }

    @Test
    void concurrentOperatingDraftsReceiveDistinctSequentialVersions()
            throws Exception {
        OwnerStore ownerStore = createStore();
        CountDownLatch startGate = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ScheduleCommandResult<?>> first = executor.submit(() -> {
                startGate.await();
                return scheduleService.replaceOperatingHours(
                        ownerStore.operatorId(),
                        ownerStore.storeId(),
                        IdempotencyKey.parse(
                                "550e8400-e29b-41d4-a716-446655440001"),
                        operatingRequest(9));
            });
            Future<ScheduleCommandResult<?>> second = executor.submit(() -> {
                startGate.await();
                return scheduleService.replaceOperatingHours(
                        ownerStore.operatorId(),
                        ownerStore.storeId(),
                        IdempotencyKey.parse(
                                "550e8400-e29b-41d4-a716-446655440002"),
                        operatingRequest(10));
            });

            startGate.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).httpStatus())
                    .isEqualTo(200);
            assertThat(second.get(20, TimeUnit.SECONDS).httpStatus())
                    .isEqualTo(200);
        }

        List<OperatingScheduleVersion> versions =
                operatingRepository.findAllByStoreIdOrderByVersionNumber(
                        ownerStore.storeId());
        StoreScheduleState state = stateRepository
                .findById(ownerStore.storeId())
                .orElseThrow();

        assertThat(versions)
                .extracting(OperatingScheduleVersion::getVersionNumber)
                .containsExactly(1L, 2L);
        assertThat(versions)
                .extracting(OperatingScheduleVersion::getStatus)
                .containsOnly(ScheduleVersionStatus.DRAFT);
        assertThat(state.getActiveOperatingScheduleVersionId()).isNull();
        Integer auditCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM store_schedule_audit_events
                        WHERE store_id = ?
                          AND action = 'DRAFT_CREATED'
                        """,
                Integer.class,
                ownerStore.storeId());
        assertThat(auditCount).isEqualTo(2);
    }

    @Test
    void dueOperatingPublicationActivatesExactlyOnce() {
        OwnerStore ownerStore = createStore();
        IdempotencyKey draftKey = IdempotencyKey.parse(
                "550e8400-e29b-41d4-a716-446655440010");
        scheduleService.createOperatingDraft(
                ownerStore.operatorId(),
                ownerStore.storeId(),
                draftKey,
                operatingRequest(9));
        Instant future = Instant.now().plusSeconds(60);
        scheduleService.publishOperating(
                ownerStore.operatorId(),
                ownerStore.storeId(),
                1L,
                IdempotencyKey.parse(
                        "550e8400-e29b-41d4-a716-446655440011"),
                new SchedulePublicationRequest(
                        PublicationMode.SCHEDULED,
                        OffsetDateTime.ofInstant(future, ZoneOffset.UTC),
                        "자동 게시 검증"));
        OperatingScheduleVersion scheduled = operatingRepository
                .findByStoreIdAndVersionNumber(ownerStore.storeId(), 1L)
                .orElseThrow();
        jdbcTemplate.update(
                """
                        UPDATE store_operating_schedule_versions
                        SET effective_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND
                        WHERE operating_schedule_version_id = ?
                        """,
                scheduled.getId());

        scheduleService.activateDueOperating(scheduled.getId());
        scheduleService.activateDueOperating(scheduled.getId());

        OperatingScheduleVersion activated =
                operatingRepository.findById(scheduled.getId()).orElseThrow();
        StoreScheduleState state =
                stateRepository.findById(ownerStore.storeId()).orElseThrow();
        assertThat(activated.getStatus()).isEqualTo(ScheduleVersionStatus.ACTIVE);
        assertThat(state.getActiveOperatingScheduleVersionId())
                .isEqualTo(scheduled.getId());
        Integer auditCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM store_schedule_audit_events
                        WHERE store_id = ?
                          AND action = 'SCHEDULE_ACTIVATED'
                        """,
                Integer.class,
                ownerStore.storeId());
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void scheduleAndIdempotencyRecordRollBackTogether() {
        OwnerStore ownerStore = createStore();
        IdempotencyCommand command = new IdempotencyCommand(
                "store-operator",
                ownerStore.operatorId(),
                "STORE_OPERATING_HOURS_REPLACE",
                "550e8400-e29b-41d4-a716-446655440003",
                "a".repeat(64));

        assertThatThrownBy(() ->
                transactionTemplate.executeWithoutResult(ignored ->
                        idempotencyExecutor.execute(command, () -> {
                            stateRepository.initialize(ownerStore.storeId());
                            StoreScheduleState state = stateRepository
                                    .findForUpdateByStoreId(ownerStore.storeId())
                                    .orElseThrow();
                            OperatingScheduleVersion version =
                                    operatingRepository.saveAndFlush(
                                            OperatingScheduleVersion.create(
                                                    ownerStore.storeId(),
                                                    state.allocateOperatingVersion(),
                                                    List.of(businessInterval())));
                            state.activateOperating(version.getId());
                            throw new IllegalStateException("force rollback");
                        })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(operatingRepository
                .findAllByStoreIdOrderByVersionNumber(ownerStore.storeId()))
                .isEmpty();
        Integer commandCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM idempotency_commands
                        WHERE command_type = 'STORE_OPERATING_HOURS_REPLACE'
                        """,
                Integer.class);
        assertThat(commandCount).isZero();
    }

    @Test
    void publicationAuthorityLockSerializesWithTerminalStateChange()
            throws Exception {
        OwnerStore ownerStore = createStore();
        CountDownLatch authorityLocked = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        CountDownLatch stateChangeLocked = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> publication = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(ignored -> {
                        storeService.requireSchedulePublicationAuthority(
                                ownerStore.operatorId(),
                                ownerStore.storeId());
                        authorityLocked.countDown();
                        await(releasePublication);
                    }));
            Future<?> stateChange = executor.submit(() -> {
                await(authorityLocked);
                transactionTemplate.executeWithoutResult(ignored -> {
                    Store store = storeRepository
                            .findByIdForUpdate(ownerStore.storeId())
                            .orElseThrow();
                    stateChangeLocked.countDown();
                    store.close();
                });
            });

            assertThat(authorityLocked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(stateChangeLocked.await(300, TimeUnit.MILLISECONDS))
                    .isFalse();
            releasePublication.countDown();
            publication.get(5, TimeUnit.SECONDS);
            stateChange.get(5, TimeUnit.SECONDS);
        }

        assertThat(storeRepository.findById(ownerStore.storeId()).orElseThrow()
                .getOperationStatus()).isEqualTo(OperationStatus.CLOSED);
    }

    @Test
    void actualStoreLockTimeoutReturnsStore006AndRollsBackCommand()
            throws Exception {
        OwnerStore ownerStore = createStore();
        CountDownLatch storeLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> lockHolder = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(ignored -> {
                        storeRepository.findByIdForUpdate(ownerStore.storeId())
                                .orElseThrow();
                        storeLocked.countDown();
                        await(releaseLock);
                    }));
            assertThat(storeLocked.await(5, TimeUnit.SECONDS)).isTrue();

            try {
                assertThatThrownBy(() -> commandFacade.replaceOperatingHours(
                        ownerStore.operatorId(),
                        ownerStore.storeId(),
                        IdempotencyKey.parse(
                                "550e8400-e29b-41d4-a716-446655440004"),
                        operatingRequest(9)))
                        .isInstanceOf(ServiceException.class)
                        .extracting(exception ->
                                ((ServiceException) exception).getErrorCode())
                        .isEqualTo(StoreErrorCode.SCHEDULE_CONFLICT);
            } finally {
                releaseLock.countDown();
            }
            lockHolder.get(5, TimeUnit.SECONDS);
        }

        assertThat(operatingRepository
                .findAllByStoreIdOrderByVersionNumber(ownerStore.storeId()))
                .isEmpty();
        Integer commandCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM idempotency_commands
                        WHERE idempotency_key =
                            '550e8400-e29b-41d4-a716-446655440004'
                        """,
                Integer.class);
        assertThat(commandCount).isZero();
    }

    private OwnerStore createStore() {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create(
                        "schedule-publication@example.com",
                        "hashed",
                        "운영자")).getId();
        long storeId = storeRepository.saveAndFlush(Store.create(
                operatorId,
                "1234567890",
                BusinessType.CAFE,
                "야간 매장",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                Set.of(),
                true,
                true,
                true,
                "Asia/Seoul",
                LocalDateTime.of(2026, 7, 31, 12, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
        return new OwnerStore(operatorId, storeId);
    }

    private WeeklyOperatingHoursRequest operatingRequest(int startHour) {
        return new WeeklyOperatingHoursRequest(
                Arrays.stream(DayOfWeek.values())
                        .map(day -> new DailyOperatingScheduleRequest(
                                day,
                                day == DayOfWeek.MONDAY
                                        ? List.of(new TimeRangeRequest(
                                                LocalTime.of(startHour, 0),
                                                LocalTime.of(startHour + 8, 0)))
                                        : List.of(),
                                List.of()))
                        .toList());
    }

    private WeeklyInterval businessInterval() {
        return new WeeklyInterval(
                DayOfWeek.MONDAY,
                LocalTime.of(18, 0),
                LocalTime.of(2, 0),
                true,
                ScheduleIntervalKind.BUSINESS_HOURS,
                1080,
                1560);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private record OwnerStore(long operatorId, long storeId) {
    }
}
