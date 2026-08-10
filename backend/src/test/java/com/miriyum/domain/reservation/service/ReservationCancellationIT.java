package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.domain.reservation.dto.request.ConsumerCancellationRequest;
import com.miriyum.domain.reservation.dto.request.StoreCancellationRequest;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.menu.entity.Menu;
import com.miriyum.domain.store.menu.model.AllergenDisclosure;
import com.miriyum.domain.store.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.store.menu.model.AllergenIngredientCode;
import com.miriyum.domain.store.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.store.menu.model.MenuContent;
import com.miriyum.domain.store.menu.repository.MenuRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-b")
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false"
        }
)
class ReservationCancellationIT {

    private static final String TIME_ZONE_ID = "Asia/Seoul";
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 10);
    private static final LocalTime START_TIME = LocalTime.NOON;
    private static final Instant ACTIVATED_AT = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-08-02T00:00:00Z");
    private static final int PARTY_SIZE = 2;
    private static final long EXECUTOR_TERMINATION_TIMEOUT_SECONDS = 5L;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();

    private static final String MENU_HOLD_FAILURE_TRIGGER =
            "trg_task7_cancel_menu_hold_failure";
    private static final String AUDIT_FAILURE_TRIGGER =
            "trg_task7_cancel_audit_failure";
    private static final String IDEMPOTENCY_FAILURE_TRIGGER =
            "trg_task7_cancel_idempotency_failure";

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
    private ReservationCancellationCommandFacade facade;

    @Autowired
    private ConsumerAccountRepository consumerRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationCapacityBucketRepository capacityBucketRepository;

    @Autowired
    private ReservationCapacityAllocationRepository allocationRepository;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private MenuInventoryBucketRepository inventoryBucketRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanRowsInForeignKeyOrder() {
        assertThat(jdbcTemplate.getDataSource()).isSameAs(dataSource);
        dropTrigger(MENU_HOLD_FAILURE_TRIGGER);
        dropTrigger(AUDIT_FAILURE_TRIGGER);
        dropTrigger(IDEMPOTENCY_FAILURE_TRIGGER);
        jdbcTemplate.execute("DELETE FROM reservation_cancellation_audits");
        jdbcTemplate.execute("DELETE FROM menu_hold_items");
        jdbcTemplate.execute("DELETE FROM menu_holds");
        jdbcTemplate.execute("DELETE FROM menu_inventory_ledger");
        jdbcTemplate.execute("DELETE FROM menu_inventory_policy_audits");
        jdbcTemplate.execute("DELETE FROM menu_inventory_buckets");
        jdbcTemplate.execute("DELETE FROM reservation_capacity_allocations");
        jdbcTemplate.execute("DELETE FROM reservations");
        jdbcTemplate.execute("DELETE FROM reservation_capacity_buckets");
        jdbcTemplate.execute("DELETE FROM reservation_time_policy_audits");
        jdbcTemplate.execute("DELETE FROM reservation_time_policy_versions");
        jdbcTemplate.execute("DELETE FROM menu_publication_events");
        jdbcTemplate.execute("DELETE FROM menu_version_origin_disclosures");
        jdbcTemplate.execute("DELETE FROM menu_version_allergen_disclosures");
        jdbcTemplate.execute("DELETE FROM menu_version_local_tags");
        jdbcTemplate.execute("DELETE FROM menu_version_secondary_categories");
        jdbcTemplate.execute("DELETE FROM menu_versions");
        jdbcTemplate.execute("DELETE FROM menus");
        jdbcTemplate.execute("DELETE FROM idempotency_commands");
        jdbcTemplate.execute("DELETE FROM store_tag_assignment");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");
    }

    @Test
    @DisplayName("메뉴 없는 소비자 취소는 상태·수용량·감사·멱등 결과를 한 번만 확정한다")
    void noMenuCancellationCommitsStateCapacityAuditAndReplayOnce() {
        // given
        Scenario scenario = confirmedScenario(false, false, 1);
        IdempotencyKey key = key(1);

        // when
        ReservationCancellationCommandResult first = facade.cancelByConsumer(
                scenario.consumerId(), scenario.reservationId(), key,
                new ConsumerCancellationRequest(null));
        ResourceSnapshot afterFirst = snapshot(
                scenario, "consumer", scenario.consumerId());
        ReservationCancellationCommandResult replay = facade.cancelByConsumer(
                scenario.consumerId(), scenario.reservationId(), key,
                new ConsumerCancellationRequest(null));

        // then
        assertThat(first.httpStatus()).isEqualTo(200);
        assertThat(first.data().cancelledBy()).isEqualTo("CONSUMER");
        assertThat(first.data().cancellationReason()).isNull();
        assertThat(replay).isEqualTo(first);
        assertThat(snapshot(scenario, "consumer", scenario.consumerId()))
                .isEqualTo(afterFirst);
        assertThat(reservationStatus(scenario)).isEqualTo("CANCELLED");
        assertThat(auditCount(scenario)).isOne();
        assertCapacity(scenario.originalBucketIds().getFirst(), 0, 0);
        assertThat(menuHoldStatus(scenario)).isNull();
        assertThat(successfulCommandCount(
                "consumer", scenario.consumerId(), key.value())).isOne();
        assertThat(singleCommand("consumer", scenario.consumerId(), key.value()))
                .containsEntry(
                        "request_fingerprint",
                        consumerFingerprint(scenario.reservationId(), null));
    }

    @Test
    @DisplayName("운영자 공백 사유 취소는 메뉴·수용량·감사 correlation과 재생 결과를 보존한다")
    void storeOperatorCancellationCommitsEveryResourceAndReplaysExactDetail() {
        // given
        Scenario scenario = confirmedScenario(true, false, 2);
        IdempotencyKey key = key(2);
        String correlation = correlation(
                "store-operator", scenario.operatorId(), key.value());

        // when
        ReservationCancellationCommandResult first = facade.cancelByStoreOperator(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(), key,
                new StoreCancellationRequest(" "));
        ResourceSnapshot afterFirst = snapshot(
                scenario, "store-operator", scenario.operatorId());
        ReservationCancellationCommandResult replay = facade.cancelByStoreOperator(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(), key,
                new StoreCancellationRequest(" "));

        // then
        assertThat(first.httpStatus()).isEqualTo(200);
        assertThat(replay).isEqualTo(first);
        assertThat(first.data().cancelledBy()).isEqualTo("STORE_OPERATOR");
        assertThat(first.data().cancellationReason()).isEqualTo(" ");
        assertThat(first.data().menuSelections()).singleElement().satisfies(item -> {
            assertThat(item.menuName()).isEqualTo("Cancellation Americano");
            assertThat(item.quantity()).isOne();
        });
        assertThat(snapshot(scenario, "store-operator", scenario.operatorId()))
                .isEqualTo(afterFirst);
        scenario.originalBucketIds().forEach(bucketId -> assertCapacity(bucketId, 0, 0));
        assertThat(menuHoldStatus(scenario)).isEqualTo("RELEASED");
        assertThat(inventoryRemaining(scenario)).isEqualTo(5);
        assertThat(restoreLedgerCount(scenario)).isOne();
        assertThat(restoreOperationId(scenario)).isEqualTo(correlation);

        Map<String, Object> audit = singleAudit(scenario);
        assertThat(audit)
                .containsEntry("actor_type", "STORE_OPERATOR")
                .containsEntry("actor_id", scenario.operatorId())
                .containsEntry("cancellation_reason", " ")
                .containsEntry("command_id", correlation);

        Map<String, Object> command = singleCommand(
                "store-operator", scenario.operatorId(), key.value());
        assertThat(command)
                .containsEntry("principal_namespace", "store-operator")
                .containsEntry("principal_id", scenario.operatorId())
                .containsEntry("command_type", "RESERVATION_CANCEL")
                .containsEntry("idempotency_key", key.value())
                .containsEntry("processing_status", "SUCCEEDED")
                .containsEntry("result_http_status", 200)
                .containsEntry("result_response_code", "SUCCESS")
                .containsEntry("result_resource_type", "RESERVATION")
                .containsEntry("result_resource_id", String.valueOf(scenario.reservationId()));
        String payload = (String) command.get("result_payload");
        assertThat(objectMapper.readTree(payload))
                .isEqualTo(objectMapper.readTree(
                        objectMapper.writeValueAsString(first.data())));
        assertThat(reservationStatus(scenario)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("취소는 원본·최신 수용량 합집합만 복구하고 중간 버전과 배정 이력을 보존한다")
    void carryOverCancellationRestoresOriginalAndLatestButNotIntermediateBuckets() {
        // given
        Scenario scenario = confirmedScenario(false, true, 2);
        List<Map<String, Object>> allocationsBefore = allocationRows(scenario);

        // when
        ReservationCancellationCommandResult result = facade.cancelByConsumer(
                scenario.consumerId(), scenario.reservationId(), key(3),
                new ConsumerCancellationRequest("carry-over"));

        // then
        assertThat(result.httpStatus()).isEqualTo(200);
        scenario.originalBucketIds().forEach(bucketId -> assertCapacity(bucketId, 0, 0));
        scenario.latestBucketIds().forEach(bucketId -> assertCapacity(bucketId, 0, 0));
        scenario.intermediateBucketIds().forEach(
                bucketId -> assertCapacity(bucketId, PARTY_SIZE, 1));
        assertThat(allocationRows(scenario)).isEqualTo(allocationsBefore);
        assertThat(allocationRows(scenario)).hasSize(2);
    }

    @Test
    @DisplayName("완전 비겹침 재게시 뒤 소비자 취소는 원본과 MenuHold만 복구한다")
    void consumerCancellationAfterDisjointPublicationRestoresCommittedResources() {
        Scenario scenario = confirmedScenario(true, false, 2);
        List<Long> disjointLatestIds = seedPublishedCapacityVersion(
                scenario.storeId(),
                2L,
                List.of(new PublishedBucket(
                        LocalTime.of(9, 0), LocalTime.of(10, 0), false)),
                PARTY_SIZE,
                1
        );

        ReservationCancellationCommandResult result = facade.cancelByConsumer(
                scenario.consumerId(), scenario.reservationId(), key(40),
                new ConsumerCancellationRequest("disjoint publication"));

        assertThat(result.httpStatus()).isEqualTo(200);
        assertCommittedResourceEffect(scenario);
        disjointLatestIds.forEach(bucketId -> assertCapacity(bucketId, 0, 0));
    }

    @Test
    @DisplayName("완전 비겹침 재게시 뒤 운영자 취소도 원본 점유만 복구한다")
    void storeOperatorCancellationAfterDisjointPublicationRestoresOriginalOccupancy() {
        Scenario scenario = confirmedScenario(false, false, 2);
        List<Long> disjointLatestIds = seedPublishedCapacityVersion(
                scenario.storeId(),
                2L,
                List.of(new PublishedBucket(
                        LocalTime.of(9, 0), LocalTime.of(10, 0), false)),
                PARTY_SIZE,
                1
        );

        ReservationCancellationCommandResult result = facade.cancelByStoreOperator(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(), key(42),
                new StoreCancellationRequest("disjoint publication"));

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(reservationStatus(scenario)).isEqualTo("CANCELLED");
        assertThat(auditCount(scenario)).isOne();
        scenario.originalBucketIds().forEach(bucketId -> assertCapacity(bucketId, 0, 0));
        disjointLatestIds.forEach(bucketId -> assertCapacity(bucketId, 0, 0));
        assertThat(menuHoldStatus(scenario)).isNull();
    }

    @Test
    @DisplayName("부분 겹침 재게시 뒤 운영자 취소는 원본과 실제 겹친 최신 점유만 복구한다")
    void storeOperatorCancellationAfterPartialPublicationRestoresOverlappingOccupancy() {
        Scenario scenario = confirmedScenario(false, false, 2);
        List<Long> latestBucketIds = seedPublishedCapacityVersion(
                scenario.storeId(),
                2L,
                List.of(
                        new PublishedBucket(
                                START_TIME.plusMinutes(15),
                                START_TIME.plusMinutes(45),
                                true),
                        new PublishedBucket(
                                LocalTime.of(9, 0),
                                LocalTime.of(10, 0),
                                false)
                ),
                PARTY_SIZE,
                1
        );

        ReservationCancellationCommandResult result = facade.cancelByStoreOperator(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(), key(41),
                new StoreCancellationRequest("partial publication"));

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(reservationStatus(scenario)).isEqualTo("CANCELLED");
        assertThat(auditCount(scenario)).isOne();
        scenario.originalBucketIds().forEach(bucketId -> assertCapacity(bucketId, 0, 0));
        latestBucketIds.forEach(bucketId -> assertCapacity(bucketId, 0, 0));
        assertThat(menuHoldStatus(scenario)).isNull();
    }

    @Test
    @DisplayName("부분 겹침 최신 버킷 underflow는 취소의 모든 자원을 롤백한다")
    void partialCurrentBucketUnderflowRollsBackEveryCancellationEffect() {
        Scenario scenario = confirmedScenario(true, false, 2);
        seedPublishedCapacityVersion(
                scenario.storeId(),
                2L,
                List.of(new PublishedBucket(
                        START_TIME.plusMinutes(15),
                        START_TIME.plusMinutes(45),
                        true)),
                PARTY_SIZE,
                0
        );
        ResourceSnapshot before = snapshot(
                scenario, "consumer", scenario.consumerId());

        Throwable failure = catchThrowable(() -> facade.cancelByConsumer(
                scenario.consumerId(), scenario.reservationId(), key(43),
                new ConsumerCancellationRequest("current underflow")));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("capacity occupancy cannot be restored below zero");
        assertThat(snapshot(scenario, "consumer", scenario.consumerId())).isEqualTo(before);
    }

    @Test
    @DisplayName("legacy 취소 정책 버전·해석 시각 누락은 RES006이며 자원을 바꾸지 않는다")
    void legacyCancellationInputsReturnReservation006WithoutMutation() {
        // given
        Scenario nullVersion = confirmedScenario(false, false, 1);
        jdbcTemplate.update(
                "UPDATE reservations SET cancellation_policy_version = NULL "
                        + "WHERE reservation_id = ?",
                nullVersion.reservationId());
        ResourceSnapshot versionBefore = snapshot(
                nullVersion, "consumer", nullVersion.consumerId());

        // when & then
        assertServiceError(
                () -> facade.cancelByConsumer(
                        nullVersion.consumerId(), nullVersion.reservationId(), key(4),
                        new ConsumerCancellationRequest(null)),
                ReservationErrorCode.CANCELLATION_NOT_ALLOWED);
        assertThat(snapshot(nullVersion, "consumer", nullVersion.consumerId()))
                .isEqualTo(versionBefore);

        Scenario nullStartAt = confirmedScenario(false, false, 1);
        makeLegacyTimeUnresolved(nullStartAt);
        ResourceSnapshot timeBefore = snapshot(
                nullStartAt, "consumer", nullStartAt.consumerId());
        assertServiceError(
                () -> facade.cancelByConsumer(
                        nullStartAt.consumerId(), nullStartAt.reservationId(), key(5),
                        new ConsumerCancellationRequest(null)),
                ReservationErrorCode.CANCELLATION_NOT_ALLOWED);
        assertThat(snapshot(nullStartAt, "consumer", nullStartAt.consumerId()))
                .isEqualTo(timeBefore);
    }

    @Test
    @DisplayName("성공 뒤 같은 키의 다른 사유는 COMMON007, 다른 키는 RES005다")
    void replayFingerprintAndTerminalStateRemainDistinct() {
        // given
        Scenario scenario = confirmedScenario(false, false, 1);
        IdempotencyKey firstKey = key(6);
        facade.cancelByConsumer(
                scenario.consumerId(), scenario.reservationId(), firstKey,
                new ConsumerCancellationRequest("first"));
        ResourceSnapshot afterSuccess = snapshot(
                scenario, "consumer", scenario.consumerId());

        // when & then
        assertServiceError(
                () -> facade.cancelByConsumer(
                        scenario.consumerId(), scenario.reservationId(), firstKey,
                        new ConsumerCancellationRequest("different")),
                CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertServiceError(
                () -> facade.cancelByConsumer(
                        scenario.consumerId(), scenario.reservationId(), key(7),
                        new ConsumerCancellationRequest("first")),
                ReservationErrorCode.INVALID_STATE_TRANSITION);
        assertThat(snapshot(scenario, "consumer", scenario.consumerId()))
                .isEqualTo(afterSuccess);
    }

    @Test
    @DisplayName("소비자 타인 예약과 운영자 관리 범위 위반은 공개 404·403을 보존한다")
    void ownershipFailuresUseReservationAndStorePublicErrorsWithoutEffects() {
        // given
        Scenario scenario = confirmedScenario(false, false, 1);
        long foreignConsumer = createConsumer();
        long foreignOperator = createOperator();
        ResourceSnapshot before = snapshot(
                scenario, "consumer", scenario.consumerId());

        // when & then
        assertServiceError(
                () -> facade.cancelByConsumer(
                        foreignConsumer, scenario.reservationId(), key(8),
                        new ConsumerCancellationRequest(null)),
                ReservationErrorCode.RESERVATION_NOT_FOUND);
        assertServiceError(
                () -> facade.cancelByStoreOperator(
                        foreignOperator, scenario.storeId(), scenario.reservationId(), key(9),
                        new StoreCancellationRequest("ownership")),
                StoreErrorCode.ACCESS_DENIED);
        assertServiceError(
                () -> facade.cancelByStoreOperator(
                        foreignOperator, Long.MAX_VALUE, scenario.reservationId(), key(10),
                        new StoreCancellationRequest("missing store")),
                StoreErrorCode.STORE_NOT_FOUND);
        assertThat(snapshot(scenario, "consumer", scenario.consumerId())).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_commands WHERE command_type = ?",
                Integer.class,
                "RESERVATION_CANCEL")).isZero();
    }

    @Test
    @DisplayName("메뉴·감사·멱등 late failure와 underflow·감사 UNIQUE는 전체 롤백한다")
    void injectedPersistenceFailuresRollBackEveryCancellationEffect() {
        for (FailurePoint failurePoint : FailurePoint.values()) {
            Scenario scenario = confirmedScenario(true, false, 2);
            if (failurePoint == FailurePoint.CAPACITY_UNDERFLOW) {
                long lastBucketId = scenario.originalBucketIds().getLast();
                jdbcTemplate.update(
                        "UPDATE reservation_capacity_buckets SET occupied_teams = 0 "
                                + "WHERE reservation_capacity_bucket_id = ?",
                        lastBucketId);
            }
            if (failurePoint == FailurePoint.AUDIT_UNIQUE_CONFLICT) {
                seedConflictingAudit(scenario);
            }
            ResourceSnapshot before = snapshot(
                    scenario, "consumer", scenario.consumerId());
            try {
                createFailureTrigger(failurePoint);
                assertFailurePoint(
                        failurePoint,
                        catchThrowable(() -> facade.cancelByConsumer(
                                scenario.consumerId(), scenario.reservationId(),
                                key(100 + failurePoint.ordinal()),
                                new ConsumerCancellationRequest(
                                        "rollback-" + failurePoint.name()))));
            } finally {
                dropFailureTrigger(failurePoint);
            }
            assertThat(snapshot(scenario, "consumer", scenario.consumerId()))
                    .as("rollback snapshot for %s", failurePoint)
                    .isEqualTo(before);
            assertThat(reservationStatus(scenario)).isEqualTo("CONFIRMED");
        }
    }

    @Test
    @DisplayName("같은 키 경합은 멱등 UNIQUE에서 두 worker를 막고 단일 효과로 수렴한다")
    void sameKeyContentionBlocksAtIdempotencyThenProducesOneEffectAndEqualResponses()
            throws Exception {
        // given
        Scenario scenario = confirmedScenario(true, false, 2);
        IdempotencyKey key = key(200);
        String fingerprint = consumerFingerprint(scenario.reservationId(), null);
        ResourceSnapshot before = snapshot(
                scenario, "consumer", scenario.consumerId());
        CountDownLatch idempotencyLockHeld = new CountDownLatch(1);
        CountDownLatch releaseIdempotencyLock = new CountDownLatch(1);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch startWorkers = new CountDownLatch(1);
        AtomicLong holderConnectionId = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(3, workerFactory());
        Future<Long> holder = null;
        Future<CancellationAttempt> firstWorker = null;
        Future<CancellationAttempt> secondWorker = null;
        try {
            holder = executor.submit(() -> transactions.execute(status -> {
                jdbcTemplate.update("""
                        INSERT INTO idempotency_commands (
                            principal_namespace, principal_id, command_type, idempotency_key,
                            request_fingerprint, processing_status, created_at, updated_at
                        ) VALUES (
                            'consumer', ?, 'RESERVATION_CANCEL', ?, ?,
                            'PROCESSING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
                        )
                        """, scenario.consumerId(), key.value(), fingerprint);
                long connectionId = jdbcTemplate.queryForObject(
                        "SELECT CONNECTION_ID()", Long.class);
                holderConnectionId.set(connectionId);
                idempotencyLockHeld.countDown();
                awaitLatch(releaseIdempotencyLock, "idempotency holder release");
                status.setRollbackOnly();
                return connectionId;
            }));
            assertThat(idempotencyLockHeld.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(holderConnectionId.get()).isPositive();

            firstWorker = executor.submit(() -> cancelAfterStart(
                    scenario, key, null, workersReady, startWorkers));
            secondWorker = executor.submit(() -> cancelAfterStart(
                    scenario, key, null, workersReady, startWorkers));
            assertThat(workersReady.await(5, TimeUnit.SECONDS)).isTrue();
            startWorkers.countDown();

            assertFutureBlocked(firstWorker);
            assertFutureBlocked(secondWorker);
            awaitBlockingWaits(
                    holderConnectionId.get(),
                    "idempotency_commands",
                    "uk_idempotency_commands",
                    2);
            assertThat(snapshot(scenario, "consumer", scenario.consumerId()))
                    .isEqualTo(before);

            releaseIdempotencyLock.countDown();
            holder.get(10, TimeUnit.SECONDS);
            CancellationAttempt first = firstWorker.get(15, TimeUnit.SECONDS);
            CancellationAttempt second = secondWorker.get(15, TimeUnit.SECONDS);
            assertThat(first.errorCode()).isNull();
            assertThat(second.errorCode()).isNull();
            assertThat(first.result()).isEqualTo(second.result());
            assertSingleCommittedEffect(scenario, key.value());
        } finally {
            idempotencyLockHeld.countDown();
            releaseIdempotencyLock.countDown();
            workersReady.countDown();
            workersReady.countDown();
            startWorkers.countDown();
            cancelIfRunning(holder);
            cancelIfRunning(firstWorker);
            cancelIfRunning(secondWorker);
            shutdownAndAwait(executor);
        }
    }

    @Test
    @DisplayName("다른 키 경합은 Reservation PK에서 두 worker를 막고 성공 하나·RES005 하나로 수렴한다")
    void differentKeyContentionBlocksAtReservationThenProducesOneSuccessAndReservation005()
            throws Exception {
        // given
        Scenario scenario = confirmedScenario(true, false, 2);
        IdempotencyKey firstKey = key(201);
        IdempotencyKey secondKey = key(202);
        ResourceSnapshot before = snapshot(
                scenario, "consumer", scenario.consumerId());
        CountDownLatch reservationLockHeld = new CountDownLatch(1);
        CountDownLatch releaseReservationLock = new CountDownLatch(1);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch startWorkers = new CountDownLatch(1);
        AtomicLong holderConnectionId = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(3, workerFactory());
        Future<Long> holder = null;
        Future<CancellationAttempt> firstWorker = null;
        Future<CancellationAttempt> secondWorker = null;
        try {
            holder = executor.submit(() -> transactions.execute(status -> {
                jdbcTemplate.queryForObject(
                        "SELECT reservation_id FROM reservations "
                                + "WHERE reservation_id = ? FOR UPDATE",
                        Long.class,
                        scenario.reservationId());
                long connectionId = jdbcTemplate.queryForObject(
                        "SELECT CONNECTION_ID()", Long.class);
                holderConnectionId.set(connectionId);
                reservationLockHeld.countDown();
                awaitLatch(releaseReservationLock, "reservation holder release");
                return connectionId;
            }));
            assertThat(reservationLockHeld.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(holderConnectionId.get()).isPositive();

            firstWorker = executor.submit(() -> cancelAfterStart(
                    scenario, firstKey, "distinct", workersReady, startWorkers));
            secondWorker = executor.submit(() -> cancelAfterStart(
                    scenario, secondKey, "distinct", workersReady, startWorkers));
            assertThat(workersReady.await(5, TimeUnit.SECONDS)).isTrue();
            startWorkers.countDown();

            assertFutureBlocked(firstWorker);
            assertFutureBlocked(secondWorker);
            awaitBlockingWaits(holderConnectionId.get(), "reservations", "PRIMARY", 2);
            assertThat(snapshot(scenario, "consumer", scenario.consumerId()))
                    .isEqualTo(before);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM idempotency_commands "
                            + "WHERE principal_namespace = 'consumer' "
                            + "AND principal_id = ? AND command_type = 'RESERVATION_CANCEL'",
                    Integer.class,
                    scenario.consumerId())).isZero();

            releaseReservationLock.countDown();
            holder.get(10, TimeUnit.SECONDS);
            List<CancellationAttempt> attempts = List.of(
                    firstWorker.get(15, TimeUnit.SECONDS),
                    secondWorker.get(15, TimeUnit.SECONDS));
            assertThat(attempts)
                    .filteredOn(attempt -> attempt.result() != null)
                    .singleElement()
                    .satisfies(attempt -> assertThat(attempt.result().httpStatus()).isEqualTo(200));
            assertThat(attempts)
                    .filteredOn(attempt -> attempt.errorCode() != null)
                    .singleElement()
                    .extracting(CancellationAttempt::errorCode)
                    .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
            assertThat(successfulCancellationCommandCount(scenario.consumerId())).isOne();
            assertCommittedResourceEffect(scenario);
        } finally {
            reservationLockHeld.countDown();
            releaseReservationLock.countDown();
            workersReady.countDown();
            workersReady.countDown();
            startWorkers.countDown();
            cancelIfRunning(holder);
            cancelIfRunning(firstWorker);
            cancelIfRunning(secondWorker);
            shutdownAndAwait(executor);
        }
    }

    private Scenario confirmedScenario(
            boolean withMenu,
            boolean withCarryOver,
            int originalBucketCount
    ) {
        return transactions.execute(status -> {
            int sequence = SEQUENCE.incrementAndGet();
            StoreOperatorAccount operator = operatorRepository.saveAndFlush(
                    StoreOperatorAccount.create(
                            "cancellation-owner-" + sequence + "@example.com",
                            "hashed-password",
                            "owner"));
            Store store = storeRepository.saveAndFlush(Store.create(
                    operator.getId(),
                    Long.toString(8_000_000_000L + sequence),
                    BusinessType.CAFE,
                    "MiriYum Cancellation Store " + sequence,
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
            ConsumerAccount consumer = consumerRepository.saveAndFlush(
                    ConsumerAccount.createWithContact(
                            "cancellation-consumer-" + sequence + "@example.com",
                            "hashed-password",
                            "consumer",
                            String.format(Locale.ROOT, "010%08d", sequence),
                            "opaque-cancellation-contact-" + sequence));

            int turnoverMinutes = originalBucketCount == 1 ? 0 : 60;
            ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                    store.getId(), 1L, 60, 60, turnoverMinutes);
            policy.activate(ACTIVATED_AT, "cancellation fixture");
            ReservationTimeSnapshot timeSnapshot = ReservationTimeSnapshot.calculate(
                    policy,
                    LocalDateTime.of(SERVICE_DATE, START_TIME),
                    ZoneId.of(TIME_ZONE_ID),
                    null);
            Reservation reservation = reservationRepository.saveAndFlush(Reservation.confirm(
                    consumer.getId(),
                    store.getId(),
                    store.getName(),
                    timeSnapshot,
                    PartyComposition.of(PARTY_SIZE, 0, 0),
                    ReservationContactSnapshot.contactable(
                            "opaque-reservation-target-" + sequence),
                    1L,
                    new ReservationCancellationPolicyVersion(1L),
                    CREATED_AT));

            List<Long> originalBucketIds = seedCapacityVersion(
                    store.getId(), reservation.getId(), 1L, originalBucketCount, true);
            List<Long> intermediateBucketIds = List.of();
            List<Long> latestBucketIds = originalBucketIds;
            if (withCarryOver) {
                intermediateBucketIds = seedCapacityVersion(
                        store.getId(), reservation.getId(), 2L, 1, false);
                latestBucketIds = seedCapacityVersion(
                        store.getId(), reservation.getId(), 3L, 1, false);
            }

            MenuFixture menu = withMenu
                    ? seedConfirmedMenuHold(
                            operator.getId(), store.getId(), consumer.getId(),
                            reservation.getId(), sequence)
                    : null;
            return new Scenario(
                    operator.getId(),
                    store.getId(),
                    consumer.getId(),
                    reservation.getId(),
                    originalBucketIds,
                    intermediateBucketIds,
                    latestBucketIds,
                    menu);
        });
    }

    private List<Long> seedCapacityVersion(
            long storeId,
            long reservationId,
            long policyVersion,
            int bucketCount,
            boolean allocate
    ) {
        List<ReservationCapacityBucket> buckets;
        if (bucketCount == 1) {
            LocalTime endTime = policyVersion == 1L
                    ? occupancyEndTime()
                    : START_TIME.plusHours(2);
            buckets = List.of(capacityBucketRepository.saveAndFlush(
                    capacityBucket(storeId, START_TIME, endTime, policyVersion)));
        } else {
            buckets = List.of(
                    capacityBucketRepository.saveAndFlush(capacityBucket(
                            storeId, START_TIME, START_TIME.plusHours(1), policyVersion)),
                    capacityBucketRepository.saveAndFlush(capacityBucket(
                            storeId, START_TIME.plusHours(1), START_TIME.plusHours(2),
                            policyVersion)));
        }
        if (allocate) {
            buckets.forEach(bucket -> allocationRepository.saveAndFlush(
                    ReservationCapacityAllocation.allocate(
                            reservationId, bucket.getId(), PARTY_SIZE, policyVersion)));
        }
        return buckets.stream().map(ReservationCapacityBucket::getId).toList();
    }

    private List<Long> seedPublishedCapacityVersion(
            long storeId,
            long policyVersion,
            List<PublishedBucket> intervals,
            int occupiedPeople,
            int occupiedTeams
    ) {
        return intervals.stream()
                .map(interval -> capacityBucketRepository.saveAndFlush(
                        ReservationCapacityBucket.create(
                                storeId,
                                SERVICE_DATE,
                                interval.startTime(),
                                interval.endTime(),
                                10,
                                5,
                                interval.overlapsReservation() ? occupiedPeople : 0,
                                interval.overlapsReservation() ? occupiedTeams : 0,
                                1,
                                10,
                                true,
                                policyVersion)))
                .map(ReservationCapacityBucket::getId)
                .toList();
    }

    private static ReservationCapacityBucket capacityBucket(
            long storeId,
            LocalTime startTime,
            LocalTime endTime,
            long policyVersion
    ) {
        return ReservationCapacityBucket.create(
                storeId,
                SERVICE_DATE,
                startTime,
                endTime,
                10,
                5,
                PARTY_SIZE,
                1,
                1,
                10,
                true,
                policyVersion);
    }

    private static LocalTime occupancyEndTime() {
        return START_TIME.plusHours(1);
    }

    private MenuFixture seedConfirmedMenuHold(
            long operatorId,
            long storeId,
            long consumerId,
            long reservationId,
            int sequence
    ) {
        Menu menu = Menu.create(storeId, menuContent(), operatorId, ACTIVATED_AT);
        menu.publish(ACTIVATED_AT);
        menu = menuRepository.saveAndFlush(menu);
        MenuInventoryBucket inventory = inventoryBucketRepository.saveAndFlush(
                MenuInventoryBucket.create(
                        menu.getId(),
                        SERVICE_DATE,
                        START_TIME,
                        SERVICE_DATE,
                        START_TIME.plusHours(1),
                        TIME_ZONE_ID,
                        1L,
                        5,
                        5,
                        0,
                        0,
                        false));
        jdbcTemplate.update(
                "UPDATE menu_inventory_buckets SET online_hold_remaining = 4 "
                        + "WHERE menu_inventory_bucket_id = ?",
                inventory.getId());
        String acquireOperationId = "reservation-create:task7:" + sequence;
        jdbcTemplate.update("""
                INSERT INTO menu_inventory_ledger (
                    operation_id, source_operation_id, menu_inventory_bucket_id,
                    operation_type, pool_type, quantity_delta, quantity_before,
                    quantity_after, created_at, updated_at
                ) VALUES (?, NULL, ?, 'ACQUIRE', 'ONLINE_HOLD', -1, 5, 4,
                          CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, acquireOperationId, inventory.getId());
        jdbcTemplate.update("""
                INSERT INTO menu_holds (
                    reservation_id, store_id, consumer_account_id,
                    service_date, start_time, end_date, end_time,
                    acquire_operation_id, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED',
                          CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """,
                reservationId,
                storeId,
                consumerId,
                SERVICE_DATE,
                START_TIME,
                SERVICE_DATE,
                START_TIME.plusHours(1),
                acquireOperationId);
        long holdId = jdbcTemplate.queryForObject(
                "SELECT menu_hold_id FROM menu_holds WHERE reservation_id = ?",
                Long.class,
                reservationId);
        jdbcTemplate.update("""
                INSERT INTO menu_hold_items (
                    menu_hold_id, menu_id, menu_inventory_bucket_id,
                    menu_policy_version, menu_name_snapshot, unit_price_snapshot,
                    inventory_policy_version, quantity, created_at, updated_at
                ) VALUES (?, ?, ?, 1, 'Cancellation Americano', 5000, 1, 1,
                          CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, holdId, menu.getId(), inventory.getId());
        return new MenuFixture(menu.getId(), inventory.getId(), acquireOperationId);
    }

    private static MenuContent menuContent() {
        return new MenuContent(
                "Cancellation Americano",
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

    private long createConsumer() {
        return transactions.execute(status -> {
            int sequence = SEQUENCE.incrementAndGet();
            return consumerRepository.saveAndFlush(ConsumerAccount.createWithContact(
                    "foreign-cancellation-consumer-" + sequence + "@example.com",
                    "hashed-password",
                    "consumer",
                    String.format(Locale.ROOT, "010%08d", sequence),
                    "opaque-foreign-contact-" + sequence)).getId();
        });
    }

    private long createOperator() {
        return transactions.execute(status -> {
            int sequence = SEQUENCE.incrementAndGet();
            return operatorRepository.saveAndFlush(StoreOperatorAccount.create(
                    "foreign-cancellation-owner-" + sequence + "@example.com",
                    "hashed-password",
                    "owner")).getId();
        });
    }

    private void makeLegacyTimeUnresolved(Scenario scenario) {
        jdbcTemplate.update("""
                UPDATE reservations
                   SET start_time = ?,
                       end_time = ?,
                       start_at = NULL,
                       service_end_at = NULL,
                       occupancy_end_at = NULL,
                       time_zone_id_snapshot = NULL,
                       start_offset_seconds = NULL,
                       service_end_offset_seconds = NULL,
                       occupancy_end_offset_seconds = NULL,
                       slot_interval_minutes = NULL,
                       service_duration_minutes = NULL,
                       turnover_duration_minutes = NULL,
                       reservation_time_policy_store_id = NULL
                 WHERE reservation_id = ?
                """, START_TIME, START_TIME.plusHours(1), scenario.reservationId());
    }

    private void seedConflictingAudit(Scenario scenario) {
        jdbcTemplate.update("""
                INSERT INTO reservation_cancellation_audits (
                    reservation_id, actor_type, actor_id, cancellation_reason,
                    requested_at, occurred_at, before_status, after_status,
                    cancellation_policy_version, capacity_policy_version, command_id
                ) VALUES (?, 'CONSUMER', ?, NULL,
                          '2026-08-03 00:00:00.000000', '2026-08-03 00:00:01.000000',
                          'CONFIRMED', 'CANCELLED', 1, 1, ?)
                """,
                scenario.reservationId(),
                scenario.consumerId(),
                "reservation-cancel:consumer:" + scenario.consumerId()
                        + ":550e8400-e29b-41d4-a716-999999999999");
    }

    private void createFailureTrigger(FailurePoint failurePoint) {
        switch (failurePoint) {
            case MENU_HOLD_UPDATE -> jdbcTemplate.execute("""
                    CREATE TRIGGER trg_task7_cancel_menu_hold_failure
                    BEFORE UPDATE ON menu_holds
                    FOR EACH ROW
                    SIGNAL SQLSTATE '45000'
                        SET MESSAGE_TEXT = 'task7 menu hold release failure'
                    """);
            case AUDIT_INSERT -> jdbcTemplate.execute("""
                    CREATE TRIGGER trg_task7_cancel_audit_failure
                    BEFORE INSERT ON reservation_cancellation_audits
                    FOR EACH ROW
                    SIGNAL SQLSTATE '45000'
                        SET MESSAGE_TEXT = 'task7 audit insert failure'
                    """);
            case IDEMPOTENCY_SUCCEEDED_UPDATE -> jdbcTemplate.execute("""
                    CREATE TRIGGER trg_task7_cancel_idempotency_failure
                    BEFORE UPDATE ON idempotency_commands
                    FOR EACH ROW
                    SIGNAL SQLSTATE '45000'
                        SET MESSAGE_TEXT = 'task7 idempotency success failure'
                    """);
            case CAPACITY_UNDERFLOW, AUDIT_UNIQUE_CONFLICT -> {
            }
        }
    }

    private static void assertFailurePoint(FailurePoint failurePoint, Throwable failure) {
        assertThat(failure)
                .as("failure raised for %s", failurePoint)
                .isNotNull();
        switch (failurePoint) {
            case MENU_HOLD_UPDATE, AUDIT_INSERT, IDEMPOTENCY_SUCCEEDED_UPDATE -> {
                SQLException sqlException = requireCause(failure, SQLException.class);
                assertThat(sqlException.getSQLState()).isEqualTo("45000");
                assertThat(sqlException.getMessage())
                        .contains(triggerMessage(failurePoint));
            }
            case CAPACITY_UNDERFLOW -> assertThat(failure)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("capacity occupancy cannot be restored below zero");
            case AUDIT_UNIQUE_CONFLICT -> {
                SQLException sqlException = requireCause(failure, SQLException.class);
                assertThat(sqlException.getSQLState()).isEqualTo("23000");
                assertThat(sqlException.getErrorCode()).isEqualTo(1062);
                assertThat(sqlException.getMessage())
                        .contains("uk_reservation_cancellation_audits_reservation");
            }
        }
    }

    private static String triggerMessage(FailurePoint failurePoint) {
        return switch (failurePoint) {
            case MENU_HOLD_UPDATE -> "task7 menu hold release failure";
            case AUDIT_INSERT -> "task7 audit insert failure";
            case IDEMPOTENCY_SUCCEEDED_UPDATE -> "task7 idempotency success failure";
            case CAPACITY_UNDERFLOW, AUDIT_UNIQUE_CONFLICT ->
                    throw new IllegalArgumentException("failure point does not use a trigger");
        };
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
        throw new AssertionError(
                "expected cause " + causeType.getName() + " in failure chain",
                failure);
    }

    private void dropFailureTrigger(FailurePoint failurePoint) {
        switch (failurePoint) {
            case MENU_HOLD_UPDATE -> dropTrigger(MENU_HOLD_FAILURE_TRIGGER);
            case AUDIT_INSERT -> dropTrigger(AUDIT_FAILURE_TRIGGER);
            case IDEMPOTENCY_SUCCEEDED_UPDATE -> dropTrigger(IDEMPOTENCY_FAILURE_TRIGGER);
            case CAPACITY_UNDERFLOW, AUDIT_UNIQUE_CONFLICT -> {
            }
        }
    }

    private void dropTrigger(String triggerName) {
        Set<String> allowed = Set.of(
                MENU_HOLD_FAILURE_TRIGGER,
                AUDIT_FAILURE_TRIGGER,
                IDEMPOTENCY_FAILURE_TRIGGER);
        if (!allowed.contains(triggerName)) {
            throw new IllegalArgumentException("unsupported trigger");
        }
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName);
    }

    private ResourceSnapshot snapshot(
            Scenario scenario,
            String principalNamespace,
            long principalId
    ) {
        Map<String, Object> reservation = jdbcTemplate.queryForMap("""
                SELECT status, cancelled_at, fulfilled_at, cancellation_policy_version,
                       start_at, service_end_at, occupancy_end_at, time_zone_id_snapshot,
                       start_offset_seconds, service_end_offset_seconds,
                       occupancy_end_offset_seconds, slot_interval_minutes,
                       service_duration_minutes, turnover_duration_minutes,
                       reservation_time_policy_store_id
                  FROM reservations
                 WHERE reservation_id = ?
                """, scenario.reservationId());
        List<Map<String, Object>> capacities = jdbcTemplate.queryForList("""
                SELECT reservation_capacity_bucket_id, policy_version,
                       occupied_people, occupied_teams
                  FROM reservation_capacity_buckets
                 WHERE store_id = ?
                 ORDER BY reservation_capacity_bucket_id
                """, scenario.storeId());
        List<Map<String, Object>> allocations = allocationRows(scenario);
        List<Map<String, Object>> holds = jdbcTemplate.queryForList("""
                SELECT status, acquire_operation_id
                  FROM menu_holds
                 WHERE reservation_id = ?
                 ORDER BY menu_hold_id
                """, scenario.reservationId());
        List<Map<String, Object>> inventory = scenario.menu() == null
                ? List.of()
                : jdbcTemplate.queryForList("""
                        SELECT online_hold_remaining, shared_remaining, lock_version
                          FROM menu_inventory_buckets
                         WHERE menu_inventory_bucket_id = ?
                        """, scenario.menu().inventoryBucketId());
        List<Map<String, Object>> ledgers = scenario.menu() == null
                ? List.of()
                : jdbcTemplate.queryForList("""
                        SELECT operation_id, source_operation_id, operation_type,
                               pool_type, quantity_delta, quantity_before, quantity_after
                          FROM menu_inventory_ledger
                         WHERE menu_inventory_bucket_id = ?
                         ORDER BY menu_inventory_ledger_id
                        """, scenario.menu().inventoryBucketId());
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT actor_type, actor_id, cancellation_reason, requested_at, occurred_at,
                       before_status, after_status, cancellation_policy_version,
                       capacity_policy_version, command_id
                  FROM reservation_cancellation_audits
                 WHERE reservation_id = ?
                 ORDER BY reservation_cancellation_audit_id
                """, scenario.reservationId());
        List<Map<String, Object>> idempotency = jdbcTemplate.queryForList("""
                SELECT idempotency_key, request_fingerprint, processing_status,
                       result_http_status, result_response_code,
                       result_resource_type, result_resource_id, result_payload
                  FROM idempotency_commands
                 WHERE principal_namespace = ?
                   AND principal_id = ?
                   AND command_type = 'RESERVATION_CANCEL'
                 ORDER BY idempotency_command_id
                """, principalNamespace, principalId);
        return new ResourceSnapshot(
                reservation,
                capacities,
                allocations,
                holds,
                inventory,
                ledgers,
                audits,
                idempotency);
    }

    private List<Map<String, Object>> allocationRows(Scenario scenario) {
        return jdbcTemplate.queryForList("""
                SELECT reservation_capacity_allocation_id,
                       reservation_capacity_bucket_id, occupied_people,
                       occupied_teams, capacity_policy_version
                  FROM reservation_capacity_allocations
                 WHERE reservation_id = ?
                 ORDER BY reservation_capacity_bucket_id
                """, scenario.reservationId());
    }

    private void assertSingleCommittedEffect(Scenario scenario, String normalizedKey) {
        assertThat(successfulCommandCount(
                "consumer", scenario.consumerId(), normalizedKey)).isOne();
        assertCommittedResourceEffect(scenario);
    }

    private void assertCommittedResourceEffect(Scenario scenario) {
        assertThat(reservationStatus(scenario)).isEqualTo("CANCELLED");
        assertThat(auditCount(scenario)).isOne();
        scenario.originalBucketIds().forEach(bucketId -> assertCapacity(bucketId, 0, 0));
        assertThat(menuHoldStatus(scenario)).isEqualTo("RELEASED");
        assertThat(inventoryRemaining(scenario)).isEqualTo(5);
        assertThat(restoreLedgerCount(scenario)).isOne();
    }

    private int successfulCommandCount(
            String namespace,
            long principalId,
            String normalizedKey
    ) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM idempotency_commands
                 WHERE principal_namespace = ?
                   AND principal_id = ?
                   AND command_type = 'RESERVATION_CANCEL'
                   AND idempotency_key = ?
                   AND processing_status = 'SUCCEEDED'
                """, Integer.class, namespace, principalId, normalizedKey);
    }

    private int successfulCancellationCommandCount(long consumerId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM idempotency_commands
                 WHERE principal_namespace = 'consumer'
                   AND principal_id = ?
                   AND command_type = 'RESERVATION_CANCEL'
                   AND processing_status = 'SUCCEEDED'
                """, Integer.class, consumerId);
    }

    private Map<String, Object> singleCommand(
            String namespace,
            long principalId,
            String normalizedKey
    ) {
        return jdbcTemplate.queryForMap("""
                SELECT principal_namespace, principal_id, command_type,
                       idempotency_key, request_fingerprint, processing_status,
                       result_http_status, result_response_code,
                       result_resource_type, result_resource_id, result_payload
                  FROM idempotency_commands
                 WHERE principal_namespace = ?
                   AND principal_id = ?
                   AND command_type = 'RESERVATION_CANCEL'
                   AND idempotency_key = ?
                """, namespace, principalId, normalizedKey);
    }

    private Map<String, Object> singleAudit(Scenario scenario) {
        return jdbcTemplate.queryForMap("""
                SELECT actor_type, actor_id, cancellation_reason, command_id
                  FROM reservation_cancellation_audits
                 WHERE reservation_id = ?
                """, scenario.reservationId());
    }

    private int auditCount(Scenario scenario) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_cancellation_audits "
                        + "WHERE reservation_id = ?",
                Integer.class,
                scenario.reservationId());
    }

    private String reservationStatus(Scenario scenario) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE reservation_id = ?",
                String.class,
                scenario.reservationId());
    }

    private String menuHoldStatus(Scenario scenario) {
        List<String> statuses = jdbcTemplate.queryForList(
                "SELECT status FROM menu_holds WHERE reservation_id = ?",
                String.class,
                scenario.reservationId());
        return statuses.isEmpty() ? null : statuses.getFirst();
    }

    private int inventoryRemaining(Scenario scenario) {
        return jdbcTemplate.queryForObject(
                "SELECT online_hold_remaining FROM menu_inventory_buckets "
                        + "WHERE menu_inventory_bucket_id = ?",
                Integer.class,
                scenario.menu().inventoryBucketId());
    }

    private int restoreLedgerCount(Scenario scenario) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM menu_inventory_ledger
                 WHERE menu_inventory_bucket_id = ?
                   AND operation_type = 'RESTORE'
                """, Integer.class, scenario.menu().inventoryBucketId());
    }

    private String restoreOperationId(Scenario scenario) {
        return jdbcTemplate.queryForObject("""
                SELECT operation_id
                  FROM menu_inventory_ledger
                 WHERE menu_inventory_bucket_id = ?
                   AND operation_type = 'RESTORE'
                """, String.class, scenario.menu().inventoryBucketId());
    }

    private void assertCapacity(long bucketId, int people, int teams) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT occupied_people, occupied_teams
                  FROM reservation_capacity_buckets
                 WHERE reservation_capacity_bucket_id = ?
                """, bucketId))
                .containsEntry("occupied_people", people)
                .containsEntry("occupied_teams", teams);
    }

    private CancellationAttempt cancelAfterStart(
            Scenario scenario,
            IdempotencyKey key,
            String reason,
            CountDownLatch workersReady,
            CountDownLatch startWorkers
    ) {
        workersReady.countDown();
        awaitLatch(startWorkers, "cancellation worker start");
        try {
            return CancellationAttempt.succeeded(facade.cancelByConsumer(
                    scenario.consumerId(),
                    scenario.reservationId(),
                    key,
                    new ConsumerCancellationRequest(reason)));
        } catch (ServiceException exception) {
            return CancellationAttempt.failed(exception.getErrorCode());
        }
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
            throw new AssertionError("reservation cancellation IT workers did not terminate");
        }
    }

    private static ThreadFactory workerFactory() {
        return task -> {
            Thread worker = new Thread(
                    task,
                    "reservation-cancellation-it-worker-"
                            + WORKER_SEQUENCE.incrementAndGet());
            worker.setDaemon(true);
            return worker;
        };
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
            throw new IllegalStateException(
                    "unable to observe MySQL lock waits with the root monitoring connection",
                    exception);
        }
        throw new AssertionError(
                "expected " + expectedWaits + " lock waits blocked by connection "
                        + holderConnectionId + " on " + tableName + "." + indexName
                        + " but last observed " + lastObservedCount);
    }

    private static String consumerFingerprint(long reservationId, String reason) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(canonical, "method", "POST");
        appendCanonical(
                canonical,
                "route",
                "/api/v1/reservations/{reservationId}/cancellations");
        appendCanonical(canonical, "reservationId", String.valueOf(reservationId));
        appendCanonical(canonical, "reason", reason);
        return RequestFingerprint.of(canonical.toString());
    }

    private static void appendCanonical(StringBuilder target, String field, String value) {
        target.append(field).append('=');
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.length()).append(':').append(value);
        }
        target.append('|');
    }

    private static String correlation(String namespace, long actorId, String normalizedKey) {
        return "reservation-cancel:" + namespace + ":" + actorId + ":" + normalizedKey;
    }

    private static void assertServiceError(Runnable invocation, ErrorCode expectedError) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expectedError));
    }

    private static IdempotencyKey key(int suffix) {
        return IdempotencyKey.parse(String.format(
                Locale.ROOT,
                "550e8400-e29b-41d4-a716-%012d",
                suffix));
    }

    private enum FailurePoint {
        MENU_HOLD_UPDATE,
        AUDIT_INSERT,
        IDEMPOTENCY_SUCCEEDED_UPDATE,
        CAPACITY_UNDERFLOW,
        AUDIT_UNIQUE_CONFLICT
    }

    private record Scenario(
            long operatorId,
            long storeId,
            long consumerId,
            long reservationId,
            List<Long> originalBucketIds,
            List<Long> intermediateBucketIds,
            List<Long> latestBucketIds,
            MenuFixture menu
    ) {
    }

    private record PublishedBucket(
            LocalTime startTime,
            LocalTime endTime,
            boolean overlapsReservation
    ) {
    }

    private record MenuFixture(
            long menuId,
            long inventoryBucketId,
            String acquireOperationId
    ) {
    }

    private record ResourceSnapshot(
            Map<String, Object> reservation,
            List<Map<String, Object>> capacities,
            List<Map<String, Object>> allocations,
            List<Map<String, Object>> holds,
            List<Map<String, Object>> inventory,
            List<Map<String, Object>> ledgers,
            List<Map<String, Object>> audits,
            List<Map<String, Object>> idempotency
    ) {
    }

    private record CancellationAttempt(
            ReservationCancellationCommandResult result,
            ErrorCode errorCode
    ) {
        private static CancellationAttempt succeeded(
                ReservationCancellationCommandResult result
        ) {
            return new CancellationAttempt(result, null);
        }

        private static CancellationAttempt failed(ErrorCode errorCode) {
            return new CancellationAttempt(null, errorCode);
        }
    }
}
