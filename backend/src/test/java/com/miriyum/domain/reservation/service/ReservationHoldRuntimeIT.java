package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.model.AllergenDisclosure;
import com.miriyum.domain.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.menu.model.AllergenIngredientCode;
import com.miriyum.domain.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.menu.model.MenuContent;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.domain.reservation.dto.ReservationHoldContracts;
import com.miriyum.domain.reservation.dto.request.CapacityBucketRequest;
import com.miriyum.domain.reservation.dto.request.ConsumerCancellationRequest;
import com.miriyum.domain.reservation.dto.request.ReservationCapacitiesRequest;
import com.miriyum.domain.reservation.dto.request.ReservationFulfillmentRequest;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationHold;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.entity.ReservationHoldTransitionAudit;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldTransitionAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldSelection;
import com.miriyum.domain.schedule.closure.entity.RegularClosureVersion;
import com.miriyum.domain.schedule.closure.repository.RegularClosureVersionRepository;
import com.miriyum.domain.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.schedule.entity.StoreScheduleState;
import com.miriyum.domain.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.schedule.model.WeeklyInterval;
import com.miriyum.domain.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.schedule.repository.ReservationScheduleVersionRepository;
import com.miriyum.domain.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-c")
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false",
            "miriyum.reservation.hold-expiration.enabled=false"
        }
)
@Import(ReservationHoldRuntimeIT.MutableClockConfig.class)
class ReservationHoldRuntimeIT {

