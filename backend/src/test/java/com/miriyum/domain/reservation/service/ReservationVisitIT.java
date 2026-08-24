package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.qrepoch.ConsumerQrEpochService;
import com.miriyum.domain.auth.qrepoch.ConsumerQrEpochSnapshot;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.reservation.dto.request.ReservationCheckInRequest;
import com.miriyum.domain.reservation.dto.request.ReservationFulfillmentRequest;
import com.miriyum.domain.reservation.dto.request.ReservationNoShowRequest;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationNoShowReason;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-b")
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
@Import(ReservationVisitIT.TestClockConfiguration.class)
class ReservationVisitIT {

    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0.40");
    private static final Instant START_AT = Instant.parse("2026-08-16T01:00:00Z");
    private static final ConsumerQrEpochSnapshot EPOCH = new ConsumerQrEpochSnapshot(
            1L, "v1." + "A".repeat(43)
    );
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withCommand("--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired private ReservationCheckInQrGrantCommandFacade grantFacade;
    @Autowired private ReservationVisitCommandFacade visitFacade;
    @Autowired private ReservationFulfillmentCommandFacade fulfillmentFacade;
    @Autowired private ReservationCheckInQrTokenService tokenService;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactions;
    @Autowired private StoreOperatorAccountRepository operatorRepository;
    @Autowired private ConsumerAccountRepository consumerRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private ReservationRepository reservationRepository;
    @MockitoBean private ConsumerQrEpochService epochService;

    @BeforeEach
    void setUpEpoch() {
        given(epochService.captureCurrent(anyLong())).willAnswer(invocation ->
                new ConsumerQrEpochSnapshot(
                        invocation.getArgument(0), EPOCH.opaqueVersion()
                ));
    }

    @AfterEach
    void dropFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_qr_check_in_audit_failure");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_visit_notification_failure");
    }

    @Test
    void rotationStoresOnlyLatestDigestAndReplayReturnsCommittedQrResult() {
        Scenario scenario = confirmedScenario();
        clock.set(START_AT.plusSeconds(60));

        ReservationCheckInQrGrantResult first = grantFacade.issue(
                scenario.consumerId(), scenario.reservationId()
        );
        ReservationCheckInQrGrantResult second = grantFacade.issue(
                scenario.consumerId(), scenario.reservationId()
        );

        assertThat(first.data().tokenVersion()).isEqualTo(1L);
        assertThat(second.data().tokenVersion()).isEqualTo(2L);
        byte[] storedDigest = jdbcTemplate.queryForObject(
                "SELECT token_digest FROM reservation_check_in_qr_grants WHERE reservation_id = ?",
                byte[].class,
                scenario.reservationId()
        );
        assertThat(storedDigest).containsExactly(tokenService.digest(second.data().qrToken()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_check_in_audits "
                        + "WHERE reservation_id = ? AND event_type = 'QR_GRANT_ISSUED'",
                Integer.class,
                scenario.reservationId()
        )).isEqualTo(2);

        IdempotencyKey key = key(1);
        ReservationCheckInRequest request = new ReservationCheckInRequest(
                second.data().qrToken()
        );
        ReservationVisitCommandResult fresh = visitFacade.checkIn(
                scenario.operatorId(), scenario.storeId(), key, request
        );
        clock.set(START_AT.plusSeconds(900));
        ReservationVisitCommandResult replay = visitFacade.checkIn(
                scenario.operatorId(), scenario.storeId(), key, request
        );

        assertThat(fresh.data().status()).isEqualTo("FULFILLED");
        assertThat(replay).isEqualTo(fresh);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_fulfillment_audits WHERE reservation_id = ?",
                Integer.class,
                scenario.reservationId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_check_in_audits "
                        + "WHERE reservation_id = ? AND event_type = 'QR_CHECK_IN_FULFILLED'",
                Integer.class,
                scenario.reservationId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT consumed_at IS NOT NULL FROM reservation_check_in_qr_grants "
                        + "WHERE reservation_id = ?",
                Boolean.class,
                scenario.reservationId()
        )).isTrue();
        assertTerminalTask(
                scenario.reservationId(),
                "RESERVATION_VISIT_COMPLETED",
                "visit-completed"
        );
    }

    @Test
    void concurrentRotationSerializesAndLeavesOnlyHighestVersionCurrent() throws Exception {
        Scenario scenario = confirmedScenario();
        clock.set(START_AT.plusSeconds(60));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ReservationCheckInQrGrantResult> first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return grantFacade.issue(scenario.consumerId(), scenario.reservationId());
            });
            Future<ReservationCheckInQrGrantResult> second = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return grantFacade.issue(scenario.consumerId(), scenario.reservationId());
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ReservationCheckInQrGrantResult> results = List.of(
                    first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)
            );
            assertThat(results.stream().map(result -> result.data().tokenVersion()))
                    .containsExactlyInAnyOrder(1L, 2L);
            ReservationCheckInQrGrantResult current = results.stream()
                    .filter(result -> result.data().tokenVersion() == 2L)
                    .findFirst()
                    .orElseThrow();
            byte[] storedDigest = jdbcTemplate.queryForObject(
                    "SELECT token_digest FROM reservation_check_in_qr_grants "
                            + "WHERE reservation_id = ?",
                    byte[].class,
                    scenario.reservationId()
            );
            assertThat(storedDigest)
                    .containsExactly(tokenService.digest(current.data().qrToken()));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void exactFiveMinuteBoundaryCommitsNoShowAndRequiredReason() {
        Scenario scenario = confirmedScenario();
        clock.set(START_AT.plusSeconds(300));

        ReservationVisitCommandResult result = visitFacade.markNoShow(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(), key(2),
                new ReservationNoShowRequest(ReservationNoShowReason.UNCLEAR)
        );
        ReservationVisitCommandResult replay = visitFacade.markNoShow(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(), key(2),
                new ReservationNoShowRequest(ReservationNoShowReason.UNCLEAR)
        );

        assertThat(result.data().status()).isEqualTo("NO_SHOW");
        assertThat(replay).isEqualTo(result);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, no_show_at FROM reservations WHERE reservation_id = ?",
                scenario.reservationId()
        )).containsEntry("status", "NO_SHOW");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason FROM reservation_no_show_audits WHERE reservation_id = ?",
                String.class,
                scenario.reservationId()
        )).isEqualTo("UNCLEAR");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_no_show_audits WHERE reservation_id = ?",
                Integer.class,
                scenario.reservationId()
        )).isOne();
        assertTerminalTask(
                scenario.reservationId(),
                "RESERVATION_NO_SHOW",
                "no-show"
        );
    }

    @Test
    void v2UserNoShowCommitsOnePendingDispositionAndReplaysIt() {
        Scenario scenario = confirmedScenario();
        seedCompletedDepositProcess(scenario);
        clock.set(START_AT.plusSeconds(300));
        IdempotencyKey key = key(6);
        ReservationNoShowRequest request = new ReservationNoShowRequest(
                ReservationNoShowReason.USER_CAUSE_CANDIDATE
        );

        ReservationVisitCommandResult fresh = visitFacade.markNoShow(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(), key, request
        );
        ReservationVisitCommandResult replay = visitFacade.markNoShow(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(), key, request
        );

        assertThat(fresh.data().status()).isEqualTo("NO_SHOW");
        assertThat(fresh.data().depositDisposition().status()).isEqualTo("PENDING");
        assertThat(fresh.data().depositDisposition().responsibilityCode())
                .isEqualTo("CONSUMER");
        assertThat(fresh.data().depositDisposition().targetRefundRateBasisPoints()).isZero();
        assertThat(replay).isEqualTo(fresh);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT source_event_type, responsibility_code,
                       target_refund_rate_basis_points, status
                  FROM reservation_deposit_disposition_obligations
                 WHERE reservation_id = ?
                """, scenario.reservationId()))
                .containsEntry("source_event_type", "RESERVATION_NO_SHOW")
                .containsEntry("responsibility_code", "CONSUMER")
                .containsEntry("target_refund_rate_basis_points", 0)
                .containsEntry("status", "PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_deposit_disposition_obligations "
                        + "WHERE reservation_id = ?",
                Integer.class,
                scenario.reservationId()
        )).isOne();
    }

    @Test
    void lateQrAuditFailureRollsBackReservationGrantAuditAndIdempotency() {
        Scenario scenario = confirmedScenario();
        clock.set(START_AT.plusSeconds(60));
        ReservationCheckInQrGrantResult issued = grantFacade.issue(
                scenario.consumerId(), scenario.reservationId()
        );
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_qr_check_in_audit_failure
                BEFORE INSERT ON reservation_check_in_audits FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'qr audit failure'
                """);

        assertThatThrownBy(() -> visitFacade.checkIn(
                scenario.operatorId(), scenario.storeId(), key(3),
                new ReservationCheckInRequest(issued.data().qrToken())
        )).isInstanceOf(RuntimeException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE reservation_id = ?",
                String.class,
                scenario.reservationId()
        )).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT consumed_at IS NULL FROM reservation_check_in_qr_grants "
                        + "WHERE reservation_id = ?",
                Boolean.class,
                scenario.reservationId()
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_fulfillment_audits WHERE reservation_id = ?",
                Integer.class,
                scenario.reservationId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_commands WHERE idempotency_key = ?",
                Integer.class,
                key(3).value()
        )).isZero();
        assertThat(notificationTaskCount(scenario.reservationId(), "visit-completed"))
                .isZero();
    }

    @Test
    void lateNotificationFailureRollsBackQrReservationGrantAuditsAndIdempotency() {
        Scenario scenario = confirmedScenario();
        clock.set(START_AT.plusSeconds(60));
        ReservationCheckInQrGrantResult issued = grantFacade.issue(
                scenario.consumerId(), scenario.reservationId()
        );
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_visit_notification_failure
                BEFORE INSERT ON notification_tasks FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'visit notification failure'
                """);

        assertThatThrownBy(() -> visitFacade.checkIn(
                scenario.operatorId(), scenario.storeId(), key(6),
                new ReservationCheckInRequest(issued.data().qrToken())
        )).isInstanceOf(RuntimeException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE reservation_id = ?",
                String.class,
                scenario.reservationId()
        )).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT consumed_at IS NULL FROM reservation_check_in_qr_grants "
                        + "WHERE reservation_id = ?",
                Boolean.class,
                scenario.reservationId()
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_fulfillment_audits WHERE reservation_id = ?",
                Integer.class,
                scenario.reservationId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_check_in_audits "
                        + "WHERE reservation_id = ? AND event_type = 'QR_CHECK_IN_FULFILLED'",
                Integer.class,
                scenario.reservationId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_commands WHERE idempotency_key = ?",
                Integer.class,
                key(6).value()
        )).isZero();
        assertThat(notificationTaskCount(scenario.reservationId(), "visit-completed"))
                .isZero();
    }

    @Test
    void lateNotificationFailureRollsBackNoShowAuditIdempotencyAndTask() {
        Scenario scenario = confirmedScenario();
        insertConfirmedMenuHold(scenario);
        clock.set(START_AT.plusSeconds(300));
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_visit_notification_failure
                BEFORE INSERT ON notification_tasks FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'visit notification failure'
                """);

        assertThatThrownBy(() -> visitFacade.markNoShow(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(), key(9),
                new ReservationNoShowRequest(ReservationNoShowReason.UNCLEAR)
        )).isInstanceOf(RuntimeException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE reservation_id = ?",
                String.class,
                scenario.reservationId()
        )).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_no_show_audits WHERE reservation_id = ?",
                Integer.class,
                scenario.reservationId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_commands WHERE idempotency_key = ?",
                Integer.class,
                key(9).value()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM menu_holds WHERE reservation_id = ?",
                String.class,
                scenario.reservationId()
        )).isEqualTo("CONFIRMED");
        assertThat(notificationTaskCount(scenario.reservationId(), "no-show")).isZero();

        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_visit_notification_failure");
        ReservationVisitCommandResult retry = visitFacade.markNoShow(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(), key(9),
                new ReservationNoShowRequest(ReservationNoShowReason.UNCLEAR)
        );

        assertThat(retry.data().status()).isEqualTo("NO_SHOW");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM menu_holds WHERE reservation_id = ?",
                String.class,
                scenario.reservationId()
        )).isEqualTo("FORFEITED");
        assertTerminalTask(
                scenario.reservationId(), "RESERVATION_NO_SHOW", "no-show");
    }

    @Test
    void noShowAndDirectFulfillmentRaceLeavesOneMatchingTerminalTask() throws Exception {
        Scenario scenario = confirmedScenario();
        clock.set(START_AT.plusSeconds(300));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> noShow = executor.submit(() -> attempt(ready, start, () ->
                    visitFacade.markNoShow(
                            scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                            key(7), new ReservationNoShowRequest(
                                    ReservationNoShowReason.UNCLEAR)
                    )));
            Future<Boolean> direct = executor.submit(() -> attempt(ready, start, () ->
                    fulfillmentFacade.fulfill(
                            scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                            key(8), new ReservationFulfillmentRequest()
                    )));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    noShow.get(15, TimeUnit.SECONDS), direct.get(15, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        String terminalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE reservation_id = ?",
                String.class,
                scenario.reservationId()
        );
        String winningEvent = "NO_SHOW".equals(terminalStatus) ? "no-show" : "visit-completed";
        String winningPurpose = "NO_SHOW".equals(terminalStatus)
                ? "RESERVATION_NO_SHOW"
                : "RESERVATION_VISIT_COMPLETED";
        assertTerminalTask(scenario.reservationId(), winningPurpose, winningEvent);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_tasks WHERE resource_id = ?",
                Integer.class,
                scenario.reservationId()
        )).isOne();
    }

    @Test
    void v2QrAndDirectFulfillmentRaceLeavesOneMatchingDisposition() throws Exception {
        Scenario scenario = confirmedScenario();
        seedCompletedDepositProcess(scenario);
        clock.set(START_AT.plusSeconds(60));
        ReservationCheckInQrGrantResult issued = grantFacade.issue(
                scenario.consumerId(), scenario.reservationId()
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> qr = executor.submit(() -> attempt(ready, start, () ->
                    visitFacade.checkIn(
                            scenario.operatorId(), scenario.storeId(), key(4),
                            new ReservationCheckInRequest(issued.data().qrToken())
                    )));
            Future<Boolean> direct = executor.submit(() -> attempt(ready, start, () ->
                    fulfillmentFacade.fulfill(
                            scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                            key(5), new ReservationFulfillmentRequest()
                    )));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    qr.get(15, TimeUnit.SECONDS), direct.get(15, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE reservation_id = ?",
                String.class,
                scenario.reservationId()
        )).isEqualTo("FULFILLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_fulfillment_audits WHERE reservation_id = ?",
                Integer.class,
                scenario.reservationId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT source_event_type, responsibility_code,
                       target_refund_rate_basis_points, status
                  FROM reservation_deposit_disposition_obligations
                 WHERE reservation_id = ?
                """, scenario.reservationId()))
                .containsEntry("source_event_type", "RESERVATION_FULFILLED")
                .containsEntry("responsibility_code", "CONSUMER")
                .containsEntry("target_refund_rate_basis_points", 10_000)
                .containsEntry("status", "PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_deposit_disposition_obligations "
                        + "WHERE reservation_id = ?",
                Integer.class,
                scenario.reservationId()
        )).isOne();
        assertTerminalTask(
                scenario.reservationId(),
                "RESERVATION_VISIT_COMPLETED",
                "visit-completed"
        );
    }

    private void assertTerminalTask(long reservationId, String purpose, String eventName) {
        assertThat(jdbcTemplate.queryForMap(
                "SELECT purpose, resource_version, source_state FROM notification_tasks "
                        + "WHERE source_event_id = ?",
                "reservation:" + reservationId + ":" + eventName
        )).containsEntry("purpose", purpose)
                .containsEntry("resource_version", 2L)
                .containsEntry("source_state", purpose.equals("RESERVATION_NO_SHOW")
                        ? "NO_SHOW" : "FULFILLED");
        assertThat(notificationTaskCount(reservationId, eventName)).isOne();
    }

    private int notificationTaskCount(long reservationId, String eventName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_tasks WHERE source_event_id = ?",
                Integer.class,
                "reservation:" + reservationId + ":" + eventName
        );
    }

    private Scenario confirmedScenario() {
        return transactions.execute(status -> {
            int sequence = SEQUENCE.incrementAndGet();
            StoreOperatorAccount operator = operatorRepository.saveAndFlush(
                    StoreOperatorAccount.create(
                            "visit-owner-" + sequence + "@example.com",
                            "hashed-password",
                            "owner"
                    ));
            Store store = storeRepository.saveAndFlush(Store.create(
                    operator.getId(),
                    Long.toString(8_000_000_000L + sequence),
                    "Visit Store " + sequence,
                    "",
                    Region.SEOUL,
                    "fixture-address",
                    "CAFE_BAKERY",
                    Set.of(),
                    true,
                    true,
                    false,
                    "Asia/Seoul",
                    LocalDateTime.of(2026, 8, 1, 9, 0),
                    "STORE_ONBOARDING_REQUIRED_TERMS_V1"
            ));
            ConsumerAccount consumer = consumerRepository.saveAndFlush(
                    ConsumerAccount.createWithContact(
                            "visit-consumer-" + sequence + "@example.com",
                            "hashed-password",
                            "consumer",
                            String.format(Locale.ROOT, "010%08d", sequence),
                            "opaque-visit-contact-" + sequence
                    ));
            ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                    store.getId(), 1L, 60, 60, 0
            );
            policy.activate(Instant.parse("2026-08-01T00:00:00Z"), "visit fixture");
            ReservationTimeSnapshot time = ReservationTimeSnapshot.calculate(
                    policy,
                    LocalDateTime.of(2026, 8, 16, 10, 0),
                    ZoneId.of("Asia/Seoul"),
                    null
            );
            Reservation reservation = reservationRepository.saveAndFlush(Reservation.confirm(
                    consumer.getId(),
                    store.getId(),
                    store.getName(),
                    time,
                    PartyComposition.of(2, 0, 0),
                    ReservationContactSnapshot.contactable("opaque-target-" + sequence),
                    1L,
                    new ReservationCancellationPolicyVersion(1L),
                    Instant.parse("2026-08-10T00:00:00Z")
            ));
            return new Scenario(
                    operator.getId(), store.getId(), consumer.getId(), reservation.getId()
            );
        });
    }

    private void seedCompletedDepositProcess(Scenario scenario) {
        long holdId = 1_000_000L + scenario.reservationId();
        String paymentId = Long.toString(
                8_000_000_000_000_000_000L + scenario.reservationId());
        jdbcTemplate.update(
                "UPDATE reservations SET cancellation_policy_version = 2 "
                        + "WHERE reservation_id = ?",
                scenario.reservationId());
        jdbcTemplate.update("""
                INSERT INTO reservation_holds (
                    reservation_hold_id, consumer_account_id, store_id,
                    store_name_snapshot, service_date, start_at, service_end_at,
                    occupancy_end_at, time_zone_id_snapshot, start_offset_seconds,
                    service_end_offset_seconds, occupancy_end_offset_seconds,
                    slot_interval_minutes, service_duration_minutes,
                    turnover_duration_minutes, reservation_time_policy_store_id,
                    reservation_policy_version, adult_count, child_count, infant_count,
                    notification_target_reference, contact_available_at_confirmation,
                    capacity_policy_version, cancellation_policy_version, status,
                    status_version, creation_command_id, created_at, expires_at
                )
                SELECT ?, consumer_account_id, store_id, store_name_snapshot,
                       service_date, start_at, service_end_at, occupancy_end_at,
                       time_zone_id_snapshot, start_offset_seconds,
                       service_end_offset_seconds, occupancy_end_offset_seconds,
                       slot_interval_minutes, service_duration_minutes,
                       turnover_duration_minutes, reservation_time_policy_store_id,
                       reservation_policy_version, adult_count, child_count, infant_count,
                       notification_target_reference, contact_available_at_confirmation,
                       capacity_policy_version, 2, 'CONFIRMED', 0,
                       CONCAT('deposit-visit-it:', reservation_id),
                       created_at, DATE_ADD(created_at, INTERVAL 10 MINUTE)
                  FROM reservations
                 WHERE reservation_id = ?
                """, holdId, scenario.reservationId());
        jdbcTemplate.update("""
                INSERT INTO reservation_deposit_processes (
                    reservation_hold_id, consumer_account_id, status, expires_at,
                    payment_id, portone_payment_id, payment_order_name,
                    payment_amount_minor, payment_currency, payment_source_expires_at,
                    payment_preparation_status, store_deposit_policy_version,
                    deposit_rate_percent, deposit_algorithm_version, deposit_party_size,
                    deposit_amount_minor, deposit_currency, representative_menu_version,
                    representative_menu_price_total, representative_menu_count,
                    abandonment_requested, resources_protected, resources_protected_at,
                    final_reservation_id, requested_at, completed_at
                ) VALUES (
                    ?, ?, 'COMPLETED', '2026-08-02 00:10:00.000000',
                    ?, CONCAT('portone-', ?), 'V2 visit deposit',
                    10001, 'KRW', '2026-08-02 00:10:00.000000',
                    'READY', 1, 20, 1, 2, 10001, 'KRW', 1, 50005, 1,
                    FALSE, TRUE, '2026-08-02 00:01:00.000000',
                    ?, '2026-08-02 00:00:00.000000',
                    '2026-08-02 00:02:00.000000'
                )
                """,
                holdId,
                scenario.consumerId(),
                paymentId,
                paymentId,
                scenario.reservationId());
    }

    private void insertConfirmedMenuHold(Scenario scenario) {
        jdbcTemplate.update("""
                INSERT INTO menu_holds (
                    reservation_id, store_id, consumer_account_id,
                    service_date, start_time, end_date, end_time,
                    acquire_operation_id, status, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, '2026-08-16', '10:00:00', '2026-08-16', '11:00:00',
                    ?, 'CONFIRMED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
                )
                """,
                scenario.reservationId(),
                scenario.storeId(),
                scenario.consumerId(),
                 "reservation-visit-it-hold:" + scenario.reservationId()
         );
    }

    private static boolean attempt(
            CountDownLatch ready,
            CountDownLatch start,
            ThrowingCommand command
    ) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("race start timed out");
            }
            command.run();
            return true;
        } catch (ServiceException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("race interrupted", exception);
        }
    }

    private static IdempotencyKey key(int suffix) {
        return IdempotencyKey.parse(String.format(
                Locale.ROOT, "550e8400-e29b-41d4-a716-%012d", suffix
        ));
    }

    @FunctionalInterface
    private interface ThrowingCommand {
        void run();
    }

    private record Scenario(long operatorId, long storeId, long consumerId, long reservationId) {
    }

    @TestConfiguration
    static class TestClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START_AT);
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void set(Instant instant) {
            current.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
