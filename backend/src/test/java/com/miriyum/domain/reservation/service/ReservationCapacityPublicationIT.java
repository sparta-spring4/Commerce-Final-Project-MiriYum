package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.dto.request.CapacityBucketRequest;
import com.miriyum.domain.reservation.dto.request.ReservationCapacitiesRequest;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.schedule.dto.StoreReservationWindowResult;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalRequest;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalResult;
import com.miriyum.domain.store.schedule.service.StoreScheduleService;
import com.miriyum.domain.store.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false"
        }
)
class ReservationCapacityPublicationIT {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 10);

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add(
                "spring.datasource.hikari.connection-init-sql",
                () -> "SET SESSION innodb_lock_wait_timeout = 1"
        );
    }

    @Autowired
    private ReservationCapacityCommandFacade commandFacade;

    @MockitoSpyBean
    private ReservationCapacityBucketRepository capacityBucketRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOperatorAccountRepository operatorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private StoreScheduleService storeScheduleService;

    @MockitoBean
    private StoreServiceIntervalValidationService intervalValidationService;

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.execute("DELETE FROM reservation_capacity_allocations");
        jdbcTemplate.execute("DELETE FROM reservations");
        jdbcTemplate.execute("DELETE FROM reservation_capacity_buckets");
        jdbcTemplate.execute("DELETE FROM idempotency_commands");
        jdbcTemplate.execute("DELETE FROM store_tag_assignment");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");
    }

    @Test
    void replayKeepsOneVersionAndCarriesConfirmedOccupancy() {
        // given
        OwnerStore owner = createStore("capacity-replay@example.com", "1234567890");
        seedConsumerAndReservation(owner.storeId());
        givenOneHourWindow(owner.storeId());
        acceptEveryStoreInterval();
        ReservationCapacitiesRequest request = request(4, 0);

        // when
        ReservationCapacityCommandResult first = commandFacade.replace(
                owner.operatorId(),
                owner.storeId(),
                SERVICE_DATE,
                key(1),
                request
        );
        ReservationCapacityCommandResult replay = commandFacade.replace(
                owner.operatorId(),
                owner.storeId(),
                SERVICE_DATE,
                key(1),
                request
        );

        // then
        assertThat(replay.data()).isEqualTo(first.data());
        assertThatThrownBy(() -> commandFacade.replace(
                owner.operatorId(),
                owner.storeId(),
                SERVICE_DATE,
                key(1),
                request(8, 2)
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(
                        CommonErrorCode.IDEMPOTENCY_KEY_REUSED
                ));
        assertThat(first.data().policyVersion()).isEqualTo(1L);
        assertThat(first.data().buckets()).singleElement().satisfies(bucket -> {
            assertThat(bucket.occupiedPeople()).isEqualTo(5);
            assertThat(bucket.occupiedTeams()).isEqualTo(1);
            assertThat(bucket.availablePeople()).isZero();
            assertThat(bucket.availableTeams()).isZero();
        });
        assertThat(capacityBucketRepository.findAll()).hasSize(1);
        assertThat(jdbcTemplate.queryForList(
                """
                        SELECT allocation.occupied_people,
                               allocation.occupied_teams,
                               allocation.capacity_policy_version
                        FROM reservation_capacity_allocations allocation
                        JOIN reservation_capacity_buckets bucket
                          ON bucket.reservation_capacity_bucket_id =
                             allocation.reservation_capacity_bucket_id
                        WHERE bucket.store_id = ?
                          AND bucket.service_date = ?
                          AND bucket.policy_version = 1
                        """,
                owner.storeId(),
                SERVICE_DATE
        )).singleElement().satisfies(allocation -> {
            assertThat(allocation.get("occupied_people")).isEqualTo(5);
            assertThat(allocation.get("occupied_teams")).isEqualTo(1);
            assertThat(allocation.get("capacity_policy_version")).isEqualTo(1L);
        });
        assertThat(count("idempotency_commands")).isEqualTo(1);
    }

    @Test
    void concurrentPublicationsReceiveDistinctSequentialVersions() throws Exception {
        // given
        OwnerStore owner = createStore("capacity-parallel@example.com", "1234567891");
        givenOneHourWindow(owner.storeId());
        acceptEveryStoreInterval();
        CountDownLatch startGate = new CountDownLatch(1);

        // when
        List<Long> versions;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Long> first = executor.submit(() -> {
                startGate.await();
                return commandFacade.replace(
                        owner.operatorId(),
                        owner.storeId(),
                        SERVICE_DATE,
                        key(2),
                        request(8, 2)
                ).data().policyVersion();
            });
            Future<Long> second = executor.submit(() -> {
                startGate.await();
                return commandFacade.replace(
                        owner.operatorId(),
                        owner.storeId(),
                        SERVICE_DATE,
                        key(3),
                        request(10, 3)
                ).data().policyVersion();
            });
            startGate.countDown();
            versions = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );
        }

        // then
        assertThat(versions).containsExactlyInAnyOrder(1L, 2L);
        assertThat(capacityBucketRepository.findAll())
                .extracting(ReservationCapacityBucket::getPolicyVersion)
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(count("idempotency_commands")).isEqualTo(2);
    }

    @Test
    void publicationExcludesAReservationCancelledWhileItWaitsForTheReservationLock()
            throws Exception {
        // given
        OwnerStore owner = createStore("capacity-cancel-race@example.com", "1234567893");
        long reservationId = seedConsumerAndReservation(owner.storeId());
        givenOneHourWindow(owner.storeId());
        acceptEveryStoreInterval();
        CountDownLatch cancellationUpdated = new CountDownLatch(1);
        CountDownLatch allowCancellationCommit = new CountDownLatch(1);

        // when
        ReservationCapacityCommandResult publication;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> cancellation = executor.submit(() -> {
                new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> {
                            jdbcTemplate.queryForObject(
                                    "SELECT reservation_id FROM reservations "
                                            + "WHERE reservation_id = ? FOR UPDATE",
                                    Long.class,
                                    reservationId
                            );
                            jdbcTemplate.update(
                                    "UPDATE reservations "
                                            + "SET status = 'CANCELLED', cancelled_at = NOW(6) "
                                            + "WHERE reservation_id = ?",
                                    reservationId
                            );
                            cancellationUpdated.countDown();
                            await(allowCancellationCommit, 4, TimeUnit.SECONDS);
                        });
                return null;
            });
            assertThat(cancellationUpdated.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ReservationCapacityCommandResult> pendingPublication = executor.submit(
                    () -> commandFacade.replace(
                            owner.operatorId(),
                            owner.storeId(),
                            SERVICE_DATE,
                            key(5),
                            request(8, 2)
                    )
            );
            ReservationCapacityCommandResult completedBeforeCancellation = null;
            try {
                completedBeforeCancellation = pendingPublication.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException expectedLockWait) {
                // The locking query must wait until the cancellation commits.
            } finally {
                allowCancellationCommit.countDown();
            }
            cancellation.get(10, TimeUnit.SECONDS);
            publication = completedBeforeCancellation == null
                    ? pendingPublication.get(10, TimeUnit.SECONDS)
                    : completedBeforeCancellation;
        }

        // then
        assertThat(publication.data().buckets()).singleElement().satisfies(bucket -> {
            assertThat(bucket.occupiedPeople()).isZero();
            assertThat(bucket.occupiedTeams()).isZero();
        });
        assertThat(count("reservation_capacity_allocations")).isZero();
    }

    @Test
    void bucketPersistenceFailureRollsBackTheIdempotencyClaim() {
        // given
        OwnerStore owner = createStore("capacity-rollback@example.com", "1234567892");
        givenOneHourWindow(owner.storeId());
        acceptEveryStoreInterval();
        doAnswer(invocation -> {
            List<ReservationCapacityBucket> buckets = invocation.getArgument(0);
            capacityBucketRepository.saveAndFlush(buckets.getFirst());
            throw new DataIntegrityViolationException("forced bucket failure");
        })
                .when(capacityBucketRepository)
                .saveAllAndFlush(any());

        // when & then
        assertThatThrownBy(() -> commandFacade.replace(
                owner.operatorId(),
                owner.storeId(),
                SERVICE_DATE,
                key(4),
                twoBucketRequest()
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(capacityBucketRepository.findAll()).isEmpty();
        assertThat(count("idempotency_commands")).isZero();
    }

    private OwnerStore createStore(String email, String registrationNumber) {
        long operatorId = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create(email, "hashed", "owner")
        ).getId();
        long storeId = storeRepository.saveAndFlush(Store.create(
                operatorId,
                registrationNumber,
                BusinessType.CAFE,
                "Reservation Capacity Store",
                "",
                Region.SEOUL,
                "Seoul",
                "CAFE_BAKERY",
                Set.of(),
                true,
                true,
                true,
                "Asia/Seoul",
                LocalDateTime.of(2026, 8, 1, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1"
        )).getId();
        return new OwnerStore(operatorId, storeId);
    }

    private void givenOneHourWindow(long storeId) {
        LocalDateTime start = LocalDateTime.of(SERVICE_DATE, LocalTime.of(18, 0));
        given(storeScheduleService.resolveReservationWindows(
                List.of(storeId),
                SERVICE_DATE,
                LocalTime.of(18, 0)
        )).willReturn(List.of(StoreReservationWindowResult.accepting(
                storeId,
                "Asia/Seoul",
                start,
                start.plusHours(1)
        )));
    }

    private void acceptEveryStoreInterval() {
        given(intervalValidationService.validateServiceIntervals(any()))
                .willAnswer(invocation -> {
                    List<StoreServiceIntervalRequest> requests = invocation.getArgument(0);
                    return requests.stream()
                            .map(request -> StoreServiceIntervalResult.of(request, true))
                            .toList();
                });
    }

    private long seedConsumerAndReservation(long storeId) {
        jdbcTemplate.update(
                """
                        INSERT INTO consumer_accounts (
                            email, password_hash, name, status, created_at, updated_at
                        ) VALUES (?, 'hashed', 'consumer', 'ACTIVE', NOW(6), NOW(6))
                        """,
                "capacity-consumer@example.com"
        );
        Long consumerId = jdbcTemplate.queryForObject(
                "SELECT consumer_account_id FROM consumer_accounts WHERE email = ?",
                Long.class,
                "capacity-consumer@example.com"
        );
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                storeId,
                1L,
                15,
                60,
                0
        );
        policy.activate(Instant.parse("2026-08-01T00:00:00Z"), "initial publication");
        ReservationTimeSnapshot snapshot = ReservationTimeSnapshot.calculate(
                policy,
                LocalDateTime.of(SERVICE_DATE, LocalTime.of(18, 0)),
                ZoneId.of("Asia/Seoul"),
                null
        );
        return reservationRepository.saveAndFlush(Reservation.confirm(
                consumerId,
                storeId,
                "Reservation Capacity Store",
                snapshot,
                PartyComposition.of(3, 1, 1),
                ReservationContactSnapshot.contactable("notification-target:consumer"),
                1L,
                Instant.parse("2026-08-01T00:00:00Z")
        )).getId();
    }

    private static ReservationCapacitiesRequest request(int maxPeople, int maxTeams) {
        return new ReservationCapacitiesRequest(List.of(new CapacityBucketRequest(
                LocalTime.of(18, 0),
                LocalTime.of(19, 0),
                maxPeople,
                maxTeams,
                1,
                Math.min(maxPeople, 4),
                true
        )));
    }

    private static ReservationCapacitiesRequest twoBucketRequest() {
        return new ReservationCapacitiesRequest(List.of(
                new CapacityBucketRequest(
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        8,
                        2,
                        1,
                        4,
                        true
                ),
                new CapacityBucketRequest(
                        LocalTime.of(18, 30),
                        LocalTime.of(19, 0),
                        8,
                        2,
                        1,
                        4,
                        true
                )
        ));
    }

    private IdempotencyKey key(int suffix) {
        return IdempotencyKey.parse(String.format(
                java.util.Locale.ROOT,
                "550e8400-e29b-41d4-a716-%012d",
                suffix
        ));
    }

    private int count(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Integer.class
        );
        return count == null ? 0 : count;
    }

    private static boolean await(
            CountDownLatch latch,
            long timeout,
            TimeUnit unit
    ) {
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while coordinating concurrency test", exception);
        }
    }

    private record OwnerStore(long operatorId, long storeId) {
    }
}
