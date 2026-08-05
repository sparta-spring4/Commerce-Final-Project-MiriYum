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
import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalRequest;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalResult;
import com.miriyum.domain.store.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
@Tag("integration")
@Import(ReservationCapacityPublicationIT.LockOrderTestConfiguration.class)
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
    private static final long LOCK_WAIT_OBSERVATION_TIMEOUT_MILLIS = 5_000L;
    private static final long LOCK_WAIT_OBSERVATION_POLL_MILLIS = 25L;
    private static volatile CountDownLatch reservationLockQueryStarted;
    private static final AtomicInteger RESERVATION_LOCK_QUERY_ATTEMPTS = new AtomicInteger();
    private static final AtomicInteger RESERVATION_LOCK_WAITS_OBSERVED = new AtomicInteger();

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
    private ReservationCapacityAllocationRepository capacityAllocationRepository;

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
    private StoreServiceIntervalValidationService intervalValidationService;

    @BeforeEach
    void cleanRows() {
        reservationLockQueryStarted = null;
        RESERVATION_LOCK_QUERY_ATTEMPTS.set(0);
        RESERVATION_LOCK_WAITS_OBSERVED.set(0);
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
        long reservationId = seedConsumerAndReservation(owner.storeId());
        seedOriginalCapacityAllocation(owner.storeId(), reservationId);
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
        assertThat(first.data().policyVersion()).isEqualTo(2L);
        assertThat(first.data().buckets()).singleElement().satisfies(bucket -> {
            assertThat(bucket.occupiedPeople()).isEqualTo(5);
            assertThat(bucket.occupiedTeams()).isEqualTo(1);
            assertThat(bucket.availablePeople()).isZero();
            assertThat(bucket.availableTeams()).isZero();
        });
        assertThat(capacityBucketRepository.findAll()).hasSize(2);
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
    @DisplayName("정책을 반복 게시해도 최초 배정 이력을 보존한다")
    void repeatedPublicationsPreserveOriginalAllocationHistory() {
        // given
        OwnerStore owner = createStore("capacity-history@example.com", "1234567894");
        long reservationId = seedConsumerAndReservation(owner.storeId());
        ReservationCapacityBucket originalBucket = seedOriginalCapacityAllocation(
                owner.storeId(),
                reservationId
        );
        acceptEveryStoreInterval();

        // when
        commandFacade.replace(
                owner.operatorId(),
                owner.storeId(),
                SERVICE_DATE,
                key(7),
                request(10, 2)
        );
        commandFacade.replace(
                owner.operatorId(),
                owner.storeId(),
                SERVICE_DATE,
                key(8),
                request(12, 3)
        );

        // then
        assertThat(capacityAllocationRepository.findAll()).singleElement().satisfies(allocation -> {
            assertThat(allocation.getCapacityBucketId()).isEqualTo(originalBucket.getId());
            assertThat(allocation.getCapacityPolicyVersion()).isEqualTo(1L);
        });
        List<ReservationCapacityBucket> publishedBuckets = capacityBucketRepository.findAll().stream()
                .filter(bucket -> bucket.getPolicyVersion() > 1L)
                .toList();
        assertThat(publishedBuckets)
                .extracting(ReservationCapacityBucket::getPolicyVersion)
                .containsExactlyInAnyOrder(2L, 3L);
        assertThat(publishedBuckets).allSatisfy(bucket -> {
                    assertThat(bucket.getOccupiedPeople()).isEqualTo(5);
                    assertThat(bucket.getOccupiedTeams()).isEqualTo(1);
                });
    }

    @Test
    void concurrentPublicationsReceiveDistinctSequentialVersions() throws Exception {
        // given
        OwnerStore owner = createStore("capacity-parallel@example.com", "1234567891");
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
    void publicationAndCancellationRacePreservesCapacityConsistency()
            throws Exception {
        // given
        OwnerStore owner = createStore("capacity-cancel-race@example.com", "1234567893");
        long reservationId = seedConsumerAndReservation(owner.storeId());
        ReservationCapacityBucket originalBucket = seedOriginalCapacityAllocation(
                owner.storeId(),
                reservationId
        );
        Long originalAllocationId = jdbcTemplate.queryForObject(
                "SELECT reservation_capacity_allocation_id "
                        + "FROM reservation_capacity_allocations "
                        + "WHERE reservation_id = ?",
                Long.class,
                reservationId
        );
        acceptEveryStoreInterval();
        commandFacade.replace(
                owner.operatorId(),
                owner.storeId(),
                SERVICE_DATE,
                key(5),
                request(8, 2)
        );
        commandFacade.replace(
                owner.operatorId(),
                owner.storeId(),
                SERVICE_DATE,
                key(6),
                request(8, 2)
        );
        Long currentBucketId = jdbcTemplate.queryForObject(
                "SELECT reservation_capacity_bucket_id "
                        + "FROM reservation_capacity_allocations "
                        + "WHERE reservation_id = ?",
                Long.class,
                reservationId
        );
        assertThat(currentBucketId).isEqualTo(originalBucket.getId());
        assertThat(capacityAllocationRepository.findAll()).singleElement().satisfies(allocation -> {
            assertThat(allocation.getId()).isEqualTo(originalAllocationId);
            assertThat(allocation.getCapacityBucketId()).isEqualTo(originalBucket.getId());
            assertThat(allocation.getCapacityPolicyVersion()).isEqualTo(1L);
            assertThat(allocation.getOccupiedPeople()).isEqualTo(5);
            assertThat(allocation.getOccupiedTeams()).isEqualTo(1);
        });
        assertThat(capacityBucketRepository.findAll().stream()
                .filter(bucket -> bucket.getPolicyVersion() == 2L || bucket.getPolicyVersion() == 3L)
                .toList())
                .hasSize(2)
                .extracting(ReservationCapacityBucket::getPolicyVersion)
                .containsExactlyInAnyOrder(2L, 3L);
        assertThat(capacityBucketRepository.findAll().stream()
                .filter(bucket -> bucket.getPolicyVersion() == 2L || bucket.getPolicyVersion() == 3L)
                .toList())
                .allSatisfy(bucket -> {
                    assertThat(bucket.getOccupiedPeople()).isEqualTo(5);
                    assertThat(bucket.getOccupiedTeams()).isEqualTo(1);
                });
        Long latestV3BucketId = jdbcTemplate.queryForObject(
                "SELECT reservation_capacity_bucket_id "
                        + "FROM reservation_capacity_buckets "
                        + "WHERE store_id = ? AND service_date = ? AND policy_version = 3 "
                        + "ORDER BY reservation_capacity_bucket_id ASC",
                Long.class,
                owner.storeId(),
                SERVICE_DATE
        );
        assertThat(latestV3BucketId).isNotEqualTo(originalBucket.getId());
        CountDownLatch cancellationLockedReservation = new CountDownLatch(1);
        CountDownLatch publicationStartedReservationLock = new CountDownLatch(1);
        reservationLockQueryStarted = publicationStartedReservationLock;

        // when
        ReservationCapacityCommandResult publication;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> cancellation = executor.submit(() -> {
                simulateCancellation(
                        reservationId,
                        originalAllocationId,
                        latestV3BucketId,
                        cancellationLockedReservation,
                        publicationStartedReservationLock
                );
                return null;
            });
            assertThat(cancellationLockedReservation.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ReservationCapacityCommandResult> pendingPublication = executor.submit(
                    () -> commandFacade.replace(
                            owner.operatorId(),
                            owner.storeId(),
                            SERVICE_DATE,
                            key(7),
                            request(8, 2)
                    )
            );
            cancellation.get(10, TimeUnit.SECONDS);
            publication = pendingPublication.get(10, TimeUnit.SECONDS);
        }

        // then
        assertThat(RESERVATION_LOCK_QUERY_ATTEMPTS).hasValue(1);
        assertThat(RESERVATION_LOCK_WAITS_OBSERVED).hasValue(1);
        assertThat(publication.data().policyVersion()).isEqualTo(4L);
        assertThat(publication.data().buckets()).singleElement().satisfies(bucket -> {
            assertThat(bucket.occupiedPeople()).isZero();
            assertThat(bucket.occupiedTeams()).isZero();
        });
        assertThat(capacityAllocationRepository.findAll()).singleElement().satisfies(allocation -> {
            assertThat(allocation.getId()).isEqualTo(originalAllocationId);
            assertThat(allocation.getCapacityBucketId()).isEqualTo(originalBucket.getId());
            assertThat(allocation.getCapacityPolicyVersion()).isEqualTo(1L);
            assertThat(allocation.getOccupiedPeople()).isEqualTo(5);
            assertThat(allocation.getOccupiedTeams()).isEqualTo(1);
        });
        assertThat(capacityBucketRepository.findAll().stream()
                .filter(bucket -> bucket.getId().equals(originalBucket.getId()))
                .toList())
                .singleElement()
                .satisfies(bucket -> {
                    assertThat(bucket.getOccupiedPeople()).isZero();
                    assertThat(bucket.getOccupiedTeams()).isZero();
                });
        assertThat(capacityBucketRepository.findAll().stream()
                .filter(bucket -> bucket.getId().equals(latestV3BucketId))
                .toList())
                .singleElement()
                .satisfies(bucket -> {
                    assertThat(bucket.getPolicyVersion()).isEqualTo(3L);
                    assertThat(bucket.getOccupiedPeople()).isZero();
                    assertThat(bucket.getOccupiedTeams()).isZero();
                });
        assertThat(capacityBucketRepository.findAll().stream()
                .filter(bucket -> bucket.getPolicyVersion() == 2L)
                .toList())
                .singleElement()
                .satisfies(bucket -> {
                    assertThat(bucket.getOccupiedPeople()).isEqualTo(5);
                    assertThat(bucket.getOccupiedTeams()).isEqualTo(1);
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE reservation_id = ?",
                String.class,
                reservationId
        )).isEqualTo("CANCELLED");

        simulateCancellation(reservationId, originalAllocationId, latestV3BucketId, null, null);

        assertThat(capacityAllocationRepository.findAll()).singleElement().satisfies(allocation -> {
            assertThat(allocation.getId()).isEqualTo(originalAllocationId);
            assertThat(allocation.getCapacityBucketId()).isEqualTo(originalBucket.getId());
        });
        assertThat(capacityBucketRepository.findAll().stream()
                .filter(bucket -> bucket.getId().equals(originalBucket.getId())
                        || bucket.getId().equals(latestV3BucketId))
                .toList())
                .allSatisfy(bucket -> {
                    assertThat(bucket.getOccupiedPeople()).isZero();
                    assertThat(bucket.getOccupiedTeams()).isZero();
                });
    }

    @Test
    void bucketPersistenceFailureRollsBackTheIdempotencyClaim() {
        // given
        OwnerStore owner = createStore("capacity-rollback@example.com", "1234567892");
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

    @Test
    void cancellationSimulationDeduplicatesSameOriginalAndLatestBucket() {
        // given
        OwnerStore owner = createStore("capacity-cancel-same-bucket@example.com", "1234567895");
        long reservationId = seedConsumerAndReservation(owner.storeId());
        ReservationCapacityBucket originalBucket = seedOriginalCapacityAllocation(
                owner.storeId(),
                reservationId
        );
        Long originalAllocationId = jdbcTemplate.queryForObject(
                "SELECT reservation_capacity_allocation_id "
                        + "FROM reservation_capacity_allocations "
                        + "WHERE reservation_id = ?",
                Long.class,
                reservationId
        );

        // when
        simulateCancellation(
                reservationId,
                originalAllocationId,
                originalBucket.getId(),
                null,
                null
        );

        // then
        assertThat(capacityAllocationRepository.findAll()).singleElement().satisfies(allocation -> {
            assertThat(allocation.getId()).isEqualTo(originalAllocationId);
            assertThat(allocation.getCapacityBucketId()).isEqualTo(originalBucket.getId());
            assertThat(allocation.getOccupiedPeople()).isEqualTo(5);
            assertThat(allocation.getOccupiedTeams()).isEqualTo(1);
        });
        assertThat(capacityBucketRepository.findAll()).singleElement().satisfies(bucket -> {
            assertThat(bucket.getOccupiedPeople()).isZero();
            assertThat(bucket.getOccupiedTeams()).isZero();
        });
    }

    private void simulateCancellation(
            long reservationId,
            long expectedOriginalAllocationId,
            long expectedLatestV3BucketId,
            CountDownLatch cancellationLockedReservation,
            CountDownLatch publicationStartedReservationLock
    ) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            String reservationStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM reservations WHERE reservation_id = ? FOR UPDATE",
                    String.class,
                    reservationId
            );
            if (cancellationLockedReservation != null) {
                cancellationLockedReservation.countDown();
            }
            if (publicationStartedReservationLock != null) {
                assertThat(await(publicationStartedReservationLock, 5, TimeUnit.SECONDS)).isTrue();
                awaitPublicationReservationLockWait(reservationId);
            }
            if (!"CONFIRMED".equals(reservationStatus)) {
                return;
            }

            List<CapacityAllocationSnapshot> originalAllocations = jdbcTemplate.query(
                    "SELECT reservation_capacity_allocation_id, reservation_capacity_bucket_id, "
                            + "occupied_people, occupied_teams "
                            + "FROM reservation_capacity_allocations "
                            + "WHERE reservation_id = ? "
                            + "ORDER BY reservation_capacity_allocation_id ASC FOR UPDATE",
                    (resultSet, rowNumber) -> new CapacityAllocationSnapshot(
                            resultSet.getLong("reservation_capacity_allocation_id"),
                            resultSet.getLong("reservation_capacity_bucket_id"),
                            resultSet.getInt("occupied_people"),
                            resultSet.getInt("occupied_teams")
                    ),
                    reservationId
            );
            assertThat(originalAllocations).singleElement().satisfies(allocation -> {
                assertThat(allocation.allocationId()).isEqualTo(expectedOriginalAllocationId);
            });
            CapacityAllocationSnapshot originalAllocation = originalAllocations.getFirst();
            Long originalBucketId = jdbcTemplate.queryForObject(
                    "SELECT reservation_capacity_bucket_id "
                            + "FROM reservation_capacity_buckets "
                            + "WHERE reservation_capacity_bucket_id = ? "
                            + "ORDER BY reservation_capacity_bucket_id ASC FOR UPDATE",
                    Long.class,
                    originalAllocation.capacityBucketId()
            );
            Long latestV3BucketId = originalBucketId;
            if (!originalBucketId.equals(expectedLatestV3BucketId)) {
                latestV3BucketId = jdbcTemplate.queryForObject(
                        "SELECT reservation_capacity_bucket_id "
                                + "FROM reservation_capacity_buckets "
                                + "WHERE reservation_capacity_bucket_id = ? "
                                + "AND reservation_capacity_bucket_id <> ? "
                                + "AND policy_version = 3 "
                                + "AND start_time < ? AND end_time > ? "
                                + "ORDER BY reservation_capacity_bucket_id ASC FOR UPDATE",
                        Long.class,
                        expectedLatestV3BucketId,
                        originalBucketId,
                        LocalTime.of(19, 0),
                        LocalTime.of(18, 0)
                );
            }
            assertThat(latestV3BucketId).isEqualTo(expectedLatestV3BucketId);

            int cancelled = jdbcTemplate.update(
                    "UPDATE reservations SET status = 'CANCELLED', cancelled_at = NOW(6) "
                            + "WHERE reservation_id = ? AND status = 'CONFIRMED'",
                    reservationId
            );
            assertThat(cancelled).isOne();
            jdbcTemplate.update(
                    "UPDATE reservation_capacity_buckets "
                            + "SET occupied_people = occupied_people - ?, "
                            + "occupied_teams = occupied_teams - ? "
                            + "WHERE reservation_capacity_bucket_id = ?",
                    originalAllocation.occupiedPeople(),
                    originalAllocation.occupiedTeams(),
                    originalBucketId
            );
            if (!originalBucketId.equals(latestV3BucketId)) {
                jdbcTemplate.update(
                        "UPDATE reservation_capacity_buckets "
                                + "SET occupied_people = occupied_people - ?, "
                                + "occupied_teams = occupied_teams - ? "
                                + "WHERE reservation_capacity_bucket_id = ?",
                        originalAllocation.occupiedPeople(),
                        originalAllocation.occupiedTeams(),
                        latestV3BucketId
                );
            }
        });
    }

    private void awaitPublicationReservationLockWait(long reservationId) {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(LOCK_WAIT_OBSERVATION_TIMEOUT_MILLIS);
        try (Connection monitoringConnection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                "root",
                MYSQL.getPassword()
        )) {
            while (System.nanoTime() < deadlineNanos) {
                if (hasReservationLockWait(monitoringConnection, reservationId)) {
                    RESERVATION_LOCK_WAITS_OBSERVED.incrementAndGet();
                    return;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(LOCK_WAIT_OBSERVATION_POLL_MILLIS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "interrupted while observing publication reservation lock wait",
                            exception
                    );
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "unable to observe publication reservation lock waits with MySQL root connection",
                    exception
            );
        }
        throw new AssertionError(
                "publication never entered a MySQL row-lock wait for reservation " + reservationId
        );
    }

    private static boolean hasReservationLockWait(Connection connection, long reservationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) "
                        + "FROM performance_schema.data_lock_waits lock_wait "
                        + "JOIN performance_schema.data_locks requested_lock "
                        + "ON requested_lock.engine = lock_wait.engine "
                        + "AND requested_lock.engine_lock_id "
                        + "= lock_wait.requesting_engine_lock_id "
                        + "WHERE requested_lock.object_schema = DATABASE() "
                        + "AND requested_lock.object_name = 'reservations' "
                        + "AND requested_lock.lock_data = ?"
        )) {
            statement.setString(1, Long.toString(reservationId));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
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

    private ReservationCapacityBucket seedOriginalCapacityAllocation(
            long storeId,
            long reservationId
    ) {
        ReservationCapacityBucket bucket = capacityBucketRepository.saveAndFlush(
                ReservationCapacityBucket.create(
                        storeId,
                        SERVICE_DATE,
                        LocalTime.of(18, 0),
                        LocalTime.of(19, 0),
                        8,
                        2,
                        5,
                        1,
                        1,
                        4,
                        true,
                        1L
                )
        );
        capacityAllocationRepository.saveAndFlush(ReservationCapacityAllocation.allocate(
                reservationId,
                bucket.getId(),
                5,
                1L
        ));
        return bucket;
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

    private record CapacityAllocationSnapshot(
            long allocationId,
            long capacityBucketId,
            int occupiedPeople,
            int occupiedTeams
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LockOrderTestConfiguration {

        @Bean
        HibernatePropertiesCustomizer reservationLockQueryObserver() {
            StatementInspector inspector = sql -> {
                CountDownLatch latch = reservationLockQueryStarted;
                String normalized = sql.toLowerCase(java.util.Locale.ROOT);
                if (latch != null
                        && normalized.contains(" from reservations ")
                        && normalized.contains(" for update")) {
                    RESERVATION_LOCK_QUERY_ATTEMPTS.incrementAndGet();
                    latch.countDown();
                }
                return sql;
            };
            return properties -> properties.put(
                    "hibernate.session_factory.statement_inspector",
                    inspector
            );
        }
    }
}