    private static final String TIME_ZONE_ID = "Asia/Seoul";
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 10);
    private static final LocalDate OTHER_SERVICE_DATE = LocalDate.of(2026, 8, 17);
    private static final LocalTime START_TIME = LocalTime.NOON;
    private static final Instant ACTIVATED_AT = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant BASE_NOW = Instant.parse("2026-08-10T01:00:00Z");
    private static final int PARTY_SIZE = 2;
    private static final long EXECUTOR_TERMINATION_TIMEOUT_SECONDS = 5L;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"))
                    .withCommand("--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private ReservationHoldCommandFacade holdFacade;

    @Autowired
    private ReservationHoldService holdService;

    @Autowired
    private ReservationHoldExpirationService holdExpirationService;

    @Autowired
    private ReservationCapacityCommandFacade capacityFacade;

    @Autowired
    private ReservationCancellationCommandFacade cancellationFacade;

    @Autowired
    private ReservationFulfillmentCommandFacade fulfillmentFacade;

    @Autowired
    private ReservationHoldRepository holdRepository;

    @Autowired
    private ReservationHoldTransitionAuditRepository holdTransitionAuditRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationCapacityAllocationRepository capacityAllocationRepository;

    @Autowired
    private ReservationCapacityBucketRepository capacityBucketRepository;

    @Autowired
    private ConsumerAccountRepository consumerRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreScheduleStateRepository scheduleStateRepository;

    @Autowired
    private OperatingScheduleVersionRepository operatingScheduleRepository;

    @Autowired
    private ReservationScheduleVersionRepository reservationScheduleRepository;

    @Autowired
    private RegularClosureVersionRepository regularClosureRepository;

    @Autowired
    private ReservationTimePolicyVersionRepository timePolicyRepository;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private MenuInventoryBucketRepository menuInventoryBucketRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanRowsInForeignKeyOrder() {
        clock.set(BASE_NOW);
        dropCreationAuditFailureTrigger();
        for (CreationFailurePoint point : CreationFailurePoint.values()) {
            dropFailureTrigger(point);
        }
        jdbcTemplate.execute("DELETE FROM notification_task_transition_audits");
        jdbcTemplate.execute("DELETE FROM notification_channel_attempts");
        jdbcTemplate.execute("DELETE FROM notification_tasks");
        jdbcTemplate.execute("DELETE FROM reservation_hold_warning_tasks");
        jdbcTemplate.execute("DELETE FROM reservation_hold_transition_audits");
        jdbcTemplate.execute("DELETE FROM menu_hold_items");
        jdbcTemplate.execute("DELETE FROM menu_holds");
        jdbcTemplate.execute("DELETE FROM menu_inventory_ledger");
        jdbcTemplate.execute("DELETE FROM reservation_hold_capacity_allocations");
        jdbcTemplate.execute("DELETE FROM reservation_holds");
        jdbcTemplate.execute("DELETE FROM reservation_cancellation_audits");
        jdbcTemplate.execute("DELETE FROM reservation_fulfillment_audits");
        jdbcTemplate.execute("DELETE FROM reservation_capacity_allocations");
        jdbcTemplate.execute("DELETE FROM reservations");
        jdbcTemplate.execute("DELETE FROM reservation_capacity_buckets");
        jdbcTemplate.execute("DELETE FROM menu_inventory_buckets");
        jdbcTemplate.execute("DELETE FROM reservation_time_policy_audits");
        jdbcTemplate.execute("DELETE FROM reservation_time_policy_versions");
        jdbcTemplate.execute("DELETE FROM store_closure_audit_events");
        jdbcTemplate.execute("DELETE FROM store_temporary_closures");
        jdbcTemplate.execute("DELETE FROM store_schedule_audit_events");
        jdbcTemplate.execute("DELETE FROM store_schedule_state");
        jdbcTemplate.execute("DELETE FROM store_regular_closure_entries");
        jdbcTemplate.execute("DELETE FROM store_regular_closure_versions");
        jdbcTemplate.execute("DELETE FROM store_reservation_schedule_entries");
        jdbcTemplate.execute("DELETE FROM store_reservation_schedule_versions");
        jdbcTemplate.execute("DELETE FROM store_operating_schedule_entries");
        jdbcTemplate.execute("DELETE FROM store_operating_schedule_versions");
        jdbcTemplate.execute("DELETE FROM idempotency_commands");
        jdbcTemplate.execute("DELETE FROM menu_publication_events");
        jdbcTemplate.execute("DELETE FROM menu_version_origin_disclosures");
        jdbcTemplate.execute("DELETE FROM menu_version_allergen_disclosures");
        jdbcTemplate.execute("DELETE FROM menu_version_local_tags");
        jdbcTemplate.execute("DELETE FROM menu_version_secondary_categories");
        jdbcTemplate.execute("DELETE FROM menu_versions");
        jdbcTemplate.execute("DELETE FROM menus");
        jdbcTemplate.execute("DELETE FROM store_tag_assignment");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");
    }

    @Test
    @DisplayName("Hold facade는 기존 transaction 안의 호출을 service 위임 전에 거절한다")
    void facadeRejectsAmbientTransactionBeforeServiceAttempt() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                holdFacade.create(null)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                holdFacade.transition(null)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("Task 3 메뉴 선택 생성은 capacity·inventory를 함께 commit·rollback하고 replay 의미를 보존한다")
    void task3MenuSelectionsCommitRollbackAndReplayAtomically() {
        Scenario noMenuScenario = createScenario(10, 5, twoBuckets());
        ReservationHoldContracts.Result noMenu = createHold(
                noMenuScenario, createConsumer(), "task3-no-menu");
        assertAllBucketOccupancy(noMenuScenario.originalBucketIds(), 2, 1);
        assertThat(countWhere("menu_holds", "reservation_hold_id", noMenu.reservationHoldId()))
                .isZero();
        assertThat(count("reservation_hold_capacity_allocations")).isEqualTo(2);
        assertThat(countTransitionAudits(noMenu.reservationHoldId())).isOne();
        assertThat(count("reservation_hold_warning_tasks")).isOne();

        Scenario multipleScenario = createScenario(10, 5, twoBuckets());
        MenuFixture firstMenu = createMenuFixture(multipleScenario, "Task 3 first", 5);
        MenuFixture secondMenu = createMenuFixture(multipleScenario, "Task 3 second", 7);
        long multipleConsumer = createConsumer();
        ReservationHoldContracts.CreateCommand multipleCommand = createCommand(
                multipleScenario,
                multipleConsumer,
                SERVICE_DATE,
                "task3-multiple",
                List.of(
                        new ReservationTemporaryMenuHoldSelection(secondMenu.menuId(), 3),
                        new ReservationTemporaryMenuHoldSelection(firstMenu.menuId(), 2)));

        ReservationHoldContracts.Result multiple = holdFacade.create(multipleCommand);

        assertAllBucketOccupancy(multipleScenario.originalBucketIds(), 2, 1);
        assertThat(onlineRemaining(firstMenu.bucketId())).isEqualTo(3);
        assertThat(onlineRemaining(secondMenu.bucketId())).isEqualTo(4);
        assertThat(countWhere("menu_holds", "reservation_hold_id", multiple.reservationHoldId()))
                .isOne();
        assertThat(jdbcTemplate.queryForList("""
                SELECT item.menu_id, item.quantity
                  FROM menu_hold_items item
                  JOIN menu_holds hold ON hold.menu_hold_id = item.menu_hold_id
                 WHERE hold.reservation_hold_id = ?
                 ORDER BY item.menu_id
                """, multiple.reservationHoldId()))
                .extracting(row -> List.of(row.get("menu_id"), row.get("quantity")))
                .containsExactly(
                        List.of(firstMenu.menuId(), 2),
                        List.of(secondMenu.menuId(), 3));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT expires_at = (SELECT expires_at FROM reservation_holds
                                      WHERE reservation_hold_id = ?)
                  FROM menu_holds
                 WHERE reservation_hold_id = ?
                """, Boolean.class, multiple.reservationHoldId(),
                multiple.reservationHoldId())).isTrue();
        assertThat(ledgerCountFor(List.of(firstMenu.bucketId(), secondMenu.bucketId())))
                .isEqualTo(2);
        assertThat(countHoldAllocations(multiple.reservationHoldId())).isEqualTo(2);
        assertThat(countTransitionAudits(multiple.reservationHoldId())).isOne();
        assertThat(countWarningTasks(multiple.reservationHoldId())).isOne();

        Scenario insufficientScenario = createScenario(10, 5, twoBuckets());
        MenuFixture sufficient = createMenuFixture(insufficientScenario, "Task 3 enough", 5);
        MenuFixture insufficient = createMenuFixture(insufficientScenario, "Task 3 short", 1);
        GroupArtifactCounts beforeInsufficient = groupArtifactCounts();
        assertThatThrownBy(() -> holdFacade.create(createCommand(
                insufficientScenario,
                createConsumer(),
                SERVICE_DATE,
                "task3-insufficient",
                List.of(
                        new ReservationTemporaryMenuHoldSelection(sufficient.menuId(), 2),
                        new ReservationTemporaryMenuHoldSelection(insufficient.menuId(), 2)))))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(MenuHoldErrorCode.INSUFFICIENT_QUANTITY));
        assertAllBucketOccupancy(insufficientScenario.originalBucketIds(), 0, 0);
        assertThat(onlineRemaining(sufficient.bucketId())).isEqualTo(5);
        assertThat(onlineRemaining(insufficient.bucketId())).isOne();
        assertThat(ledgerCountFor(List.of(sufficient.bucketId(), insufficient.bucketId())))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_holds WHERE creation_command_id = ?",
                Integer.class, "task3-insufficient")).isZero();
        assertThat(groupArtifactCounts()).isEqualTo(beforeInsufficient);

        Scenario persistenceFailureScenario = createScenario(10, 5, twoBuckets());
        MenuFixture persistenceMenu = createMenuFixture(
                persistenceFailureScenario, "Task 3 persistence", 5);
        GroupArtifactCounts beforePersistenceFailure = groupArtifactCounts();
        createFailureTrigger(CreationFailurePoint.MENU_HOLD_INSERT);
        try {
            assertThatThrownBy(() -> holdFacade.create(createCommand(
                    persistenceFailureScenario,
                    createConsumer(),
                    SERVICE_DATE,
                    "task3-persistence-failure",
                    List.of(new ReservationTemporaryMenuHoldSelection(
                            persistenceMenu.menuId(), 2)))))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            dropFailureTrigger(CreationFailurePoint.MENU_HOLD_INSERT);
        }
        assertAllBucketOccupancy(persistenceFailureScenario.originalBucketIds(), 0, 0);
        assertThat(onlineRemaining(persistenceMenu.bucketId())).isEqualTo(5);
        assertThat(ledgerCountFor(List.of(persistenceMenu.bucketId()))).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_holds WHERE creation_command_id = ?",
                Integer.class, "task3-persistence-failure")).isZero();
        assertThat(groupArtifactCounts()).isEqualTo(beforePersistenceFailure);

        Task3CreationSnapshot beforeStableReplay = task3CreationSnapshot(
                multipleScenario,
                multiple.reservationHoldId(),
                List.of(firstMenu.bucketId(), secondMenu.bucketId()));
        ReservationHoldContracts.Result replay = holdFacade.create(multipleCommand);
        assertThat(replay).isEqualTo(multiple);
        assertThat(task3CreationSnapshot(
                multipleScenario,
                multiple.reservationHoldId(),
                List.of(firstMenu.bucketId(), secondMenu.bucketId())))
                .isEqualTo(beforeStableReplay);
        assertThatThrownBy(() -> holdFacade.create(createCommand(
                multipleScenario,
                multipleConsumer,
                SERVICE_DATE,
                "task3-multiple",
                List.of(new ReservationTemporaryMenuHoldSelection(firstMenu.menuId(), 3)))))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                com.miriyum.global.exception.CommonErrorCode
                                        .IDEMPOTENCY_KEY_REUSED));
        assertThat(onlineRemaining(firstMenu.bucketId())).isEqualTo(3);
        assertThat(onlineRemaining(secondMenu.bucketId())).isEqualTo(4);
        assertThat(task3CreationSnapshot(
                multipleScenario,
                multiple.reservationHoldId(),
                List.of(firstMenu.bucketId(), secondMenu.bucketId())))
                .isEqualTo(beforeStableReplay);
    }

    @Test
    @DisplayName("재게시 보호 조회는 보호 상태만 매장·날짜 범위에서 PK 오름차순 반환한다")
    void protectedPublicationQueryReturnsOnlyProtectedStatusesInPrimaryKeyOrder() {
        Scenario scenario = createScenario(20, 10, twoBuckets());
        List<ReservationHoldContracts.Result> results = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            results.add(createHold(scenario, createConsumer(), "scope-" + index));
        }
        transition(results.get(1), ReservationHoldStatus.RECONCILIATION_REQUIRED, "scope-reconcile");
        transition(results.get(2), ReservationHoldStatus.CONFIRMED, "scope-confirm");
        transition(results.get(3), ReservationHoldStatus.RELEASED, "scope-release");
        clock.set(results.get(4).expiresAt());
        transition(results.get(4), ReservationHoldStatus.EXPIRED, "scope-expire");
        Scenario otherStore = createScenario(20, 10, twoBuckets());
        ReservationHoldContracts.Result otherStoreHold = createHold(
                otherStore, createConsumer(), "scope-other-store");
        List<Long> otherDateBucketIds = seedCapacityBuckets(
                scenario.storeId(), OTHER_SERVICE_DATE, 20, 10, twoBuckets(), 1L);
        ReservationHoldContracts.Result otherDateHold = holdFacade.create(createCommand(
                scenario,
                createConsumer(),
                OTHER_SERVICE_DATE,
                "scope-other-date"));

        List<ReservationHold> protectedHolds = transactions.execute(status ->
                holdRepository.findProtectedByStoreIdAndServiceDateForUpdateOrderByIdAsc(
                        scenario.storeId(), SERVICE_DATE));

        assertThat(count("reservation_holds")).isEqualTo(7);
        assertThat(otherStoreHold.storeId()).isEqualTo(otherStore.storeId());
        assertThat(otherDateHold.serviceDate()).isEqualTo(OTHER_SERVICE_DATE);
        assertAllBucketOccupancy(otherDateBucketIds, 2, 1);
        assertThat(protectedHolds)
                .extracting(ReservationHold::getId)
                .containsExactly(
                        results.get(0).reservationHoldId(),
                        results.get(1).reservationHoldId(),
                        results.get(2).reservationHoldId());
        assertThat(protectedHolds)
                .extracting(ReservationHold::getStatus)
                .containsExactly(
                        ReservationHoldStatus.ACTIVE,
                        ReservationHoldStatus.RECONCILIATION_REQUIRED,
                        ReservationHoldStatus.CONFIRMED);
    }

    @Test
    @DisplayName("같은 생성 command ID는 소비자 범위로 분리되어 서로 다른 Hold를 만든다")
    void creationReplayScopeIsSeparatedByConsumerInMySql() {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        long firstConsumer = createConsumer();
        long secondConsumer = createConsumer();

        ReservationHoldContracts.Result first = createHold(
                scenario, firstConsumer, "consumer-scoped-command");
        ReservationHoldContracts.Result second = createHold(
                scenario, secondConsumer, "consumer-scoped-command");

        assertThat(first.reservationHoldId()).isNotEqualTo(second.reservationHoldId());
        assertThat(count("reservation_holds")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
                SELECT consumer_account_id, creation_command_id
                  FROM reservation_holds
                 ORDER BY consumer_account_id
                """))
                .extracting(row -> row.get("creation_command_id"))
                .containsOnly("consumer-scoped-command");
        assertAllBucketOccupancy(scenario.originalBucketIds(), 4, 2);
    }

    @Test
    @DisplayName("MySQL ai_ci가 찾은 case·accent 변형 command IDs는 exact replay가 아니므로 COMMON_007이다")
    void mysqlCollationVariantsAreRejectedByExactCommandIdMeaning() {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        long consumerId = createConsumer();
        ReservationHoldContracts.Result active = createHold(
                scenario, consumerId, "Hold-Create-É");

        assertThatThrownBy(() -> createHold(scenario, consumerId, "hold-create-e"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                com.miriyum.global.exception.CommonErrorCode
                                        .IDEMPOTENCY_KEY_REUSED));
        ReservationHoldContracts.Result confirmed = transition(
                active,
                ReservationHoldStatus.CONFIRMED,
                "Hold-Transition-É");
        assertThatThrownBy(() -> transition(
                active,
                ReservationHoldStatus.CONFIRMED,
                "hold-transition-e"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                com.miriyum.global.exception.CommonErrorCode
                                        .IDEMPOTENCY_KEY_REUSED));

        assertThat(confirmed.status()).isEqualTo(ReservationHoldStatus.CONFIRMED);
        assertThat(count("reservation_holds")).isOne();
        assertThat(countTransitionAudits(active.reservationHoldId())).isEqualTo(2);
        assertAllBucketOccupancy(scenario.originalBucketIds(), 2, 1);
    }

    @Test
    @DisplayName("fresh 전이 응답 version은 flush된 행과 operation replay에서 동일하다")
    void transitionResultVersionMatchesFlushedRowAndReplay() {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        ReservationHoldContracts.Result active = createHold(
                scenario, createConsumer(), "version-create");
        ReservationHoldContracts.TransitionCommand command = transitionCommand(
                active, ReservationHoldStatus.RECONCILIATION_REQUIRED, "version-transition");

        ReservationHoldContracts.Result fresh = holdFacade.transition(command);
        long storedVersion = jdbcTemplate.queryForObject(
                "SELECT status_version FROM reservation_holds WHERE reservation_hold_id = ?",
                Long.class,
                active.reservationHoldId());
        ReservationHoldContracts.Result replay = holdFacade.transition(command);

        assertThat(active.statusVersion()).isZero();
        assertThat(fresh.statusVersion()).isEqualTo(1L);
        assertThat(storedVersion).isEqualTo(1L);
        assertThat(replay).isEqualTo(fresh);
        assertThat(countTransitionAudits(active.reservationHoldId())).isEqualTo(2);
    }

    @Test
    @DisplayName("transition replay는 MySQL microsecond 명령 의미만 재생하고 감사 의미 충돌은 부작용 없이 거절한다")
    void transitionReplayUsesCompleteMicrosecondNormalizedAuditMeaning() {
        Instant requestedAt = BASE_NOW.plusNanos(123_456_789);
        Instant normalizedRequestedAt = requestedAt.truncatedTo(ChronoUnit.MICROS);
        clock.set(requestedAt);
        Scenario scenario = createScenario(10, 5, twoBuckets());
        ReservationHoldContracts.Result active = createHold(
                scenario, createConsumer(), "audit-meaning-create");
        String operationId = "audit-meaning-transition";
        ReservationHoldContracts.TransitionCommand freshCommand =
                new ReservationHoldContracts.TransitionCommand(
                        active.reservationHoldId(),
                        ReservationHoldStatus.RECONCILIATION_REQUIRED,
                        operationId,
                        "  PAYMENT  ",
                        41L,
                        requestedAt);

        ReservationHoldContracts.Result fresh = holdFacade.transition(freshCommand);
        ReservationHoldContracts.TransitionCommand sameMicrosecondReplay =
                new ReservationHoldContracts.TransitionCommand(
                        active.reservationHoldId(),
                        ReservationHoldStatus.RECONCILIATION_REQUIRED,
                        operationId,
                        "PAYMENT",
                        41L,
                        normalizedRequestedAt.plusNanos(999));
        ReservationHoldContracts.Result replay = holdFacade.transition(sameMicrosecondReplay);
        List<ReservationHoldContracts.TransitionCommand> conflicts = List.of(
                new ReservationHoldContracts.TransitionCommand(
                        active.reservationHoldId(),
                        ReservationHoldStatus.RECONCILIATION_REQUIRED,
                        operationId,
                        "WORKER",
                        41L,
                        normalizedRequestedAt),
                new ReservationHoldContracts.TransitionCommand(
                        active.reservationHoldId(),
                        ReservationHoldStatus.RECONCILIATION_REQUIRED,
                        operationId,
                        "PAYMENT",
                        42L,
                        normalizedRequestedAt),
                new ReservationHoldContracts.TransitionCommand(
                        active.reservationHoldId(),
                        ReservationHoldStatus.RECONCILIATION_REQUIRED,
                        operationId,
                        "PAYMENT",
                        41L,
                        normalizedRequestedAt.plus(1, ChronoUnit.MICROS)));
        List<Throwable> conflictResults = conflicts.stream()
                .map(command -> catchThrowable(() -> holdFacade.transition(command)))
                .toList();

        assertThat(replay).isEqualTo(fresh);
        assertThat(conflictResults)
                .allSatisfy(throwable -> assertThat(throwable)
                        .isInstanceOfSatisfying(ServiceException.class, exception ->
                                assertThat(exception.getErrorCode()).isEqualTo(
                                        com.miriyum.global.exception.CommonErrorCode
                                                .IDEMPOTENCY_KEY_REUSED)));
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, status_version
                  FROM reservation_holds
                 WHERE reservation_hold_id = ?
                """, active.reservationHoldId()))
                .containsEntry("status", "RECONCILIATION_REQUIRED")
                .containsEntry("status_version", 1L);
        assertAllBucketOccupancy(scenario.originalBucketIds(), PARTY_SIZE, 1);
        assertThat(countTransitionAudits(active.reservationHoldId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT actor_type, actor_id, requested_at
                  FROM reservation_hold_transition_audits
                 WHERE command_id = ?
                """, operationId))
                .containsEntry("actor_type", "PAYMENT")
                .containsEntry("actor_id", 41L)
                .containsEntry(
                        "requested_at",
                        LocalDateTime.ofInstant(normalizedRequestedAt, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("다중 구간 fresh 생성은 Hold·배정·감사·경고와 10분·2분 시각을 원자 확정한다")
    void multiBucketFreshCreationCommitsAllAtomicSnapshots() {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        long consumerId = createConsumer();
        String creationCommandId = "atomic-create";

        ReservationHoldContracts.Result result = createHold(
                scenario, consumerId, creationCommandId);

        assertThat(jdbcTemplate.queryForObject("SELECT VERSION()", String.class))
                .startsWith("8.0.40");
        assertThat(result.status()).isEqualTo(ReservationHoldStatus.ACTIVE);
        assertThat(result.createdAt()).isEqualTo(BASE_NOW);
        assertThat(result.expiresAt()).isEqualTo(BASE_NOW.plusSeconds(600));
        assertThat(count("reservation_holds")).isOne();
        assertThat(count("reservation_hold_capacity_allocations")).isEqualTo(2);
        assertThat(countTransitionAudits(result.reservationHoldId())).isOne();
        assertThat(count("reservation_hold_warning_tasks")).isOne();
        Map<String, Object> persistedHold = jdbcTemplate.queryForMap("""
                SELECT consumer_account_id, store_id, service_date,
                       start_at, service_end_at, occupancy_end_at,
                       reservation_policy_version, capacity_policy_version,
                       cancellation_policy_version, status, status_version,
                       creation_command_id, created_at, expires_at
                  FROM reservation_holds
                 WHERE reservation_hold_id = ?
                """, result.reservationHoldId());
        assertThat(persistedHold)
                .containsEntry("consumer_account_id", consumerId)
                .containsEntry("store_id", scenario.storeId())
                .containsEntry("service_date", java.sql.Date.valueOf(SERVICE_DATE))
                .containsEntry("start_at", LocalDateTime.of(2026, 8, 10, 3, 0))
                .containsEntry("service_end_at", LocalDateTime.of(2026, 8, 10, 4, 0))
                .containsEntry("occupancy_end_at", LocalDateTime.of(2026, 8, 10, 4, 0))
                .containsEntry("reservation_policy_version", 1L)
                .containsEntry("capacity_policy_version", 1L)
                .containsEntry("cancellation_policy_version", 1L)
                .containsEntry("status", "ACTIVE")
                .containsEntry("status_version", 0L)
                .containsEntry("creation_command_id", creationCommandId)
                .containsEntry("created_at", LocalDateTime.of(2026, 8, 10, 1, 0))
                .containsEntry("expires_at", LocalDateTime.of(2026, 8, 10, 1, 10));
        Map<String, Object> creationAudit = jdbcTemplate.queryForMap("""
                SELECT actor_type, actor_id, requested_at, occurred_at,
                       before_status, after_status,
                       reservation_time_policy_version, capacity_policy_version,
                       command_id
                  FROM reservation_hold_transition_audits
                 WHERE reservation_hold_id = ?
                """, result.reservationHoldId());
        assertThat(creationAudit)
                .containsEntry("actor_type", "SYSTEM")
                .containsEntry("requested_at", LocalDateTime.of(2026, 8, 10, 1, 0))
                .containsEntry("occurred_at", LocalDateTime.of(2026, 8, 10, 1, 0))
                .containsEntry("after_status", "ACTIVE")
                .containsEntry("reservation_time_policy_version", 1L)
                .containsEntry("capacity_policy_version", 1L);
        assertThat(creationAudit.get("actor_id")).isNull();
        assertThat(creationAudit.get("before_status")).isNull();
        assertThat((String) creationAudit.get("command_id"))
                .isNotBlank()
                .isNotEqualTo(creationCommandId);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT created_at, warning_due_at
                  FROM reservation_hold_warning_tasks
                 WHERE reservation_hold_id = ?
                """, result.reservationHoldId()))
                .containsEntry("created_at", LocalDateTime.of(2026, 8, 10, 1, 0))
                .containsEntry("warning_due_at", LocalDateTime.of(2026, 8, 10, 1, 8));
        assertThat(jdbcTemplate.queryForList("""
                SELECT occupied_people, occupied_teams, capacity_policy_version
                  FROM reservation_hold_capacity_allocations
                 WHERE reservation_hold_id = ?
                 ORDER BY reservation_capacity_bucket_id
                """, result.reservationHoldId()))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row)
                        .containsEntry("occupied_people", 2)
                        .containsEntry("occupied_teams", 1)
                        .containsEntry("capacity_policy_version", 1L));
        assertAllBucketOccupancy(scenario.originalBucketIds(), 2, 1);
    }

    @ParameterizedTest
    @EnumSource(
            value = CreationFailurePoint.class,
            names = {"ALLOCATION_INSERT", "WARNING_INSERT"}
    )
    @DisplayName("allocation 또는 warning DB 저장 실패는 점유와 Hold를 포함한 생성 전체를 롤백한다")
    void allocationOrWarningPersistenceFailureRollsBackAllCreationEffects(
            CreationFailurePoint point
    ) {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        long consumerId = createConsumer();
        Throwable failure;
        try {
            createFailureTrigger(point);
            failure = catchThrowable(() -> createHold(
                    scenario, consumerId, "rollback-" + point.name()));
        } finally {
            dropFailureTrigger(point);
        }

        assertThat(failure).isNotNull();
        assertThat(requireCause(failure, SQLException.class).getSQLState()).isEqualTo("45000");
        assertThat(count("reservation_holds")).isZero();
        assertThat(count("reservation_hold_capacity_allocations")).isZero();
        assertThat(count("reservation_hold_transition_audits")).isZero();
        assertThat(count("reservation_hold_warning_tasks")).isZero();
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);

        ReservationHoldContracts.Result retry = createHold(
                scenario, consumerId, "rollback-" + point.name());
        assertThat(retry.status()).isEqualTo(ReservationHoldStatus.ACTIVE);
        assertAllBucketOccupancy(scenario.originalBucketIds(), 2, 1);
    }

    @Test
    @DisplayName("같은 생성 command 병렬 제출은 Store 잠금 뒤 하나의 Hold 결과와 효과로 수렴한다")
    void concurrentSameCreationCommandConvergesToOneHold() throws Exception {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        long consumerId = createConsumer();
        ReservationHoldContracts.CreateCommand command = createCommand(
                scenario, consumerId, "same-command-race");

        List<HoldAttempt> attempts = invokeTwoWhileRowLocked(
                "stores",
                "store_id",
                scenario.storeId(),
                () -> invokeCreate(command),
                () -> invokeCreate(command));

        assertThat(attempts).allSatisfy(attempt -> {
            assertThat(attempt.errorCode()).isNull();
            assertThat(attempt.result()).isNotNull();
        });
        assertThat(attempts)
                .extracting(attempt -> attempt.result().reservationHoldId())
                .containsOnly(attempts.getFirst().result().reservationHoldId());
        assertThat(count("reservation_holds")).isOne();
        assertThat(count("reservation_hold_capacity_allocations")).isEqualTo(2);
        assertThat(count("reservation_hold_transition_audits")).isOne();
        assertThat(count("reservation_hold_warning_tasks")).isOne();
        assertAllBucketOccupancy(scenario.originalBucketIds(), 2, 1);
    }

    @Test
    @DisplayName("nanosecond Clock 생성은 MySQL microsecond fresh·replay와 만료 경계를 동일하게 만든다")
    void nanosecondClockUsesPersistedMicrosecondForFreshReplayAndExpiryBoundary() {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        long consumerId = createConsumer();
        String commandId = "microsecond-create";
        Instant requestedNow = Instant.parse("2026-08-10T01:00:00.123456789Z");
        Instant expectedCreatedAt = Instant.parse("2026-08-10T01:00:00.123456Z");
        clock.set(requestedNow);

        ReservationHoldContracts.Result fresh = createHold(scenario, consumerId, commandId);
        ReservationHoldContracts.Result replay = createHold(scenario, consumerId, commandId);

        assertThat(fresh.createdAt()).isEqualTo(expectedCreatedAt);
        assertThat(fresh.expiresAt()).isEqualTo(expectedCreatedAt.plusSeconds(600));
        assertThat(replay).isEqualTo(fresh);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT created_at, expires_at
                  FROM reservation_holds
                 WHERE reservation_hold_id = ?
                """, fresh.reservationHoldId()))
                .containsEntry(
                        "created_at",
                        LocalDateTime.of(2026, 8, 10, 1, 0, 0, 123_456_000))
                .containsEntry(
                        "expires_at",
                        LocalDateTime.of(2026, 8, 10, 1, 10, 0, 123_456_000));

        clock.set(fresh.expiresAt().minusNanos(1));
        ReservationHoldContracts.TransitionCommand expiry = transitionCommand(
                fresh, ReservationHoldStatus.EXPIRED, "microsecond-expiry");
        assertThatThrownBy(() -> holdFacade.transition(expiry))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ReservationErrorCode.INVALID_STATE_TRANSITION));
        clock.set(fresh.expiresAt());
        ReservationHoldContracts.Result expired = holdFacade.transition(expiry);

        assertThat(expired.status()).isEqualTo(ReservationHoldStatus.EXPIRED);
        assertThat(currentStatus(fresh.reservationHoldId())).isEqualTo("EXPIRED");
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
    }

    @Test
    @DisplayName("서로 다른 Store의 같은 생성 command 경합은 qualified 1062 뒤 COMMON_007로 수렴한다")
    void crossStoreCreationCommandRaceReplaysQualifiedUniqueConflict() throws Exception {
        Scenario firstScenario = createScenario(10, 5, twoBuckets());
        Scenario secondScenario = createScenario(10, 5, twoBuckets());
        long consumerId = createConsumer();
        String commandId = "cross-store-command-race";
        ReservationHoldContracts.CreateCommand first = createCommand(
                firstScenario, consumerId, commandId);
        ReservationHoldContracts.CreateCommand second = createCommand(
                secondScenario, consumerId, commandId);

        List<HoldAttempt> attempts = invokeTwoWhileRowsLocked(
                "reservation_capacity_buckets",
                "reservation_capacity_bucket_id",
                Stream.concat(
                                firstScenario.originalBucketIds().stream(),
                                secondScenario.originalBucketIds().stream())
                        .toList(),
                () -> invokeCreate(first),
                () -> invokeCreate(second));

        HoldAttempt success = attempts.stream()
                .filter(attempt -> attempt.result() != null)
                .findFirst()
                .orElseThrow();
        assertThat(attempts).filteredOn(attempt -> attempt.result() != null).hasSize(1);
        assertThat(attempts)
                .filteredOn(attempt -> attempt.errorCode() != null)
                .singleElement()
                .extracting(HoldAttempt::errorCode)
                .isEqualTo(com.miriyum.global.exception.CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(count("reservation_holds")).isOne();
        assertThat(count("reservation_hold_capacity_allocations")).isEqualTo(2);
        assertThat(count("reservation_hold_transition_audits")).isOne();
        assertThat(count("reservation_hold_warning_tasks")).isOne();
        if (success.result().storeId() == firstScenario.storeId()) {
            assertAllBucketOccupancy(firstScenario.originalBucketIds(), 2, 1);
            assertAllBucketOccupancy(secondScenario.originalBucketIds(), 0, 0);
        } else {
            assertThat(success.result().storeId()).isEqualTo(secondScenario.storeId());
            assertAllBucketOccupancy(firstScenario.originalBucketIds(), 0, 0);
            assertAllBucketOccupancy(secondScenario.originalBucketIds(), 2, 1);
        }
    }

    @Test
    @DisplayName("마지막 수용량을 다른 소비자가 병렬 선점하면 성공 한 건과 초과 판매 0건이다")
    void concurrentDifferentConsumersCompetingForLastCapacityAllowOneHold() throws Exception {
        Scenario scenario = createScenario(2, 1, twoBuckets());
        ReservationHoldContracts.CreateCommand first = createCommand(
                scenario, createConsumer(), "last-capacity-first");
        ReservationHoldContracts.CreateCommand second = createCommand(
                scenario, createConsumer(), "last-capacity-second");

        List<HoldAttempt> attempts = invokeTwoWhileRowLocked(
                "stores",
                "store_id",
                scenario.storeId(),
                () -> invokeCreate(first),
                () -> invokeCreate(second));

        assertThat(attempts).filteredOn(attempt -> attempt.result() != null).hasSize(1);
        assertThat(attempts)
                .filteredOn(attempt -> attempt.errorCode() != null)
                .singleElement()
                .extracting(HoldAttempt::errorCode)
                .isEqualTo(ReservationErrorCode.INSUFFICIENT_CAPACITY);
        assertThat(count("reservation_holds")).isOne();
        assertThat(count("reservation_hold_capacity_allocations")).isEqualTo(2);
        assertAllBucketOccupancy(scenario.originalBucketIds(), 2, 1);
    }

    @Test
    @DisplayName("마지막 수용량과 메뉴 수량 경합은 완전한 선점 그룹 하나만 commit한다")
    void concurrentConsumersCompetingForLastCapacityAndMenuCommitOneCompleteGroup()
            throws Exception {
        Scenario scenario = createScenario(2, 1, twoBuckets());
        MenuFixture menu = createMenuFixture(scenario, "Task 5 last group", 1);
        ReservationHoldContracts.CreateCommand first = createCommand(
                scenario,
                createConsumer(),
                SERVICE_DATE,
                "task5-last-group-first",
                List.of(new ReservationTemporaryMenuHoldSelection(menu.menuId(), 1)));
        ReservationHoldContracts.CreateCommand second = createCommand(
                scenario,
                createConsumer(),
                SERVICE_DATE,
                "task5-last-group-second",
                List.of(new ReservationTemporaryMenuHoldSelection(menu.menuId(), 1)));

        List<HoldAttempt> attempts = invokeTwoWhileRowLocked(
                "stores",
                "store_id",
                scenario.storeId(),
                () -> invokeCreate(first),
                () -> invokeCreate(second));

        HoldAttempt winner = attempts.stream()
                .filter(attempt -> attempt.result() != null)
                .findFirst()
                .orElseThrow();
        assertThat(attempts).filteredOn(attempt -> attempt.result() != null).hasSize(1);
        assertThat(attempts)
                .filteredOn(attempt -> attempt.errorCode() != null)
                .singleElement()
                .extracting(HoldAttempt::errorCode)
                .isEqualTo(ReservationErrorCode.INSUFFICIENT_CAPACITY);
        assertThat(groupArtifactCounts()).isEqualTo(
                new GroupArtifactCounts(1, 2, 1, 1, 1, 1, 1));
        assertAllBucketOccupancy(scenario.originalBucketIds(), PARTY_SIZE, 1);
        assertThat(onlineRemaining(menu.bucketId())).isZero();
        assertThat(temporaryMenuHoldStatus(winner.result().reservationHoldId()))
                .isEqualTo("ACTIVE");
        assertThat(acquireLedgerCount(winner.result().reservationHoldId())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_holds
                 WHERE creation_command_id IN (?, ?)
                """, Integer.class,
                first.creationCommandId(), second.creationCommandId())).isOne();
    }

    @Test
    @DisplayName("메뉴가 있는 같은 생성 command 경합은 하나의 그룹과 stable replay로 수렴한다")
    void concurrentSameCreateCommandWithMenuConvergesToStableReplay() throws Exception {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        MenuFixture menu = createMenuFixture(scenario, "Task 5 replay", 2);
        ReservationHoldContracts.CreateCommand command = createCommand(
                scenario,
                createConsumer(),
                SERVICE_DATE,
                "task5-same-menu-command",
                List.of(new ReservationTemporaryMenuHoldSelection(menu.menuId(), 1)));

        List<HoldAttempt> attempts = invokeTwoWhileRowLocked(
                "stores",
                "store_id",
                scenario.storeId(),
                () -> invokeCreate(command),
                () -> invokeCreate(command));

        assertThat(attempts).allSatisfy(attempt -> {
            assertThat(attempt.errorCode()).isNull();
            assertThat(attempt.result()).isNotNull();
        });
        assertThat(attempts).extracting(HoldAttempt::result)
                .containsOnly(attempts.getFirst().result());
        ReservationHoldContracts.Result result = attempts.getFirst().result();
        assertThat(groupArtifactCounts()).isEqualTo(
                new GroupArtifactCounts(1, 2, 1, 1, 1, 1, 1));
        assertThat(acquireLedgerCount(result.reservationHoldId())).isOne();
        assertAllBucketOccupancy(scenario.originalBucketIds(), PARTY_SIZE, 1);
        assertThat(onlineRemaining(menu.bucketId())).isOne();
        Task3CreationSnapshot beforeReplay = task3CreationSnapshot(
                scenario, result.reservationHoldId(), List.of(menu.bucketId()));

        ReservationHoldContracts.Result replay = holdFacade.create(command);

        assertThat(replay).isEqualTo(result);
        assertThat(task3CreationSnapshot(
                scenario, result.reservationHoldId(), List.of(menu.bucketId())))
                .isEqualTo(beforeReplay);
    }

    @Test
    @DisplayName("메뉴 재고 mutation 뒤 caller 실패는 선점 그룹 전체를 rollback한다")
    void callerFailureAfterInventoryMutationRollsBackWholeGroup() {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        MenuFixture menu = createMenuFixture(scenario, "Task 5 caller rollback", 2);
        ReservationHoldContracts.CreateCommand command = createCommand(
                scenario,
                createConsumer(),
                SERVICE_DATE,
                "task5-post-inventory-failure",
                List.of(new ReservationTemporaryMenuHoldSelection(menu.menuId(), 1)));
        createCreationAuditFailureTrigger();
        Throwable failure;
        try {
            failure = catchThrowable(() -> holdFacade.create(command));
        } finally {
            dropCreationAuditFailureTrigger();
        }

        assertThat(failure).isNotNull();
        assertThat(requireCause(failure, SQLException.class).getSQLState()).isEqualTo("45000");
        assertThat(groupArtifactCounts()).isEqualTo(
                new GroupArtifactCounts(0, 0, 0, 0, 0, 0, 0));
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
        assertThat(onlineRemaining(menu.bucketId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_holds
                 WHERE creation_command_id = ?
                """, Integer.class, command.creationCommandId())).isZero();
    }

    @Test
    @DisplayName("만료 정각의 release와 expire 경합은 같은 완전 만료 결과로 수렴한다")
    void releaseAndExpireRaceCommitsOneTerminalGroupAndRestoresOnce() throws Exception {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        MenuFixture menu = createMenuFixture(scenario, "Task 5 release expire", 1);
        ReservationHoldContracts.Result active = createMenuHoldGroup(
                scenario, menu, "task5-release-expire-create");
        clock.set(active.expiresAt());
        ReservationHoldContracts.TransitionCommand release = transitionCommand(
                active, ReservationHoldStatus.RELEASED, "task5-release-race");
        ReservationHoldContracts.TransitionCommand expire = transitionCommand(
                active, ReservationHoldStatus.EXPIRED, "task5-expire-race");

        List<HoldAttempt> attempts = invokeTwoWhileRowLocked(
                "reservation_holds",
                "reservation_hold_id",
                active.reservationHoldId(),
                () -> invokeTransition(release),
                () -> invokeTransition(expire));

        assertThat(attempts)
                .extracting(HoldAttempt::result)
                .doesNotContainNull()
                .extracting(ReservationHoldContracts.Result::status)
                .containsOnly(ReservationHoldStatus.EXPIRED);
        assertThat(attempts)
                .extracting(HoldAttempt::errorCode)
                .containsOnlyNulls();
        assertThat(currentStatus(active.reservationHoldId())).isEqualTo("EXPIRED");
        assertThat(temporaryMenuHoldStatus(active.reservationHoldId())).isEqualTo("EXPIRED");
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
        assertThat(onlineRemaining(menu.bucketId())).isOne();
        assertThat(restoreLedgerCount(active.reservationHoldId())).isOne();
        assertThat(countTransitionAudits(active.reservationHoldId())).isEqualTo(2);
        assertThat(terminalAuditCount(active.reservationHoldId())).isOne();
    }

    @Test
    @DisplayName("만료 정각의 confirm과 expire 경합은 같은 완전 만료 결과로 수렴한다")
    void confirmAndExpireRaceNeverCommitsMixedGroupState() throws Exception {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        MenuFixture menu = createMenuFixture(scenario, "Task 5 confirm expire", 1);
        ReservationHoldContracts.Result active = createMenuHoldGroup(
                scenario, menu, "task5-confirm-expire-create");
        long finalReservationId = seedFinalReservation(scenario);
        clock.set(active.expiresAt());
        ReservationHoldContracts.TransitionCommand confirm = transitionCommand(
                active,
                ReservationHoldStatus.CONFIRMED,
                "task5-confirm-race",
                finalReservationId);
        ReservationHoldContracts.TransitionCommand expire = transitionCommand(
                active, ReservationHoldStatus.EXPIRED, "task5-confirm-expire-race");

        List<HoldAttempt> attempts = invokeTwoWhileRowLocked(
                "reservation_holds",
                "reservation_hold_id",
                active.reservationHoldId(),
                () -> invokeTransition(confirm),
                () -> invokeTransition(expire));

        assertThat(attempts)
                .extracting(HoldAttempt::result)
                .doesNotContainNull()
                .extracting(ReservationHoldContracts.Result::status)
                .containsOnly(ReservationHoldStatus.EXPIRED);
        assertThat(attempts)
                .extracting(HoldAttempt::errorCode)
                .containsOnlyNulls();
        assertThat(countTransitionAudits(active.reservationHoldId())).isEqualTo(2);
        assertThat(terminalAuditCount(active.reservationHoldId())).isOne();
        assertThat(currentStatus(active.reservationHoldId())).isEqualTo("EXPIRED");
        assertThat(temporaryMenuHoldStatus(active.reservationHoldId()))
                .isEqualTo("EXPIRED");
        assertThat(temporaryMenuHoldReservationId(active.reservationHoldId())).isNull();
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
        assertThat(onlineRemaining(menu.bucketId())).isOne();
        assertThat(restoreLedgerCount(active.reservationHoldId())).isOne();
    }

    @Test
    @DisplayName("확정된 임시 MenuHold는 최종 예약 취소에서 수량을 한 번만 복구한다")
    void confirmedTemporaryMenuHoldCancellationRestoresInventoryExactlyOnce() {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        MenuFixture menu = createMenuFixture(scenario, "Confirmed temporary cancellation", 1);
        ReservationHoldContracts.CreateCommand creationCommand = createCommand(
                scenario,
                createConsumer(),
                SERVICE_DATE,
                "confirmed-temporary-cancellation-create",
                List.of(new ReservationTemporaryMenuHoldSelection(menu.menuId(), 1)));
        ReservationHoldContracts.Result active = holdFacade.create(creationCommand);
        long finalReservationId = seedFinalReservation(
                scenario, active.consumerAccountId());
        ReservationHoldContracts.TransitionCommand confirmationCommand = transitionCommand(
                active,
                ReservationHoldStatus.CONFIRMED,
                "confirmed-temporary-cancellation-confirm",
                finalReservationId);
        ReservationHoldContracts.Result confirmed = holdFacade.transition(confirmationCommand);
        transferCapacityToFinalReservation(active, scenario, finalReservationId);
        IdempotencyKey cancellationKey = key(201);
        ConsumerCancellationRequest request =
                new ConsumerCancellationRequest("confirmed temporary hold cancellation");

        ReservationCancellationCommandResult cancelled = cancellationFacade.cancelByConsumer(
                active.consumerAccountId(),
                finalReservationId,
                cancellationKey,
                request);

        assertThat(cancelled.httpStatus()).isEqualTo(200);
        assertThat(cancelled.data().status()).isEqualTo("CANCELLED");
        assertThat(temporaryMenuHoldStatus(active.reservationHoldId()))
                .isEqualTo("RELEASED");
        assertThat(onlineRemaining(menu.bucketId())).isOne();
        assertThat(restoreLedgerCount(active.reservationHoldId())).isOne();

        ReservationHoldContracts.Result confirmationReplay =
                holdFacade.transition(confirmationCommand);

        assertThat(confirmationReplay).isEqualTo(confirmed);
        assertThat(onlineRemaining(menu.bucketId())).isOne();
        assertThat(restoreLedgerCount(active.reservationHoldId())).isOne();

        ReservationHoldContracts.Result creationReplay = holdFacade.create(creationCommand);

        assertThat(creationReplay).isEqualTo(confirmed);
        assertThat(onlineRemaining(menu.bucketId())).isOne();
        assertThat(restoreLedgerCount(active.reservationHoldId())).isOne();

        ReservationCancellationCommandResult replay = cancellationFacade.cancelByConsumer(
                active.consumerAccountId(),
                finalReservationId,
                cancellationKey,
                request);

        assertThat(replay).isEqualTo(cancelled);
        assertThat(onlineRemaining(menu.bucketId())).isOne();
        assertThat(restoreLedgerCount(active.reservationHoldId())).isOne();
    }

    @Test
    @DisplayName("확정된 임시 MenuHold는 최종 예약 방문 완료에서 수량을 복구하지 않는다")
    void confirmedTemporaryMenuHoldFulfillmentDoesNotRestoreInventory() {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        MenuFixture menu = createMenuFixture(scenario, "Confirmed temporary fulfillment", 1);
        ReservationHoldContracts.CreateCommand creationCommand = createCommand(
                scenario,
                createConsumer(),
                SERVICE_DATE,
                "confirmed-temporary-fulfillment-create",
                List.of(new ReservationTemporaryMenuHoldSelection(menu.menuId(), 1)));
        ReservationHoldContracts.Result active = holdFacade.create(creationCommand);
        long finalReservationId = seedFinalReservation(
                scenario, active.consumerAccountId());
        ReservationHoldContracts.TransitionCommand confirmationCommand = transitionCommand(
                active,
                ReservationHoldStatus.CONFIRMED,
                "confirmed-temporary-fulfillment-confirm",
                finalReservationId);
        ReservationHoldContracts.Result confirmed = holdFacade.transition(confirmationCommand);
        transferCapacityToFinalReservation(active, scenario, finalReservationId);

        ReservationFulfillmentCommandResult fulfilled = fulfillmentFacade.fulfill(
                scenario.operatorId(),
                scenario.storeId(),
                finalReservationId,
                key(202),
                new ReservationFulfillmentRequest());

        assertThat(fulfilled.httpStatus()).isEqualTo(200);
        assertThat(fulfilled.data().status()).isEqualTo("FULFILLED");
        assertThat(temporaryMenuHoldStatus(active.reservationHoldId()))
                .isEqualTo("FULFILLED");
        assertThat(onlineRemaining(menu.bucketId())).isZero();
        assertThat(restoreLedgerCount(active.reservationHoldId())).isZero();

        ReservationHoldContracts.Result confirmationReplay =
                holdFacade.transition(confirmationCommand);

        assertThat(confirmationReplay).isEqualTo(confirmed);
        assertThat(onlineRemaining(menu.bucketId())).isZero();
        assertThat(restoreLedgerCount(active.reservationHoldId())).isZero();

        ReservationHoldContracts.Result creationReplay = holdFacade.create(creationCommand);

        assertThat(creationReplay).isEqualTo(confirmed);
        assertThat(onlineRemaining(menu.bucketId())).isZero();
        assertThat(restoreLedgerCount(active.reservationHoldId())).isZero();
    }

    @Test
    @DisplayName("reconciliation이 release·confirm보다 먼저 잠그면 자원을 유지한 뒤 그룹으로 수렴한다")
    void reconciliationBeforeReleaseOrConfirmRetainsThenConvergesAsOneGroup()
            throws Exception {
        Scenario releaseScenario = createScenario(10, 5, twoBuckets());
        MenuFixture releaseMenu = createMenuFixture(
                releaseScenario, "Task 5 reconcile release", 1);
        ReservationHoldContracts.Result releaseActive = createMenuHoldGroup(
                releaseScenario, releaseMenu, "task5-reconcile-release-create");
        runReconciliationBeforeTerminal(
                releaseScenario,
                releaseMenu,
                releaseActive,
                transitionCommand(
                        releaseActive,
                        ReservationHoldStatus.RELEASED,
                        "task5-after-reconcile-release"));
        assertThat(currentStatus(releaseActive.reservationHoldId())).isEqualTo("RELEASED");
        assertThat(temporaryMenuHoldStatus(releaseActive.reservationHoldId()))
                .isEqualTo("RELEASED");
        assertAllBucketOccupancy(releaseScenario.originalBucketIds(), 0, 0);
        assertThat(onlineRemaining(releaseMenu.bucketId())).isOne();
        assertThat(restoreLedgerCount(releaseActive.reservationHoldId())).isOne();
        assertThat(countTransitionAudits(releaseActive.reservationHoldId())).isEqualTo(3);

        Scenario confirmScenario = createScenario(10, 5, twoBuckets());
        MenuFixture confirmMenu = createMenuFixture(
                confirmScenario, "Task 5 reconcile confirm", 1);
        ReservationHoldContracts.Result confirmActive = createMenuHoldGroup(
                confirmScenario, confirmMenu, "task5-reconcile-confirm-create");
        long finalReservationId = seedFinalReservation(confirmScenario);
        runReconciliationBeforeTerminal(
                confirmScenario,
                confirmMenu,
                confirmActive,
                transitionCommand(
                        confirmActive,
                        ReservationHoldStatus.CONFIRMED,
                        "task5-after-reconcile-confirm",
                        finalReservationId));
        assertThat(currentStatus(confirmActive.reservationHoldId())).isEqualTo("CONFIRMED");
        assertThat(temporaryMenuHoldStatus(confirmActive.reservationHoldId()))
                .isEqualTo("CONFIRMED");
        assertThat(temporaryMenuHoldReservationId(confirmActive.reservationHoldId()))
                .isEqualTo(finalReservationId);
        assertAllBucketOccupancy(confirmScenario.originalBucketIds(), PARTY_SIZE, 1);
        assertThat(onlineRemaining(confirmMenu.bucketId())).isZero();
        assertThat(restoreLedgerCount(confirmActive.reservationHoldId())).isZero();
        assertThat(countTransitionAudits(confirmActive.reservationHoldId())).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 release operation 병렬 제출은 감사 한 건과 수용량 복구 한 번으로 수렴한다")
    void concurrentSameReleaseOperationRestoresCapacityExactlyOnce() throws Exception {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        ReservationHoldContracts.Result active = createHold(
                scenario, createConsumer(), "release-race-create");
        ReservationHoldContracts.TransitionCommand command = transitionCommand(
                active, ReservationHoldStatus.RELEASED, "release-race-operation");

        List<HoldAttempt> attempts = invokeTwoWhileRowLocked(
                "reservation_holds",
                "reservation_hold_id",
                active.reservationHoldId(),
                () -> invokeTransition(command),
                () -> invokeTransition(command));

        assertThat(attempts).allSatisfy(attempt -> {
            assertThat(attempt.errorCode()).isNull();
            assertThat(attempt.result().status()).isEqualTo(ReservationHoldStatus.RELEASED);
            assertThat(attempt.result().statusVersion()).isEqualTo(1L);
        });
        assertThat(attempts).extracting(HoldAttempt::result)
                .containsOnly(attempts.getFirst().result());
        assertThat(countTransitionAudits(active.reservationHoldId())).isEqualTo(2);
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
    }

    @Test
    @DisplayName("서로 다른 Hold의 같은 operation 경합은 qualified 1062 뒤 COMMON_007로 수렴한다")
    void crossHoldTransitionOperationRaceReplaysQualifiedUniqueConflict() throws Exception {
        Scenario firstScenario = createScenario(10, 5, twoBuckets());
        Scenario secondScenario = createScenario(10, 5, twoBuckets());
        ReservationHoldContracts.Result firstHold = createHold(
                firstScenario, createConsumer(), "cross-hold-first-create");
        ReservationHoldContracts.Result secondHold = createHold(
                secondScenario, createConsumer(), "cross-hold-second-create");
        String operationId = "cross-hold-operation-race";
        ReservationHoldContracts.TransitionCommand first = transitionCommand(
                firstHold, ReservationHoldStatus.RELEASED, operationId);
        ReservationHoldContracts.TransitionCommand second = transitionCommand(
                secondHold, ReservationHoldStatus.RELEASED, operationId);

        List<HoldAttempt> attempts = invokeTwoWhileRowsLocked(
                "reservation_capacity_buckets",
                "reservation_capacity_bucket_id",
                Stream.concat(
                                firstScenario.originalBucketIds().stream(),
                                secondScenario.originalBucketIds().stream())
                        .toList(),
                () -> invokeTransition(first),
                () -> invokeTransition(second));

        HoldAttempt success = attempts.stream()
                .filter(attempt -> attempt.result() != null)
                .findFirst()
                .orElseThrow();
        assertThat(attempts).filteredOn(attempt -> attempt.result() != null).hasSize(1);
        assertThat(attempts)
                .filteredOn(attempt -> attempt.errorCode() != null)
                .singleElement()
                .extracting(HoldAttempt::errorCode)
                .isEqualTo(com.miriyum.global.exception.CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(count("reservation_hold_transition_audits")).isEqualTo(3);
        if (success.result().reservationHoldId() == firstHold.reservationHoldId()) {
            assertThat(currentStatus(firstHold.reservationHoldId())).isEqualTo("RELEASED");
            assertThat(currentStatus(secondHold.reservationHoldId())).isEqualTo("ACTIVE");
            assertAllBucketOccupancy(firstScenario.originalBucketIds(), 0, 0);
            assertAllBucketOccupancy(secondScenario.originalBucketIds(), 2, 1);
        } else {
            assertThat(success.result().reservationHoldId())
                    .isEqualTo(secondHold.reservationHoldId());
            assertThat(currentStatus(firstHold.reservationHoldId())).isEqualTo("ACTIVE");
            assertThat(currentStatus(secondHold.reservationHoldId())).isEqualTo("RELEASED");
            assertAllBucketOccupancy(firstScenario.originalBucketIds(), 2, 1);
            assertAllBucketOccupancy(secondScenario.originalBucketIds(), 0, 0);
        }
    }

    @Test
    @DisplayName("split·merge 재게시 뒤 release는 원본과 최신 합집합만 복구하고 중간 버전을 보존한다")
    void releaseAfterSplitAndMergeRestoresOriginalAndLatestButNotIntermediate() {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        ReservationHoldContracts.Result active = createHold(
                scenario, createConsumer(), "publication-history-create");

        ReservationCapacityCommandResult split = capacityFacade.replace(
                scenario.operatorId(),
                scenario.storeId(),
                SERVICE_DATE,
                key(1),
                capacities(List.of(
                        bucket(LocalTime.NOON, LocalTime.of(12, 15), 10, 5),
                        bucket(LocalTime.of(12, 15), LocalTime.of(12, 30), 10, 5),
                        bucket(LocalTime.of(12, 30), LocalTime.of(12, 45), 10, 5),
                        bucket(LocalTime.of(12, 45), LocalTime.of(13, 0), 10, 5))));
        ReservationCapacityCommandResult merge = capacityFacade.replace(
                scenario.operatorId(),
                scenario.storeId(),
                SERVICE_DATE,
                key(2),
                capacities(List.of(bucket(LocalTime.NOON, LocalTime.of(13, 0), 10, 5))));
        List<Long> intermediateIds = bucketIdsForVersion(scenario.storeId(), 2L);
        List<Long> latestIds = bucketIdsForVersion(scenario.storeId(), 3L);

        assertThat(split.data().policyVersion()).isEqualTo(2L);
        assertThat(merge.data().policyVersion()).isEqualTo(3L);
        assertAllBucketOccupancy(scenario.originalBucketIds(), 2, 1);
        assertAllBucketOccupancy(intermediateIds, 2, 1);
        assertAllBucketOccupancy(latestIds, 2, 1);

        ReservationHoldContracts.Result released = transition(
                active, ReservationHoldStatus.RELEASED, "publication-history-release");

        assertThat(released.status()).isEqualTo(ReservationHoldStatus.RELEASED);
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
        assertAllBucketOccupancy(latestIds, 0, 0);
        assertAllBucketOccupancy(intermediateIds, 2, 1);
        assertThat(count("reservation_hold_capacity_allocations")).isEqualTo(2);
    }

    @Test
    @DisplayName("재게시 최신 정책이 Hold와 겹치지 않아도 RELEASED는 original 점유를 복구한다")
    void releaseAfterNonOverlappingPublicationRestoresOriginalBuckets() {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        ReservationHoldContracts.Result active = createHold(
                scenario, createConsumer(), "non-overlap-release-create");

        ReservationCapacityCommandResult publication = capacityFacade.replace(
                scenario.operatorId(),
                scenario.storeId(),
                SERVICE_DATE,
                key(201),
                capacities(List.of(bucket(
                        LocalTime.of(13, 0), LocalTime.of(14, 0), 10, 5))));
        List<Long> latestIds = bucketIdsForVersion(scenario.storeId(), 2L);
        ReservationHoldContracts.Result released = transition(
                active,
                ReservationHoldStatus.RELEASED,
                "non-overlap-release-operation");

        assertThat(publication.data().policyVersion()).isEqualTo(2L);
        assertThat(released.status()).isEqualTo(ReservationHoldStatus.RELEASED);
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
        assertAllBucketOccupancy(latestIds, 0, 0);
        assertThat(countTransitionAudits(active.reservationHoldId())).isEqualTo(2);
    }

    @Test
    @DisplayName("재게시 최신 정책이 Hold 일부만 겹쳐도 EXPIRED는 original과 실제 겹침을 복구한다")
    void expiryAfterPartiallyOverlappingPublicationRestoresActualUnion() {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        ReservationHoldContracts.Result active = createHold(
                scenario, createConsumer(), "partial-expiry-create");

        ReservationCapacityCommandResult publication = capacityFacade.replace(
                scenario.operatorId(),
                scenario.storeId(),
                SERVICE_DATE,
                key(202),
                capacities(List.of(bucket(
                        LocalTime.of(12, 30), LocalTime.of(13, 30), 10, 5))));
        List<Long> latestIds = bucketIdsForVersion(scenario.storeId(), 2L);
        clock.set(active.expiresAt());
        ReservationHoldContracts.Result expired = transition(
                active,
                ReservationHoldStatus.EXPIRED,
                "partial-expiry-operation");

        assertThat(publication.data().policyVersion()).isEqualTo(2L);
        assertThat(expired.status()).isEqualTo(ReservationHoldStatus.EXPIRED);
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
        assertAllBucketOccupancy(latestIds, 0, 0);
        assertThat(countTransitionAudits(active.reservationHoldId())).isEqualTo(2);
    }

    @Test
    @DisplayName("재게시와 fresh Hold 생성 경합은 최신 정책에 점유를 정확히 한 번 보존한다")
    void publicationAndFreshHoldCreationConvergeWithoutLostOrDuplicateOccupancy()
            throws Exception {
        Scenario scenario = createScenario(2, 1, twoBuckets());
        long consumerId = createConsumer();
        ReservationHoldContracts.CreateCommand createCommand = createCommand(
                scenario, consumerId, "publication-create-race");

        RacePair<ReservationCapacityCommandResult, ReservationHoldContracts.Result> workers =
                invokePairWhileRowLocked(
                        "stores",
                        "store_id",
                        scenario.storeId(),
                        () -> capacityFacade.replace(
                                scenario.operatorId(),
                                scenario.storeId(),
                                SERVICE_DATE,
                                key(101),
                                capacities(List.of(bucket(
                                        LocalTime.NOON,
                                        LocalTime.of(13, 0),
                                        2,
                                        1)))),
                        () -> holdFacade.create(createCommand));

        ReservationCapacityCommandResult publication = workers.first();
        ReservationHoldContracts.Result created = workers.second();
        List<Long> latestBucketIds = bucketIdsForVersion(scenario.storeId(), 2L);

        assertThat(publication.httpStatus()).isEqualTo(200);
        assertThat(publication.data().policyVersion()).isEqualTo(2L);
        assertThat(created.status()).isEqualTo(ReservationHoldStatus.ACTIVE);
        assertThat(latestBucketIds).hasSize(1);
        assertAllBucketOccupancy(latestBucketIds, 2, 1);
        assertThat(count("reservation_holds")).isOne();
        assertThat(countTransitionAudits(created.reservationHoldId())).isOne();
        assertThat(count("reservation_hold_warning_tasks")).isOne();
        int expectedAllocationCount;
        if (created.capacityPolicyVersion() == 1L) {
            expectedAllocationCount = 2;
            assertAllBucketOccupancy(scenario.originalBucketIds(), 2, 1);
        } else {
            assertThat(created.capacityPolicyVersion()).isEqualTo(2L);
            expectedAllocationCount = 1;
            assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
        }
        assertThat(count("reservation_hold_capacity_allocations"))
                .isEqualTo(expectedAllocationCount);

        ReservationHoldContracts.Result replay = holdFacade.create(createCommand);

        assertThat(replay).isEqualTo(created);
        assertThat(count("reservation_holds")).isOne();
        assertThat(count("reservation_hold_capacity_allocations"))
                .isEqualTo(expectedAllocationCount);
        assertThat(countTransitionAudits(created.reservationHoldId())).isOne();
        assertThat(count("reservation_hold_warning_tasks")).isOne();
        assertAllBucketOccupancy(latestBucketIds, 2, 1);
        if (created.capacityPolicyVersion() == 1L) {
            assertAllBucketOccupancy(scenario.originalBucketIds(), 2, 1);
        } else {
            assertThat(created.capacityPolicyVersion()).isEqualTo(2L);
            assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
        }
    }

    @Test
    @DisplayName("재게시와 Hold release 경합은 원본과 최신 점유를 한 번만 복구한다")
    void publicationAndHoldReleaseConvergeWithoutStaleOrDoubleRestoration()
            throws Exception {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        ReservationHoldContracts.Result active = createHold(
                scenario, createConsumer(), "publication-release-create");
        ReservationHoldContracts.TransitionCommand releaseCommand = transitionCommand(
                active, ReservationHoldStatus.RELEASED, "publication-release-operation");

        RacePair<ReservationCapacityCommandResult, ReservationHoldContracts.Result> workers =
                invokePairWhileRowLocked(
                        "reservation_holds",
                        "reservation_hold_id",
                        active.reservationHoldId(),
                        () -> capacityFacade.replace(
                                scenario.operatorId(),
                                scenario.storeId(),
                                SERVICE_DATE,
                                key(102),
                                capacities(List.of(bucket(
                                        LocalTime.NOON,
                                        LocalTime.of(13, 0),
                                        10,
                                        5)))),
                        () -> holdFacade.transition(releaseCommand));

        ReservationCapacityCommandResult publication = workers.first();
        ReservationHoldContracts.Result released = workers.second();
        List<Long> latestBucketIds = bucketIdsForVersion(scenario.storeId(), 2L);

        assertThat(publication.httpStatus()).isEqualTo(200);
        assertThat(publication.data().policyVersion()).isEqualTo(2L);
        assertThat(released.status()).isEqualTo(ReservationHoldStatus.RELEASED);
        assertThat(released.statusVersion()).isEqualTo(1L);
        assertThat(currentStatus(active.reservationHoldId())).isEqualTo("RELEASED");
        assertThat(latestBucketIds).hasSize(1);
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
        assertAllBucketOccupancy(latestBucketIds, 0, 0);
        assertThat(count("reservation_holds")).isOne();
        assertThat(count("reservation_hold_capacity_allocations")).isEqualTo(2);
        assertThat(countTransitionAudits(active.reservationHoldId())).isEqualTo(2);
        assertThat(countReleaseTransitionAudits(active.reservationHoldId())).isOne();
        assertThat(count("reservation_hold_warning_tasks")).isOne();

        ReservationHoldContracts.Result replay = holdFacade.transition(releaseCommand);

        assertThat(replay).isEqualTo(released);
        assertThat(count("reservation_hold_capacity_allocations")).isEqualTo(2);
        assertThat(countTransitionAudits(active.reservationHoldId())).isEqualTo(2);
        assertThat(countReleaseTransitionAudits(active.reservationHoldId())).isOne();
        assertThat(count("reservation_hold_warning_tasks")).isOne();
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
        assertAllBucketOccupancy(latestBucketIds, 0, 0);
    }

    @Test
    @DisplayName("재게시와 확정 예약 취소 경합은 보호 Hold 점유만 최신 정책에 남긴다")
    void publicationAndConfirmedReservationCancellationPreserveProtectedHoldOnly()
            throws Exception {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        ConfirmedReservationFixture confirmed = seedConfirmedReservation(scenario);
        ReservationHoldContracts.Result protectedHold = createHold(
                scenario, createConsumer(), "publication-cancellation-hold");
        IdempotencyKey cancellationKey = key(104);
        ConsumerCancellationRequest cancellationRequest =
                new ConsumerCancellationRequest("publication race");

        RacePair<ReservationCapacityCommandResult, ReservationCancellationCommandResult> workers =
                invokePairWhileRowLocked(
                        "reservations",
                        "reservation_id",
                        confirmed.reservationId(),
                        () -> capacityFacade.replace(
                                scenario.operatorId(),
                                scenario.storeId(),
                                SERVICE_DATE,
                                key(103),
                                capacities(List.of(bucket(
                                        LocalTime.NOON,
                                        LocalTime.of(13, 0),
                                        10,
                                        5)))),
                        () -> cancellationFacade.cancelByConsumer(
                                confirmed.consumerId(),
                                confirmed.reservationId(),
                                cancellationKey,
                                cancellationRequest));

        ReservationCapacityCommandResult publication = workers.first();
        ReservationCancellationCommandResult cancelled = workers.second();
        List<Long> latestBucketIds = bucketIdsForVersion(scenario.storeId(), 2L);

        assertThat(publication.httpStatus()).isEqualTo(200);
        assertThat(publication.data().policyVersion()).isEqualTo(2L);
        assertThat(cancelled.httpStatus()).isEqualTo(200);
        assertThat(cancelled.data().status()).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE reservation_id = ?",
                String.class,
                confirmed.reservationId())).isEqualTo("CANCELLED");
        assertThat(currentStatus(protectedHold.reservationHoldId())).isEqualTo("ACTIVE");
        assertThat(latestBucketIds).hasSize(1);
        assertAllBucketOccupancy(scenario.originalBucketIds(), 2, 1);
        assertAllBucketOccupancy(latestBucketIds, 2, 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_capacity_allocations "
                        + "WHERE reservation_id = ?",
                Integer.class,
                confirmed.reservationId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_cancellation_audits "
                        + "WHERE reservation_id = ?",
                Integer.class,
                confirmed.reservationId())).isOne();
        assertThat(count("reservation_hold_capacity_allocations")).isEqualTo(2);
        assertThat(countTransitionAudits(protectedHold.reservationHoldId())).isOne();

        ReservationCancellationCommandResult replay = cancellationFacade.cancelByConsumer(
                confirmed.consumerId(),
                confirmed.reservationId(),
                cancellationKey,
                cancellationRequest);

        assertThat(replay).isEqualTo(cancelled);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_cancellation_audits "
                        + "WHERE reservation_id = ?",
                Integer.class,
                confirmed.reservationId())).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_capacity_allocations "
                        + "WHERE reservation_id = ?",
                Integer.class,
                confirmed.reservationId())).isEqualTo(2);
        assertThat(count("reservation_hold_capacity_allocations")).isEqualTo(2);
        assertThat(countTransitionAudits(protectedHold.reservationHoldId())).isOne();
        assertAllBucketOccupancy(scenario.originalBucketIds(), 2, 1);
        assertAllBucketOccupancy(latestBucketIds, 2, 1);
    }

    @Test
    @DisplayName("만료 직전은 RES005이고 정각부터 단 한 번 만료·복구한다")
    void expiryBoundaryRejectsBeforeAndExpiresExactlyOnceAtBoundary() {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        ReservationHoldContracts.Result active = createHold(
                scenario, createConsumer(), "expiry-boundary-create");
        ReservationHoldContracts.TransitionCommand command = transitionCommand(
                active, ReservationHoldStatus.EXPIRED, "expiry-boundary-operation");

        clock.set(active.expiresAt().minusNanos(1));
        assertThatThrownBy(() -> holdFacade.transition(command))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION));
        assertThat(currentStatus(active.reservationHoldId()))
                .isEqualTo(ReservationHoldStatus.ACTIVE.name());
        assertAllBucketOccupancy(scenario.originalBucketIds(), 2, 1);

        clock.set(active.expiresAt());
        ReservationHoldContracts.Result expired = holdFacade.transition(command);
        ReservationHoldContracts.Result replay = holdFacade.transition(command);

        assertThat(expired.status()).isEqualTo(ReservationHoldStatus.EXPIRED);
        assertThat(replay).isEqualTo(expired);
        assertThat(countTransitionAudits(active.reservationHoldId())).isEqualTo(2);
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);

        clock.set(active.expiresAt().plusSeconds(1));
        ReservationHoldContracts.Result afterBoundary = createHold(
                scenario, createConsumer(), "after-expiry-create");
        clock.set(afterBoundary.expiresAt().plusNanos(1));
        ReservationHoldContracts.TransitionCommand afterBoundaryCommand = transitionCommand(
                afterBoundary,
                ReservationHoldStatus.EXPIRED,
                "after-expiry-fresh-operation");

        ReservationHoldContracts.Result afterBoundaryExpired =
                holdFacade.transition(afterBoundaryCommand);
        ReservationHoldContracts.Result afterBoundaryReplay =
                holdFacade.transition(afterBoundaryCommand);

        assertThat(afterBoundaryExpired.status()).isEqualTo(ReservationHoldStatus.EXPIRED);
        assertThat(afterBoundaryExpired.statusVersion()).isEqualTo(1L);
        assertThat(afterBoundaryReplay).isEqualTo(afterBoundaryExpired);
        assertThat(countTransitionAudits(afterBoundary.reservationHoldId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_hold_transition_audits
                 WHERE reservation_hold_id = ?
                   AND after_status = 'EXPIRED'
                """, Long.class, afterBoundary.reservationHoldId())).isOne();
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
    }

    @Test
    @DisplayName("만료 정각의 확정은 결정적 EXPIRED 감사로 치환되고 원 operation replay도 수렴한다")
    void confirmationAtExpiryBoundaryPersistsDeterministicExpirationAudit() {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        ReservationHoldContracts.Result active = createHold(
                scenario, createConsumer(), "command-time-expiry-create");
        clock.set(active.expiresAt());
        ReservationHoldContracts.TransitionCommand confirmation = transitionCommand(
                active,
                ReservationHoldStatus.CONFIRMED,
                "late-confirm-operation");

        ReservationHoldContracts.Result expired = holdFacade.transition(confirmation);
        ReservationHoldContracts.Result replay = holdFacade.transition(confirmation);

        assertThat(expired.status()).isEqualTo(ReservationHoldStatus.EXPIRED);
        assertThat(replay).isEqualTo(expired);
        assertThat(countTransitionAudits(active.reservationHoldId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT command_id
                  FROM reservation_hold_transition_audits
                 WHERE reservation_hold_id = ?
                   AND after_status = 'EXPIRED'
                """, String.class, active.reservationHoldId()))
                .isEqualTo("reservation-hold-expire:" + active.reservationHoldId());
        assertThat(holdTransitionAuditRepository
                .findAllByReservationHoldIdOrderByIdAsc(active.reservationHoldId()))
                .filteredOn(audit -> audit.getAfterStatus() == ReservationHoldStatus.EXPIRED)
                .extracting(ReservationHoldTransitionAudit::getRequestedAt)
                .containsExactly(active.expiresAt());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_hold_transition_audits
                 WHERE command_id = 'late-confirm-operation'
                """, Long.class)).isZero();
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
    }

    @Test
    @DisplayName("RECONCILIATION_REQUIRED 전이 감사 후 10분 경계부터 현재 장기 체류만 센다")
    void reconciliationLongStayUsesTransitionAuditBoundaryAndCurrentStatus() {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        ReservationHoldContracts.Result active = createHold(
                scenario, createConsumer(), "reconciliation-long-stay-create");
        Instant reconciledAt = active.createdAt().plusSeconds(60);
        clock.set(reconciledAt);
        ReservationHoldContracts.Result reconciliation = transition(
                active,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                "reconciliation-long-stay-enter");

        clock.set(reconciledAt.plusSeconds(599));
        assertThat(holdExpirationService.countLongStayingReconciliations()).isZero();

        clock.set(reconciledAt.plusSeconds(600));
        assertThat(holdExpirationService.countLongStayingReconciliations()).isOne();
        assertThat(holdExpirationService.expireDueHolds(100)).isZero();
        assertThat(currentStatus(active.reservationHoldId()))
                .isEqualTo(ReservationHoldStatus.RECONCILIATION_REQUIRED.name());
        assertAllBucketOccupancy(scenario.originalBucketIds(), 2, 1);

        ReservationHoldContracts.Result released = transition(
                reconciliation,
                ReservationHoldStatus.RELEASED,
                "reconciliation-long-stay-release");

        assertThat(released.status()).isEqualTo(ReservationHoldStatus.RELEASED);
        assertThat(holdExpirationService.countLongStayingReconciliations()).isZero();
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
    }

    @Test
    @DisplayName("장기 체류 집계는 같은 Hold의 중복 대사 감사를 한 건으로 센다")
    void reconciliationLongStayCountsDistinctHoldsWhenAuditsAreDuplicated() {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        ReservationHoldContracts.Result active = createHold(
                scenario, createConsumer(), "reconciliation-distinct-count-create");
        Instant reconciledAt = active.createdAt().plusSeconds(60);
        clock.set(reconciledAt);
        transition(
                active,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                "reconciliation-distinct-count-enter");
        holdTransitionAuditRepository.save(ReservationHoldTransitionAudit.record(
                active.reservationHoldId(),
                "SYSTEM",
                null,
                reconciledAt.plusSeconds(1),
                reconciledAt.plusSeconds(1),
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                1L,
                1L,
                "reconciliation-distinct-count-duplicate"));

        clock.set(reconciledAt.plusSeconds(601));

        assertThat(holdExpirationService.countLongStayingReconciliations()).isOne();
    }

    @Test
    @DisplayName("두 만료 worker가 같은 후보를 조회해도 결정적 operation으로 한 번만 만료·복구한다")
    void concurrentExpirationWorkersConvergeToOneTransition() throws Exception {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        ReservationHoldContracts.Result active = createHold(
                scenario, createConsumer(), "worker-worker-expiry-create");
        clock.set(active.expiresAt());

        RacePair<Integer, Integer> workers = invokePairWhileRowLocked(
                "reservation_holds",
                "reservation_hold_id",
                active.reservationHoldId(),
                () -> holdExpirationService.expireDueHolds(1),
                () -> holdExpirationService.expireDueHolds(1));

        assertThat(workers.first()).isOne();
        assertThat(workers.second()).isOne();
        assertThat(currentStatus(active.reservationHoldId()))
                .isEqualTo(ReservationHoldStatus.EXPIRED.name());
        assertThat(countTransitionAudits(active.reservationHoldId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_hold_transition_audits
                 WHERE reservation_hold_id = ?
                   AND after_status = 'EXPIRED'
                """, Long.class, active.reservationHoldId())).isOne();
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
    }

    @Test
    @DisplayName("만료 service는 실제 keyset page를 끝까지 소진해 모든 due Hold를 한 번씩 만료한다")
    void expirationServiceConsumesAllKeysetPages() {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        List<ReservationHoldContracts.Result> active = List.of(
                createHold(scenario, createConsumer(), "keyset-expiry-create-1"),
                createHold(scenario, createConsumer(), "keyset-expiry-create-2"),
                createHold(scenario, createConsumer(), "keyset-expiry-create-3"));
        clock.set(active.getFirst().expiresAt());

        int completed = holdExpirationService.expireDueHolds(2);

        assertThat(completed).isEqualTo(3);
        assertThat(active)
                .extracting(result -> currentStatus(result.reservationHoldId()))
                .containsOnly(ReservationHoldStatus.EXPIRED.name());
        assertThat(active)
                .allSatisfy(result ->
                        assertThat(countTransitionAudits(result.reservationHoldId()))
                                .isEqualTo(2));
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
    }

    @ParameterizedTest(name = "target={0}")
    @EnumSource(
            value = ReservationHoldStatus.class,
            names = {"CONFIRMED", "RELEASED"}
    )
    @DisplayName("만료 정각의 명령과 worker 경합은 단일 EXPIRED와 한 번의 복구로 수렴한다")
    void commandAndWorkerAtExpiryBoundaryConvergeToSingleExpiration(
            ReservationHoldStatus requestedTarget
    ) throws Exception {
        Scenario scenario = createScenario(10, 5, twoBuckets());
        ReservationHoldContracts.Result active = createHold(
                scenario,
                createConsumer(),
                "command-worker-expiry-" + requestedTarget.name().toLowerCase(Locale.ROOT));
        clock.set(active.expiresAt());
        ReservationHoldContracts.TransitionCommand requested = transitionCommand(
                active,
                requestedTarget,
                "command-worker-operation-"
                        + requestedTarget.name().toLowerCase(Locale.ROOT));

        RacePair<Integer, ReservationHoldContracts.Result> race = invokePairWhileRowLocked(
                "reservation_holds",
                "reservation_hold_id",
                active.reservationHoldId(),
                () -> holdExpirationService.expireDueHolds(100),
                () -> holdFacade.transition(requested));

        assertThat(race.first()).isOne();
        assertThat(race.second().status()).isEqualTo(ReservationHoldStatus.EXPIRED);
        assertThat(currentStatus(active.reservationHoldId()))
                .isEqualTo(ReservationHoldStatus.EXPIRED.name());
        assertThat(countTransitionAudits(active.reservationHoldId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_hold_transition_audits
                 WHERE reservation_hold_id = ?
                   AND after_status = 'EXPIRED'
                """, Long.class, active.reservationHoldId())).isOne();
        assertAllBucketOccupancy(scenario.originalBucketIds(), 0, 0);
    }

    private Scenario createScenario(
            int maxPeople,
            int maxTeams,
            List<CapacityBucketRequest> buckets
    ) {
        return transactions.execute(status -> {
            int sequence = SEQUENCE.incrementAndGet();
            StoreOperatorAccount operator = operatorRepository.saveAndFlush(
                    StoreOperatorAccount.create(
                            "hold-owner-" + sequence + "@example.com",
                            "hashed-password",
                            "owner"));
            Store store = storeRepository.saveAndFlush(Store.create(
                    operator.getId(),
                    Long.toString(9_000_000_000L + sequence),
                    BusinessType.CAFE,
                    "MiriYum Hold Store " + sequence,
                    "",
                    Region.SEOUL,
                    "fixture-address",
                    "CAFE_BAKERY",
                    Set.of(),
                    true,
                    true,
                    false,
                    TIME_ZONE_ID,
                    LocalDateTime.of(2026, 8, 1, 9, 0),
                    "STORE_ONBOARDING_REQUIRED_TERMS_V1"));

            StoreScheduleState scheduleState = StoreScheduleState.initialize(store.getId());
            OperatingScheduleVersion operating = OperatingScheduleVersion.createDraft(
                    store.getId(),
                    scheduleState.allocateOperatingVersion(),
                    TIME_ZONE_ID,
                    List.of(weeklyInterval(
                            LocalTime.of(10, 0),
                            LocalTime.of(18, 0),
                            ScheduleIntervalKind.BUSINESS_HOURS)));
            operating.activate(ACTIVATED_AT, "hold runtime fixture");
            operating = operatingScheduleRepository.saveAndFlush(operating);
            scheduleState.activateOperating(operating.getId());

            ReservationScheduleVersion reservationSchedule =
                    ReservationScheduleVersion.createDraft(
                            store.getId(),
                            scheduleState.allocateReservationVersion(),
                            operating.getId(),
                            TIME_ZONE_ID,
                            List.of(weeklyInterval(
                                    LocalTime.of(11, 0),
                                    LocalTime.of(15, 0),
                                    ScheduleIntervalKind.RESERVATION_SLOT)));
            reservationSchedule.activate(ACTIVATED_AT, "hold runtime fixture");
            reservationSchedule = reservationScheduleRepository.saveAndFlush(
                    reservationSchedule);
            scheduleState.activateReservation(reservationSchedule.getId());

            RegularClosureVersion regularClosure = RegularClosureVersion.createDraft(
                    store.getId(),
                    scheduleState.allocateRegularClosureVersion(),
                    TIME_ZONE_ID,
                    List.of(),
                    List.of());
            regularClosure.activate(ACTIVATED_AT, "hold runtime fixture");
            regularClosure = regularClosureRepository.saveAndFlush(regularClosure);
            scheduleState.activateRegularClosure(regularClosure.getId());
            scheduleStateRepository.saveAndFlush(scheduleState);

            ReservationTimePolicyVersion timePolicy =
                    ReservationTimePolicyVersion.createDraft(
                            store.getId(), 1L, 30, 60, 0);
            timePolicy.activate(ACTIVATED_AT, "hold runtime fixture");
            timePolicyRepository.saveAndFlush(timePolicy);

            List<Long> capacityBucketIds = saveCapacityBuckets(
                    store.getId(), SERVICE_DATE, maxPeople, maxTeams, buckets, 1L);
            return new Scenario(operator.getId(), store.getId(), capacityBucketIds);
        });
    }

    private List<Long> seedCapacityBuckets(
            long storeId,
            LocalDate serviceDate,
            int maxPeople,
            int maxTeams,
            List<CapacityBucketRequest> buckets,
            long policyVersion
    ) {
        return transactions.execute(status -> saveCapacityBuckets(
                storeId, serviceDate, maxPeople, maxTeams, buckets, policyVersion));
    }

    private List<Long> saveCapacityBuckets(
            long storeId,
            LocalDate serviceDate,
            int maxPeople,
            int maxTeams,
            List<CapacityBucketRequest> buckets,
            long policyVersion
    ) {
        return buckets.stream()
                .map(request -> capacityBucketRepository.saveAndFlush(
                        ReservationCapacityBucket.create(
                                storeId,
                                serviceDate,
                                request.startTime(),
                                request.endTime(),
                                maxPeople,
                                maxTeams,
                                0,
                                0,
                                1,
                                maxPeople,
                                true,
                                policyVersion)))
                .map(ReservationCapacityBucket::getId)
                .toList();
    }

    private long createConsumer() {
        return transactions.execute(status -> {
            int sequence = SEQUENCE.incrementAndGet();
            ConsumerAccount consumer = consumerRepository.saveAndFlush(
                    ConsumerAccount.createWithContact(
                            "hold-consumer-" + sequence + "@example.com",
                            "hashed-password",
                            "consumer",
                            String.format(Locale.ROOT, "010%08d", sequence),
                            "opaque-hold-contact-" + sequence));
            return consumer.getId();
        });
    }

    private ConfirmedReservationFixture seedConfirmedReservation(Scenario scenario) {
        long consumerId = createConsumer();
        return transactions.execute(status -> {
            Store store = storeRepository.findById(scenario.storeId()).orElseThrow();
            ReservationTimePolicyVersion policy = timePolicyRepository
                    .findByStoreIdAndVersionNumberForUpdate(scenario.storeId(), 1L)
                    .orElseThrow();
            ReservationTimeSnapshot timeSnapshot = ReservationTimeSnapshot.calculate(
                    policy,
                    LocalDateTime.of(SERVICE_DATE, START_TIME),
                    ZoneId.of(TIME_ZONE_ID),
                    null);
            Reservation reservation = reservationRepository.saveAndFlush(Reservation.confirm(
                    consumerId,
                    scenario.storeId(),
                    store.getName(),
                    timeSnapshot,
                    PartyComposition.of(PARTY_SIZE, 0, 0),
                    ReservationContactSnapshot.contactable(
                            "opaque-confirmed-reservation-" + consumerId),
                    1L,
                    new ReservationCancellationPolicyVersion(1L),
                    BASE_NOW.minusSeconds(3_600)));
            List<ReservationCapacityBucket> buckets = capacityBucketRepository
                    .findAllById(scenario.originalBucketIds());
            assertThat(buckets).hasSize(2);
            buckets.forEach(bucket -> bucket.occupy(PARTY_SIZE));
            capacityBucketRepository.flush();
            capacityAllocationRepository.saveAllAndFlush(buckets.stream()
                    .map(bucket -> ReservationCapacityAllocation.allocate(
                            reservation.getId(), bucket.getId(), PARTY_SIZE, 1L))
                    .toList());
            return new ConfirmedReservationFixture(consumerId, reservation.getId());
        });
    }

    private long seedFinalReservation(Scenario scenario) {
        return seedFinalReservation(scenario, createConsumer());
    }

    private long seedFinalReservation(Scenario scenario, long consumerId) {
        return transactions.execute(status -> {
            Store store = storeRepository.findById(scenario.storeId()).orElseThrow();
            ReservationTimePolicyVersion policy = timePolicyRepository
                    .findByStoreIdAndVersionNumberForUpdate(scenario.storeId(), 1L)
                    .orElseThrow();
            ReservationTimeSnapshot timeSnapshot = ReservationTimeSnapshot.calculate(
                    policy,
                    LocalDateTime.of(SERVICE_DATE, START_TIME),
                    ZoneId.of(TIME_ZONE_ID),
                    null);
            return reservationRepository.saveAndFlush(Reservation.confirm(
                    consumerId,
                    scenario.storeId(),
                    store.getName(),
                    timeSnapshot,
                    PartyComposition.of(PARTY_SIZE, 0, 0),
                    ReservationContactSnapshot.contactable(
                            "opaque-task5-final-reservation-" + consumerId),
                    1L,
                    new ReservationCancellationPolicyVersion(1L),
                    BASE_NOW.minusSeconds(60))).getId();
        });
    }

    private void transferCapacityToFinalReservation(
            ReservationHoldContracts.Result hold,
            Scenario scenario,
            long finalReservationId
    ) {
        transactions.executeWithoutResult(status -> {
            capacityAllocationRepository.saveAllAndFlush(
                    scenario.originalBucketIds().stream()
                            .map(bucketId -> ReservationCapacityAllocation.allocate(
                                    finalReservationId,
                                    bucketId,
                                    PARTY_SIZE,
                                    hold.capacityPolicyVersion()))
                            .toList());
            jdbcTemplate.update(
                    "DELETE FROM reservation_hold_capacity_allocations "
                            + "WHERE reservation_hold_id = ?",
                    hold.reservationHoldId());
        });
    }

    private ReservationHoldContracts.Result createHold(
            Scenario scenario,
            long consumerId,
            String commandId
    ) {
        return holdFacade.create(createCommand(scenario, consumerId, commandId));
    }

    private ReservationHoldContracts.Result createMenuHoldGroup(
            Scenario scenario,
            MenuFixture menu,
            String commandId
    ) {
        return holdFacade.create(createCommand(
                scenario,
                createConsumer(),
                SERVICE_DATE,
                commandId,
                List.of(new ReservationTemporaryMenuHoldSelection(menu.menuId(), 1))));
    }

    private static ReservationHoldContracts.CreateCommand createCommand(
            Scenario scenario,
            long consumerId,
            String commandId
    ) {
        return createCommand(scenario, consumerId, SERVICE_DATE, commandId);
    }

    private static ReservationHoldContracts.CreateCommand createCommand(
            Scenario scenario,
            long consumerId,
            LocalDate serviceDate,
            String commandId
    ) {
        return createCommand(scenario, consumerId, serviceDate, commandId, List.of());
    }

    private static ReservationHoldContracts.CreateCommand createCommand(
            Scenario scenario,
            long consumerId,
            LocalDate serviceDate,
            String commandId,
            List<ReservationTemporaryMenuHoldSelection> menuSelections
    ) {
        return new ReservationHoldContracts.CreateCommand(
                consumerId,
                scenario.storeId(),
                serviceDate,
                START_TIME,
                null,
                PARTY_SIZE,
                0,
                0,
                commandId,
                menuSelections);
    }

    private MenuFixture createMenuFixture(
            Scenario scenario,
            String name,
            int onlineQuantity
    ) {
        return transactions.execute(status -> {
            Menu menu = Menu.create(
                    scenario.storeId(),
                    menuContent(name),
                    scenario.operatorId(),
                    ACTIVATED_AT);
            menu.publish(ACTIVATED_AT);
            menu = menuRepository.saveAndFlush(menu);
            MenuInventoryBucket bucket = menuInventoryBucketRepository.saveAndFlush(
                    MenuInventoryBucket.create(
                            menu.getId(),
                            SERVICE_DATE,
                            START_TIME,
                            SERVICE_DATE,
                            LocalTime.of(13, 0),
                            TIME_ZONE_ID,
                            1L,
                            onlineQuantity,
                            onlineQuantity,
                            0,
                            0,
                            false));
            return new MenuFixture(menu.getId(), bucket.getId());
        });
    }

    private static MenuContent menuContent(String name) {
        return new MenuContent(
                name,
                "",
                5_000,
                false,
                "BEVERAGE",
                List.of(),
                List.of(),
                true,
                false,
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosure(
                        AllergenIngredientCode.MILK,
                        AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.NOT_APPLICABLE,
                List.of(),
                false);
    }

    private int onlineRemaining(long bucketId) {
        return jdbcTemplate.queryForObject(
                "SELECT online_hold_remaining FROM menu_inventory_buckets "
                        + "WHERE menu_inventory_bucket_id = ?",
                Integer.class,
                bucketId);
    }

    private int ledgerCountFor(List<Long> bucketIds) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM menu_inventory_ledger "
                        + "WHERE menu_inventory_bucket_id IN ("
                        + placeholders(bucketIds.size()) + ")",
                Integer.class,
                bucketIds.toArray());
    }

    private int acquireLedgerCount(long reservationHoldId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM menu_inventory_ledger
                 WHERE operation_id = ?
                   AND operation_type = 'ACQUIRE'
                """, Integer.class, acquireOperationId(reservationHoldId));
    }

    private int restoreLedgerCount(long reservationHoldId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM menu_inventory_ledger
                 WHERE source_operation_id = ?
                   AND operation_type = 'RESTORE'
                """, Integer.class, acquireOperationId(reservationHoldId));
    }

    private static String acquireOperationId(long reservationHoldId) {
        return "reservation-temp-menu-acquire:" + reservationHoldId;
    }

    private String temporaryMenuHoldStatus(long reservationHoldId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM menu_holds WHERE reservation_hold_id = ?",
                String.class,
                reservationHoldId);
    }

    private Long temporaryMenuHoldReservationId(long reservationHoldId) {
        return jdbcTemplate.queryForObject(
                "SELECT reservation_id FROM menu_holds WHERE reservation_hold_id = ?",
                Long.class,
                reservationHoldId);
    }

    private int countHoldAllocations(long reservationHoldId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_hold_capacity_allocations "
                        + "WHERE reservation_hold_id = ?",
                Integer.class,
                reservationHoldId);
    }

    private int countWarningTasks(long reservationHoldId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_hold_warning_tasks "
                        + "WHERE reservation_hold_id = ?",
                Integer.class,
                reservationHoldId);
    }

    private GroupArtifactCounts groupArtifactCounts() {
        return new GroupArtifactCounts(
                count("reservation_holds"),
                count("reservation_hold_capacity_allocations"),
                count("menu_holds"),
                count("menu_hold_items"),
                count("menu_inventory_ledger"),
                count("reservation_hold_transition_audits"),
                count("reservation_hold_warning_tasks"));
    }

    private Task3CreationSnapshot task3CreationSnapshot(
            Scenario scenario,
            long reservationHoldId,
            List<Long> inventoryBucketIds
    ) {
        List<Long> orderedCapacityIds = scenario.originalBucketIds().stream().sorted().toList();
        List<Long> orderedInventoryIds = inventoryBucketIds.stream().sorted().toList();
        return new Task3CreationSnapshot(
                jdbcTemplate.queryForList("""
                        SELECT reservation_capacity_bucket_id,
                               occupied_people,
                               occupied_teams
                          FROM reservation_capacity_buckets
                         WHERE reservation_capacity_bucket_id IN (%s)
                         ORDER BY reservation_capacity_bucket_id
                        """.formatted(placeholders(orderedCapacityIds.size())),
                        orderedCapacityIds.toArray()),
                jdbcTemplate.queryForList("""
                        SELECT menu_inventory_bucket_id,
                               online_hold_remaining,
                               shared_remaining,
                               availability_status,
                               lock_version
                          FROM menu_inventory_buckets
                         WHERE menu_inventory_bucket_id IN (%s)
                         ORDER BY menu_inventory_bucket_id
                        """.formatted(placeholders(orderedInventoryIds.size())),
                        orderedInventoryIds.toArray()),
                jdbcTemplate.queryForList("""
                        SELECT operation_id,
                               menu_inventory_bucket_id,
                               operation_type,
                               pool_type,
                               quantity_delta,
                               quantity_before,
                               quantity_after
                          FROM menu_inventory_ledger
                         WHERE menu_inventory_bucket_id IN (%s)
                         ORDER BY menu_inventory_ledger_id
                        """.formatted(placeholders(orderedInventoryIds.size())),
                        orderedInventoryIds.toArray()),
                jdbcTemplate.queryForList("""
                        SELECT reservation_hold_id,
                               status,
                               status_version,
                               creation_command_id,
                               capacity_policy_version,
                               created_at,
                               expires_at
                          FROM reservation_holds
                         WHERE reservation_hold_id = ?
                        """, reservationHoldId),
                jdbcTemplate.queryForList("""
                        SELECT reservation_hold_id,
                               reservation_capacity_bucket_id,
                               occupied_people,
                               occupied_teams,
                               capacity_policy_version
                          FROM reservation_hold_capacity_allocations
                         WHERE reservation_hold_id = ?
                         ORDER BY reservation_capacity_bucket_id
                        """, reservationHoldId),
                jdbcTemplate.queryForList("""
                        SELECT menu_hold_id,
                               reservation_hold_id,
                               expires_at,
                               acquire_operation_id,
                               status,
                               created_at,
                               updated_at
                          FROM menu_holds
                         WHERE reservation_hold_id = ?
                        """, reservationHoldId),
                jdbcTemplate.queryForList("""
                        SELECT item.menu_id,
                               item.menu_inventory_bucket_id,
                               item.menu_policy_version,
                               item.inventory_policy_version,
                               item.quantity
                          FROM menu_hold_items item
                          JOIN menu_holds hold ON hold.menu_hold_id = item.menu_hold_id
                         WHERE hold.reservation_hold_id = ?
                         ORDER BY item.menu_id, item.menu_inventory_bucket_id
                        """, reservationHoldId),
                jdbcTemplate.queryForList("""
                        SELECT before_status,
                               after_status,
                               command_id,
                               requested_at,
                               occurred_at
                          FROM reservation_hold_transition_audits
                         WHERE reservation_hold_id = ?
                         ORDER BY reservation_hold_transition_audit_id
                        """, reservationHoldId),
                jdbcTemplate.queryForList("""
                        SELECT created_at,
                               warning_due_at
                          FROM reservation_hold_warning_tasks
                         WHERE reservation_hold_id = ?
                        """, reservationHoldId));
    }

    private int countWhere(String tableName, String idColumn, long id) {
        if (!tableName.equals("menu_holds") || !idColumn.equals("reservation_hold_id")) {
            throw new IllegalArgumentException("unsupported count target");
        }
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE " + idColumn + " = ?",
                Integer.class,
                id);
    }

    private ReservationHoldContracts.Result transition(
            ReservationHoldContracts.Result hold,
            ReservationHoldStatus targetStatus,
            String operationId
    ) {
        return holdFacade.transition(transitionCommand(hold, targetStatus, operationId));
    }

    private ReservationHoldContracts.TransitionCommand transitionCommand(
            ReservationHoldContracts.Result hold,
            ReservationHoldStatus targetStatus,
            String operationId
    ) {
        return new ReservationHoldContracts.TransitionCommand(
                hold.reservationHoldId(),
                targetStatus,
                operationId,
                "SYSTEM",
                null,
                clock.instant());
    }

    private ReservationHoldContracts.TransitionCommand transitionCommand(
            ReservationHoldContracts.Result hold,
            ReservationHoldStatus targetStatus,
            String operationId,
            long finalReservationId
    ) {
        return new ReservationHoldContracts.TransitionCommand(
                hold.reservationHoldId(),
                targetStatus,
                operationId,
                "SYSTEM",
                null,
                clock.instant(),
                finalReservationId);
    }

    private void runReconciliationBeforeTerminal(
            Scenario scenario,
            MenuFixture menu,
            ReservationHoldContracts.Result active,
            ReservationHoldContracts.TransitionCommand terminalCommand
    ) throws Exception {
        ReservationHoldContracts.TransitionCommand reconciliationCommand = transitionCommand(
                active,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                terminalCommand.operationId() + "-precondition");
        CountDownLatch reconciliationApplied = new CountDownLatch(1);
        CountDownLatch allowReconciliationCommit = new CountDownLatch(1);
        AtomicLong reconciliationConnectionId = new AtomicLong();
        AtomicReference<Map<String, Object>> terminalObservation = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2, workerFactory());
        Future<ReservationHoldContracts.Result> reconciliation = null;
        Future<HoldAttempt> terminal = null;
        try {
            reconciliation = executor.submit(() -> transactions.execute(status -> {
                ReservationHoldContracts.Result result =
                        holdService.transition(reconciliationCommand);
                reconciliationConnectionId.set(jdbcTemplate.queryForObject(
                        "SELECT CONNECTION_ID()", Long.class));
                assertThat(result.status())
                        .isEqualTo(ReservationHoldStatus.RECONCILIATION_REQUIRED);
                assertThat(currentStatus(active.reservationHoldId()))
                        .isEqualTo("RECONCILIATION_REQUIRED");
                assertThat(temporaryMenuHoldStatus(active.reservationHoldId()))
                        .isEqualTo("RECONCILIATION_REQUIRED");
                assertAllBucketOccupancy(scenario.originalBucketIds(), PARTY_SIZE, 1);
                assertThat(onlineRemaining(menu.bucketId())).isZero();
                assertThat(restoreLedgerCount(active.reservationHoldId())).isZero();
                assertThat(countTransitionAudits(active.reservationHoldId())).isEqualTo(2);
                reconciliationApplied.countDown();
                awaitLatch(allowReconciliationCommit, "reconciliation commit release");
                return result;
            }));
            if (!reconciliationApplied.await(10, TimeUnit.SECONDS)) {
                reconciliation.get(1, TimeUnit.SECONDS);
                throw new AssertionError("reconciliation worker did not reach commit gate");
            }
            terminal = executor.submit(() -> {
                HoldAttempt attempt = invokeTransition(terminalCommand);
                terminalObservation.set(jdbcTemplate.queryForMap("""
                        SELECT hold.status AS hold_status,
                               hold.status_version,
                               menu.status AS menu_status,
                               (SELECT COUNT(*)
                                  FROM reservation_hold_transition_audits audit
                                 WHERE audit.reservation_hold_id = hold.reservation_hold_id)
                                   AS audit_count
                          FROM reservation_holds hold
                          LEFT JOIN menu_holds menu
                            ON menu.reservation_hold_id = hold.reservation_hold_id
                         WHERE hold.reservation_hold_id = ?
                        """, active.reservationHoldId()));
                return attempt;
            });
            assertFutureBlocked(terminal);
            awaitBlockingWaits(
                    reconciliationConnectionId.get(), "reservation_holds", "PRIMARY", 1);
            allowReconciliationCommit.countDown();
            assertThat(reconciliation.get(10, TimeUnit.SECONDS).status())
                    .isEqualTo(ReservationHoldStatus.RECONCILIATION_REQUIRED);
            HoldAttempt terminalAttempt = terminal.get(30, TimeUnit.SECONDS);
            assertThat(terminalAttempt.errorCode()).isNull();
            assertThat(terminalAttempt.result().status())
                    .isEqualTo(terminalCommand.targetStatus());
            assertThat(terminalObservation.get())
                    .as("the committed terminal transaction must be externally visible")
                    .containsEntry("hold_status", terminalCommand.targetStatus().name())
                    .containsEntry("menu_status", terminalCommand.targetStatus().name())
                    .containsEntry("status_version", 2L)
                    .containsEntry("audit_count", 3L);
            assertThat(terminalAttempt.result().statusVersion())
                    .isEqualTo(((Number) terminalObservation.get()
                            .get("status_version")).longValue());
        } finally {
            reconciliationApplied.countDown();
            allowReconciliationCommit.countDown();
            cancelIfRunning(reconciliation);
            cancelIfRunning(terminal);
            shutdownAndAwait(executor);
        }
    }

    private HoldAttempt invokeCreate(ReservationHoldContracts.CreateCommand command) {
        try {
            return HoldAttempt.succeeded(holdFacade.create(command));
        } catch (ServiceException exception) {
            return HoldAttempt.failed(exception.getErrorCode());
        }
    }

    private HoldAttempt invokeTransition(ReservationHoldContracts.TransitionCommand command) {
        try {
            return HoldAttempt.succeeded(holdFacade.transition(command));
        } catch (ServiceException exception) {
            return HoldAttempt.failed(exception.getErrorCode());
        }
    }

    private List<HoldAttempt> invokeTwoWhileRowLocked(
            String tableName,
            String idColumn,
            long rowId,
            java.util.concurrent.Callable<HoldAttempt> firstInvocation,
            java.util.concurrent.Callable<HoldAttempt> secondInvocation
    ) throws Exception {
        RacePair<HoldAttempt, HoldAttempt> pair = invokePairWhileRowLocked(
                tableName,
                idColumn,
                rowId,
                firstInvocation,
                secondInvocation);
        return List.of(pair.first(), pair.second());
    }

    private List<HoldAttempt> invokeTwoWhileRowsLocked(
            String tableName,
            String idColumn,
            List<Long> rowIds,
            java.util.concurrent.Callable<HoldAttempt> firstInvocation,
            java.util.concurrent.Callable<HoldAttempt> secondInvocation
    ) throws Exception {
        RacePair<HoldAttempt, HoldAttempt> pair = invokePairWhileRowsLocked(
                tableName,
                idColumn,
                rowIds,
                firstInvocation,
                secondInvocation);
        return List.of(pair.first(), pair.second());
    }

    private <F, S> RacePair<F, S> invokePairWhileRowLocked(
            String tableName,
            String idColumn,
            long rowId,
            java.util.concurrent.Callable<F> firstInvocation,
            java.util.concurrent.Callable<S> secondInvocation
    ) throws Exception {
        return invokePairWhileRowsLocked(
                tableName,
                idColumn,
                List.of(rowId),
                firstInvocation,
                secondInvocation);
    }

    private <F, S> RacePair<F, S> invokePairWhileRowsLocked(
            String tableName,
            String idColumn,
            List<Long> rowIds,
            java.util.concurrent.Callable<F> firstInvocation,
            java.util.concurrent.Callable<S> secondInvocation
    ) throws Exception {
        Set<String> allowedTables = Set.of(
                "stores",
                "reservation_holds",
                "reservations",
                "reservation_capacity_buckets");
        Set<String> allowedColumns = Set.of(
                "store_id",
                "reservation_hold_id",
                "reservation_id",
                "reservation_capacity_bucket_id");
        if (!allowedTables.contains(tableName)
                || !allowedColumns.contains(idColumn)
                || rowIds == null
                || rowIds.isEmpty()
                || rowIds.stream().anyMatch(id -> id == null || id <= 0)
                || rowIds.stream().distinct().count() != rowIds.size()) {
            throw new IllegalArgumentException("unsupported lock target");
        }
        List<Long> orderedRowIds = rowIds.stream().sorted().toList();
        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch startWorkers = new CountDownLatch(1);
        AtomicLong holderConnectionId = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(3, workerFactory());
        Future<Long> holder = null;
        Future<F> first = null;
        Future<S> second = null;
        try {
            holder = executor.submit(() -> transactions.execute(status -> {
                List<Long> lockedIds = jdbcTemplate.queryForList(
                        "SELECT " + idColumn + " FROM " + tableName
                                + " WHERE " + idColumn + " IN ("
                                + placeholders(orderedRowIds.size()) + ")"
                                + " ORDER BY " + idColumn + " FOR UPDATE",
                        Long.class,
                        orderedRowIds.toArray());
                assertThat(lockedIds).containsExactlyElementsOf(orderedRowIds);
                long connectionId = jdbcTemplate.queryForObject(
                        "SELECT CONNECTION_ID()", Long.class);
                holderConnectionId.set(connectionId);
                holderReady.countDown();
                awaitLatch(releaseHolder, "row lock holder release");
                return connectionId;
            }));
            assertThat(holderReady.await(5, TimeUnit.SECONDS)).isTrue();
            first = executor.submit(() -> invokeAfterStart(
                    firstInvocation, workersReady, startWorkers));
            second = executor.submit(() -> invokeAfterStart(
                    secondInvocation, workersReady, startWorkers));
            assertThat(workersReady.await(5, TimeUnit.SECONDS)).isTrue();
            startWorkers.countDown();
            assertFutureBlocked(first);
            assertFutureBlocked(second);
            awaitBlockingWaits(holderConnectionId.get(), tableName, "PRIMARY", 2);
            releaseHolder.countDown();
            holder.get(10, TimeUnit.SECONDS);
            return new RacePair<>(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS));
        } finally {
            holderReady.countDown();
            releaseHolder.countDown();
            workersReady.countDown();
            workersReady.countDown();
            startWorkers.countDown();
            cancelIfRunning(holder);
            cancelIfRunning(first);
            cancelIfRunning(second);
            shutdownAndAwait(executor);
        }
    }

    private static <T> T invokeAfterStart(
            java.util.concurrent.Callable<T> invocation,
            CountDownLatch workersReady,
            CountDownLatch startWorkers
    ) throws Exception {
        workersReady.countDown();
        awaitLatch(startWorkers, "hold worker start");
        return invocation.call();
    }

    private void awaitBlockingWaits(
            long holderConnectionId,
            String tableName,
            String indexName,
            int expectedWaits
    ) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int lastObservedCount = 0;
        try (Connection monitoringConnection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            monitoringConnection.setReadOnly(true);
            try (PreparedStatement statement = monitoringConnection.prepareStatement("""
                    SELECT COUNT(*)
                    FROM performance_schema.data_lock_waits AS wait_edge
                    JOIN performance_schema.data_locks AS blocking_lock
                      ON blocking_lock.ENGINE = wait_edge.ENGINE
                     AND blocking_lock.ENGINE_LOCK_ID = wait_edge.BLOCKING_ENGINE_LOCK_ID
                    JOIN information_schema.INNODB_TRX AS blocking_transaction
                      ON blocking_transaction.TRX_ID = blocking_lock.ENGINE_TRANSACTION_ID
                    WHERE blocking_transaction.TRX_MYSQL_THREAD_ID = ?
                      AND blocking_lock.OBJECT_SCHEMA = DATABASE()
                      AND blocking_lock.OBJECT_NAME = ?
                      AND blocking_lock.INDEX_NAME = ?
                    """)) {
                statement.setLong(1, holderConnectionId);
                statement.setString(2, tableName);
                statement.setString(3, indexName);
                while (System.nanoTime() < deadlineNanos) {
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new AssertionError("lock wait count query returned no row");
                        }
                        lastObservedCount = resultSet.getInt(1);
                    }
                    if (lastObservedCount >= expectedWaits) {
                        return;
                    }
                    Thread.onSpinWait();
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("unable to observe MySQL lock waits", exception);
        }
        throw new AssertionError(
                "expected " + expectedWaits + " waits on " + tableName + "." + indexName
                        + " but observed " + lastObservedCount);
    }

    private void assertAllBucketOccupancy(List<Long> bucketIds, int people, int teams) {
        assertThat(bucketIds).isNotEmpty();
        assertThat(jdbcTemplate.queryForList("""
                SELECT occupied_people, occupied_teams
                  FROM reservation_capacity_buckets
                 WHERE reservation_capacity_bucket_id IN (%s)
                 ORDER BY reservation_capacity_bucket_id
                """.formatted(placeholders(bucketIds.size())), bucketIds.toArray()))
                .hasSize(bucketIds.size())
                .allSatisfy(row -> assertThat(row)
                        .containsEntry("occupied_people", people)
                        .containsEntry("occupied_teams", teams));
    }

    private List<Long> bucketIdsForVersion(long storeId, long version) {
        return jdbcTemplate.queryForList("""
                SELECT reservation_capacity_bucket_id
                  FROM reservation_capacity_buckets
                 WHERE store_id = ? AND service_date = ? AND policy_version = ?
                 ORDER BY reservation_capacity_bucket_id
                """, Long.class, storeId, SERVICE_DATE, version);
    }

    private String currentStatus(long holdId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM reservation_holds WHERE reservation_hold_id = ?",
                String.class,
                holdId);
    }

    private int countTransitionAudits(long holdId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_hold_transition_audits "
                        + "WHERE reservation_hold_id = ?",
                Integer.class,
                holdId);
    }

    private int countReleaseTransitionAudits(long holdId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_hold_transition_audits
                 WHERE reservation_hold_id = ?
                   AND before_status = 'ACTIVE'
                   AND after_status = 'RELEASED'
                """, Integer.class, holdId);
    }

    private int terminalAuditCount(long holdId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_hold_transition_audits
                 WHERE reservation_hold_id = ?
                   AND after_status IN ('CONFIRMED', 'RELEASED', 'EXPIRED')
                """, Integer.class, holdId);
    }

    private int count(String tableName) {
        Set<String> allowed = Set.of(
                "reservation_holds",
                "reservation_hold_capacity_allocations",
                "reservation_hold_transition_audits",
                "reservation_hold_warning_tasks",
                "menu_holds",
                "menu_hold_items",
                "menu_inventory_ledger");
        if (!allowed.contains(tableName)) {
            throw new IllegalArgumentException("unsupported count table");
        }
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName,
                Integer.class);
    }

    private void createFailureTrigger(CreationFailurePoint point) {
        jdbcTemplate.execute(switch (point) {
            case ALLOCATION_INSERT -> """
                    CREATE TRIGGER trg_hold_allocation_failure
                    BEFORE INSERT ON reservation_hold_capacity_allocations
                    FOR EACH ROW SIGNAL SQLSTATE '45000'
                    SET MESSAGE_TEXT = 'hold allocation insert failure'
                    """;
            case WARNING_INSERT -> """
                    CREATE TRIGGER trg_hold_warning_failure
                    BEFORE INSERT ON reservation_hold_warning_tasks
                    FOR EACH ROW SIGNAL SQLSTATE '45000'
                    SET MESSAGE_TEXT = 'hold warning insert failure'
                    """;
            case MENU_HOLD_INSERT -> """
                    CREATE TRIGGER trg_temporary_menu_hold_failure
                    BEFORE INSERT ON menu_holds
                    FOR EACH ROW SIGNAL SQLSTATE '45000'
                    SET MESSAGE_TEXT = 'temporary menu hold insert failure'
                    """;
        });
    }

    private void createCreationAuditFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_hold_creation_audit_failure
                BEFORE INSERT ON reservation_hold_transition_audits
                FOR EACH ROW SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'hold creation audit insert failure'
                """);
    }

    private void dropCreationAuditFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_hold_creation_audit_failure");
    }

    private void dropFailureTrigger(CreationFailurePoint point) {
        String name = switch (point) {
            case ALLOCATION_INSERT -> "trg_hold_allocation_failure";
            case WARNING_INSERT -> "trg_hold_warning_failure";
            case MENU_HOLD_INSERT -> "trg_temporary_menu_hold_failure";
        };
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + name);
    }

    private static <T extends Throwable> T requireCause(
            Throwable failure,
            Class<T> causeType
    ) {
        Throwable current = failure;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return causeType.cast(current);
            }
            current = current.getCause();
        }
        throw new AssertionError("expected cause " + causeType.getName(), failure);
    }

    private static List<CapacityBucketRequest> twoBuckets() {
        return List.of(
                bucket(LocalTime.NOON, LocalTime.of(12, 30), 10, 5),
                bucket(LocalTime.of(12, 30), LocalTime.of(13, 0), 10, 5));
    }

    private static CapacityBucketRequest bucket(
            LocalTime start,
            LocalTime end,
            int maxPeople,
            int maxTeams
    ) {
        return new CapacityBucketRequest(
                start, end, maxPeople, maxTeams, 1, maxPeople, true);
    }

    private static ReservationCapacitiesRequest capacities(
            List<CapacityBucketRequest> buckets
    ) {
        return new ReservationCapacitiesRequest(buckets);
    }

    private static WeeklyInterval weeklyInterval(
            LocalTime start,
            LocalTime end,
            ScheduleIntervalKind kind
    ) {
        int dayStart = (DayOfWeek.MONDAY.getValue() - 1) * 1440;
        return new WeeklyInterval(
                DayOfWeek.MONDAY,
                start,
                end,
                false,
                kind,
                dayStart + start.getHour() * 60 + start.getMinute(),
                dayStart + end.getHour() * 60 + end.getMinute());
    }

    private static IdempotencyKey key(int suffix) {
        return IdempotencyKey.parse(String.format(
                Locale.ROOT,
                "550e8400-e29b-41d4-a716-%012d",
                suffix));
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static void awaitLatch(CountDownLatch latch, String name) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(name + " timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name + " interrupted", exception);
        }
    }

    private static void assertFutureBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(250, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    private static void cancelIfRunning(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private static void shutdownAndAwait(ExecutorService executor) throws Exception {
        executor.shutdownNow();
        if (!executor.awaitTermination(
                EXECUTOR_TERMINATION_TIMEOUT_SECONDS,
                TimeUnit.SECONDS)) {
            throw new AssertionError("reservation hold runtime workers did not terminate");
        }
    }

    private static ThreadFactory workerFactory() {
        return task -> {
            Thread worker = new Thread(
                    task,
                    "reservation-hold-runtime-worker-"
                            + WORKER_SEQUENCE.incrementAndGet());
            worker.setDaemon(true);
            return worker;
        };
    }

    private enum CreationFailurePoint {
        ALLOCATION_INSERT,
        WARNING_INSERT,
        MENU_HOLD_INSERT
    }

    private record Scenario(long operatorId, long storeId, List<Long> originalBucketIds) {
    }

    private record MenuFixture(long menuId, long bucketId) {
    }

    private record GroupArtifactCounts(
            int reservationHolds,
            int holdAllocations,
            int menuHolds,
            int menuHoldItems,
            int inventoryLedgers,
            int transitionAudits,
            int warningTasks
    ) {
    }

    private record Task3CreationSnapshot(
            List<Map<String, Object>> capacityBuckets,
            List<Map<String, Object>> inventoryBuckets,
            List<Map<String, Object>> inventoryLedger,
            List<Map<String, Object>> reservationHolds,
            List<Map<String, Object>> holdAllocations,
            List<Map<String, Object>> menuHolds,
            List<Map<String, Object>> menuHoldItems,
            List<Map<String, Object>> transitionAudits,
            List<Map<String, Object>> warningTasks
    ) {
    }

    private record ConfirmedReservationFixture(long consumerId, long reservationId) {
    }

    private record RacePair<F, S>(F first, S second) {
    }

    private record HoldAttempt(ReservationHoldContracts.Result result, ErrorCode errorCode) {
        private static HoldAttempt succeeded(ReservationHoldContracts.Result result) {
            return new HoldAttempt(result, null);
        }

        private static HoldAttempt failed(ErrorCode errorCode) {
            return new HoldAttempt(null, errorCode);
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(BASE_NOW);

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (ZoneOffset.UTC.equals(zone)) {
                return this;
            }
            return Clock.fixed(now.get(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock reservationHoldRuntimeClock() {
            return new MutableClock();
        }
    }
}
