package com.miriyum.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.dto.source.NotificationSourceReadResult;
import com.miriyum.domain.notification.dto.source.NotificationActionAvailability;
import com.miriyum.domain.notification.dto.source.NotificationActionType;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;
import com.miriyum.domain.notification.port.PickupNotificationSource;
import com.miriyum.domain.notification.port.WaitingNotificationSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@Import(NotificationTaskWorkerIntegrationTest.FakeSourceConfig.class)
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.notification.worker.enabled=true",
            "miriyum.notification.worker.policy-version=notification-worker-test-v1",
            "miriyum.notification.worker.worker-id=worker-test",
            "miriyum.notification.worker.batch-size=10",
            "miriyum.notification.worker.lease-duration-ms=30000",
            "miriyum.notification.worker.max-attempts=3",
            "miriyum.notification.worker.initial-retry-delay-ms=5000",
            "miriyum.notification.worker.max-retry-delay-ms=60000",
            "miriyum.notification.worker.initial-delay-ms=3600000",
            "miriyum.store.schedule.activation-delay-ms=3600000",
            "miriyum.menu.schedule.initial-delay-ms=3600000",
            "miriyum.reservation.time-policy.activation-delay-ms=3600000"
        }
)
class NotificationTaskWorkerIntegrationTest {

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired NotificationTaskRecorder recorder;
    @Autowired NotificationTaskWorker worker;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TestPickupSource pickupSource;
    @Autowired TestWaitingSource waitingSource;
    @Autowired WaitingNotificationReevaluationService reevaluationService;
    @Autowired BlockingTitleRenderer titleRenderer;
    @Autowired ApplicationContext applicationContext;

    @BeforeEach
    void resetDatabase() {
        pickupSource.reset(found(3L, 7L, "CONFIRMED"));
        waitingSource.reset(waitingFound());
        titleRenderer.reset();
        jdbcTemplate.execute("DELETE FROM notification_task_transition_audits");
        jdbcTemplate.execute("DELETE FROM notification_channel_attempts");
        jdbcTemplate.execute("DELETE FROM notification_tasks");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");
        jdbcTemplate.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name, status, created_at, updated_at
                ) VALUES (11, 'worker-owner@example.com', 'hash', '알림소유자',
                          'ACTIVE', NOW(6), NOW(6))
                """);
    }

    @Test
    void foundContextDeliversInAppAndAuditsTheTransition() {
        record("found-1");

        assertThat(worker.deliverDueBatch()).isOne();

        assertThat(taskString("status")).isEqualTo("DELIVERED");
        assertThat(taskString("title")).isEqualTo("미리윰 강남 픽업 예약이 확정되었습니다.");
        assertThat(taskTimestampCount("delivered_at")).isOne();
        assertThat(channelString("status")).isEqualTo("DELIVERED");
        assertThat(channelInt("attempt_count")).isOne();
        assertThat(auditReasons()).containsExactly(
                "SOURCE_EVENT_RECORDED",
                "IN_APP_DELIVERED@notification-worker-test-v1"
        );
    }

    @Test
    void supersededAndNotEligibleResultsCancelWithoutGuessingAnotherRecipient() {
        record("superseded-1");
        pickupSource.reset(result(NotificationSourceReadResult.SUPERSEDED));

        assertThat(worker.deliverDueBatch()).isOne();
        assertThat(taskString("status")).isEqualTo("CANCELLED");
        assertThat(channelString("failure_code")).isEqualTo("SOURCE_SUPERSEDED");

        resetDatabase();
        record("not-eligible-1");
        pickupSource.reset(result(NotificationSourceReadResult.NOT_ELIGIBLE));

        assertThat(worker.deliverDueBatch()).isOne();
        assertThat(taskString("status")).isEqualTo("CANCELLED");
        assertThat(channelString("failure_code")).isEqualTo("RECIPIENT_NOT_ELIGIBLE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT recipient_account_id FROM notification_tasks", Long.class)).isEqualTo(11L);
    }

    @Test
    void foundContextWithChangedVersionOrRecipientRelationFailsClosed() {
        record("changed-version-1");
        pickupSource.reset(found(4L, 7L, "CONFIRMED"));

        assertThat(worker.deliverDueBatch()).isOne();
        assertThat(channelString("failure_code")).isEqualTo("SOURCE_SUPERSEDED");

        resetDatabase();
        record("changed-recipient-1");
        pickupSource.reset(found(3L, 8L, "CONFIRMED"));

        assertThat(worker.deliverDueBatch()).isOne();
        assertThat(channelString("failure_code")).isEqualTo("RECIPIENT_NOT_ELIGIBLE");
    }

    @Test
    void temporaryUnavailabilityUsesBoundedDelayedRetryThenFails() {
        record("temporary-1");
        pickupSource.reset(result(NotificationSourceReadResult.TEMPORARILY_UNAVAILABLE));

        int firstResult = worker.deliverDueBatch();

        assertThat(firstResult)
                .as("taskStatus=%s, channelStatus=%s, failureCode=%s",
                        taskString("status"),
                        channelString("status"),
                        channelString("failure_code"))
                .isZero();
        assertThat(taskString("status")).isEqualTo("PENDING");
        assertThat(channelString("status")).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT next_attempt_at > NOW(6) FROM notification_tasks
                """, Boolean.class)).isTrue();

        makeRetryDue();
        assertThat(worker.deliverDueBatch()).isZero();
        makeRetryDue();
        assertThat(worker.deliverDueBatch()).isOne();

        assertThat(taskString("status")).isEqualTo("FAILED");
        assertThat(channelString("status")).isEqualTo("FAILED");
        assertThat(channelInt("attempt_count")).isEqualTo(3);
        assertThat(auditReasons()).containsExactly(
                "SOURCE_EVENT_RECORDED",
                "SOURCE_TEMPORARILY_UNAVAILABLE@notification-worker-test-v1",
                "SOURCE_TEMPORARILY_UNAVAILABLE@notification-worker-test-v1",
                "SOURCE_RETRY_EXHAUSTED@notification-worker-test-v1"
        );
    }

    @Test
    void waitingReservationConversionHoldUsesFiniteDelayWithoutAttemptBudget() {
        recordWaiting("waiting-hold-1");
        waitingSource.reset(waitingConverting());

        assertThat(worker.deliverDueBatch()).isZero();

        assertThat(taskString("status")).isEqualTo("PENDING");
        assertThat(taskInt("attempt_count")).isZero();
        assertThat(channelInt("attempt_count")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT next_attempt_at > NOW(6) FROM notification_tasks",
                Boolean.class)).isTrue();
        assertThat(auditReasons()).containsExactly(
                "SOURCE_EVENT_RECORDED",
                "WAITING_RESERVATION_CONVERTING@notification-worker-test-v1"
        );
    }

    @Test
    void transactionalWaitingSourceFailureRollsBackBeforeRetryIsRecorded() {
        recordWaiting("waiting-transactional-source-failure-1");
        waitingSource.failWithRollbackOnly();

        assertThatCode(worker::deliverDueBatch).doesNotThrowAnyException();

        assertThat(taskString("status")).isEqualTo("PENDING");
        assertThat(taskString("last_error_code"))
                .isEqualTo("SOURCE_TEMPORARILY_UNAVAILABLE");
        assertThat(taskInt("attempt_count")).isOne();
        assertThat(channelString("status")).isEqualTo("PENDING");
        assertThat(channelInt("attempt_count")).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lease_token IS NULL AND lease_until IS NULL
                  FROM notification_tasks
                """, Boolean.class)).isTrue();
        assertThat(auditReasons()).containsExactly(
                "SOURCE_EVENT_RECORDED",
                "SOURCE_TEMPORARILY_UNAVAILABLE@notification-worker-test-v1"
        );
    }

    @Test
    void holdThenWaitingStatusReevaluationWakesAndDeliversTheOriginalEntryEvent() {
        recordWaiting("waiting-hold-first-1");
        waitingSource.reset(waitingConverting());
        assertThat(worker.deliverDueBatch()).isZero();

        waitingSource.set(waitingFound());
        transactions.executeWithoutResult(ignored ->
                reevaluationService.reevaluate(31L, 81L, 4L));

        assertThat(worker.deliverDueBatch()).isOne();
        assertThat(taskString("status")).isEqualTo("DELIVERED");
        assertThat(taskInt("attempt_count")).isOne();
        assertThat(channelInt("attempt_count")).isOne();
        assertThat(auditReasons()).containsExactly(
                "SOURCE_EVENT_RECORDED",
                "WAITING_RESERVATION_CONVERTING@notification-worker-test-v1",
                "WAITING_REEVALUATION:81:4",
                "IN_APP_DELIVERED@notification-worker-test-v1"
        );
    }

    @Test
    void reevaluationBeforeStaleHoldInvalidatesClaimVersionAndRereadsLatestState()
            throws Exception {
        recordWaiting("waiting-event-first-1");
        waitingSource.blockFirstRead(waitingConverting());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var delivery = executor.submit(worker::deliverDueBatch);
            assertThat(waitingSource.awaitFirstRead()).isTrue();

            transactions.executeWithoutResult(ignored ->
                    reevaluationService.reevaluate(31L, 82L, 5L));
            waitingSource.set(waitingFound());
            waitingSource.releaseFirstRead();

            assertThat(delivery.get(10, TimeUnit.SECONDS)).isOne();
        } finally {
            waitingSource.releaseFirstRead();
        }

        assertThat(taskString("status")).isEqualTo("DELIVERED");
        assertThat(taskInt("attempt_count")).isOne();
        assertThat(channelInt("attempt_count")).isOne();
        assertThat(auditReasons()).containsExactly(
                "SOURCE_EVENT_RECORDED",
                "WAITING_REEVALUATION:82:5",
                "IN_APP_DELIVERED@notification-worker-test-v1"
        );
    }

    @Test
    void malformedFoundContextFailsWithoutRetryingAsTemporaryUnavailability() {
        record("invalid-context-1");
        pickupSource.reset(new NotificationSourceContextV1(
                NotificationSourceReadResult.FOUND,
                3L,
                7L,
                "CONFIRMED",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        assertThat(worker.deliverDueBatch()).isOne();

        assertThat(taskString("status")).isEqualTo("FAILED");
        assertThat(channelString("failure_code")).isEqualTo("SOURCE_CONTEXT_INVALID");
        assertThat(channelInt("attempt_count")).isOne();
        assertThat(auditReasons()).containsExactly(
                "SOURCE_EVENT_RECORDED",
                "SOURCE_CONTEXT_INVALID@notification-worker-test-v1"
        );
    }

    @Test
    void temporaryUnavailabilityNeverSchedulesPastImmutableTaskExpiry() {
        record("expiring-temporary-1", OffsetDateTime.now().plusSeconds(2).withNano(0));
        pickupSource.reset(result(NotificationSourceReadResult.TEMPORARILY_UNAVAILABLE));

        assertThat(worker.deliverDueBatch()).isOne();

        assertThat(taskString("status")).isEqualTo("CANCELLED");
        assertThat(channelString("failure_code")).isEqualTo("TASK_EXPIRED");
        assertThat(auditReasons()).containsExactly(
                "SOURCE_EVENT_RECORDED",
                "TASK_EXPIRED@notification-worker-test-v1"
        );
    }

    @Test
    void deliveryCrossingImmutableTaskExpiryCancelsInsteadOfPublishingHistory() throws Exception {
        record("task-expiry-race-1");
        titleRenderer.blockNextRender();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var delivery = executor.submit(worker::deliverDueBatch);
            assertThat(titleRenderer.awaitRender()).isTrue();
            jdbcTemplate.update("""
                    UPDATE notification_tasks
                       SET expires_at = TIMESTAMPADD(SECOND, 1, NOW(6))
                    """);
            BigDecimal expiryEpoch = jdbcTemplate.queryForObject(
                    "SELECT UNIX_TIMESTAMP(expires_at) FROM notification_tasks",
                    BigDecimal.class);
            awaitDatabaseTimeAtOrAfter(expiryEpoch);
            titleRenderer.releaseRender();

            assertThat(delivery.get(10, TimeUnit.SECONDS)).isOne();
        } finally {
            titleRenderer.releaseRender();
        }

        assertThat(taskString("status")).isEqualTo("CANCELLED");
        assertThat(taskTimestampCount("delivered_at")).isZero();
        assertThat(channelString("failure_code")).isEqualTo("TASK_EXPIRED");
        assertThat(auditReasons()).containsExactly(
                "SOURCE_EVENT_RECORDED",
                "TASK_EXPIRED@notification-worker-test-v1"
        );
    }

    @Test
    void deliveryCrossingSourceExpiryCancelsAsSuperseded() throws Exception {
        OffsetDateTime sourceExpiresAt = OffsetDateTime.now().plusSeconds(3).withNano(0);
        record("source-expiry-race-1");
        pickupSource.reset(found(3L, 7L, "CONFIRMED", sourceExpiresAt));
        titleRenderer.blockNextRender();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var delivery = executor.submit(worker::deliverDueBatch);
            assertThat(titleRenderer.awaitRender()).isTrue();
            awaitDatabaseTimeAtOrAfter(sourceExpiresAt);
            titleRenderer.releaseRender();

            assertThat(delivery.get(10, TimeUnit.SECONDS)).isOne();
        } finally {
            titleRenderer.releaseRender();
        }

        assertThat(taskString("status")).isEqualTo("CANCELLED");
        assertThat(taskTimestampCount("delivered_at")).isZero();
        assertThat(channelString("failure_code")).isEqualTo("SOURCE_SUPERSEDED");
        assertThat(auditReasons()).containsExactly(
                "SOURCE_EVENT_RECORDED",
                "SOURCE_SUPERSEDED@notification-worker-test-v1"
        );
    }

    @Test
    void expiredLeaseIsRecoveredAndTheStaleWorkerCannotOverwriteDelivery() throws Exception {
        record("lease-recovery-1");
        pickupSource.blockFirstRead(found(3L, 7L, "CONFIRMED"));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var staleWorker = executor.submit(worker::deliverDueBatch);
            assertThat(pickupSource.awaitFirstRead()).isTrue();
            jdbcTemplate.update("""
                    UPDATE notification_tasks
                       SET lease_until = DATE_SUB(NOW(6), INTERVAL 1 SECOND)
                    """);

            var recoveringWorker = executor.submit(worker::deliverDueBatch);
            assertThat(recoveringWorker.get(10, TimeUnit.SECONDS)).isOne();
            pickupSource.releaseFirstRead();
            assertThat(staleWorker.get(10, TimeUnit.SECONDS)).isZero();
        }

        assertThat(taskString("status")).isEqualTo("DELIVERED");
        assertThat(channelInt("attempt_count")).isEqualTo(2);
        assertThat(auditReasons()).containsExactly(
                "SOURCE_EVENT_RECORDED",
                "LEASE_RECOVERED@notification-worker-test-v1",
                "IN_APP_DELIVERED@notification-worker-test-v1"
        );
    }

    private void record(String sourceEventId) {
        record(sourceEventId, null);
    }

    private void record(String sourceEventId, OffsetDateTime expiresAt) {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-08-12T01:02:03Z");
        transactions.executeWithoutResult(ignored -> recorder.record(new NotificationSourceEventV1(
                sourceEventId,
                NotificationSourceDomain.PICKUP,
                NotificationPurpose.PICKUP_RESERVATION_CONFIRMED,
                "11",
                7L,
                NotificationResourceType.PICKUP_RESERVATION,
                "21",
                3L,
                "CONFIRMED",
                occurredAt,
                occurredAt,
                expiresAt,
                null,
                "correlation-" + sourceEventId
        )));
    }

    private void recordWaiting(String sourceEventId) {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-08-12T01:02:03Z");
        transactions.executeWithoutResult(ignored -> recorder.record(new NotificationSourceEventV1(
                sourceEventId,
                NotificationSourceDomain.WAITING,
                NotificationPurpose.WAITING_ENTRY_IMMINENT,
                "11",
                1L,
                NotificationResourceType.WAITING_TEAM,
                "31",
                1L,
                "WAITING",
                occurredAt,
                occurredAt,
                null,
                null,
                "correlation-" + sourceEventId
        )));
    }

    private void makeRetryDue() {
        jdbcTemplate.update("""
                UPDATE notification_tasks
                   SET next_attempt_at = DATE_SUB(NOW(6), INTERVAL 1 SECOND)
                 WHERE status = 'PENDING'
                """);
    }

    @Test
    void blockingNotificationSourceDoesNotDelayApplicationScheduledTasks() throws Exception {
        record("scheduler-isolation-1");
        pickupSource.blockFirstRead(found(3L, 7L, "CONFIRMED"));
        TaskScheduler notificationScheduler = applicationContext.getBean(
                "notificationTaskScheduler", TaskScheduler.class);
        TaskScheduler applicationScheduler = applicationContext.getBean(
                "taskScheduler", TaskScheduler.class);
        CountDownLatch applicationTaskRan = new CountDownLatch(1);

        var notificationRun = notificationScheduler.schedule(
                worker::deliverDueBatch, Instant.now());
        try {
            assertThat(pickupSource.awaitFirstRead()).isTrue();
            applicationScheduler.schedule(applicationTaskRan::countDown, Instant.now());

            assertThat(applicationTaskRan.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            pickupSource.releaseFirstRead();
        }
        notificationRun.get(10, TimeUnit.SECONDS);
        assertThat(taskString("status")).isEqualTo("DELIVERED");
    }

    private void awaitDatabaseTimeAtOrAfter(OffsetDateTime threshold)
            throws InterruptedException {
        BigDecimal epochSeconds = BigDecimal.valueOf(threshold.toEpochSecond())
                .add(BigDecimal.valueOf(threshold.getNano(), 9));
        awaitDatabaseTimeAtOrAfter(epochSeconds);
    }

    private void awaitDatabaseTimeAtOrAfter(BigDecimal epochSeconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT UNIX_TIMESTAMP(NOW(6)) >= ?", Boolean.class, epochSeconds))) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("database clock did not reach expiry threshold");
            }
            Thread.sleep(10);
        }
    }

    private String taskString(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM notification_tasks", String.class);
    }

    private int taskTimestampCount(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_tasks WHERE " + column + " IS NOT NULL",
                Integer.class);
    }

    private int taskInt(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM notification_tasks", Integer.class);
    }

    private String channelString(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM notification_channel_attempts", String.class);
    }

    private int channelInt(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM notification_channel_attempts", Integer.class);
    }

    private java.util.List<String> auditReasons() {
        return jdbcTemplate.queryForList("""
                SELECT reason
                  FROM notification_task_transition_audits
                 ORDER BY notification_task_transition_audit_id
                """, String.class);
    }

    private static NotificationSourceContextV1 found(
            long resourceVersion,
            long recipientRelationVersion,
            String sourceState
    ) {
        return found(resourceVersion, recipientRelationVersion, sourceState, null);
    }

    private static NotificationSourceContextV1 found(
            long resourceVersion,
            long recipientRelationVersion,
            String sourceState,
            OffsetDateTime expiresAt
    ) {
        return new NotificationSourceContextV1(
                NotificationSourceReadResult.FOUND,
                resourceVersion,
                recipientRelationVersion,
                sourceState,
                "미리윰 강남",
                null,
                OffsetDateTime.parse("2026-08-12T01:02:03Z"),
                expiresAt,
                NotificationActionType.PICKUP_RESERVATION_DETAIL,
                NotificationResourceType.PICKUP_RESERVATION,
                "21",
                NotificationActionAvailability.AVAILABLE
        );
    }

    private static NotificationSourceContextV1 result(NotificationSourceReadResult result) {
        return new NotificationSourceContextV1(
                result, 0L, 0L, null, null, null, null, null,
                null, null, null, null
        );
    }

    private static NotificationSourceContextV1 waitingFound() {
        return new NotificationSourceContextV1(
                NotificationSourceReadResult.FOUND,
                1L,
                1L,
                "WAITING",
                "미리윰 웨이팅",
                null,
                OffsetDateTime.parse("2026-08-12T01:02:03Z"),
                null,
                null,
                null,
                null,
                null
        );
    }

    private static NotificationSourceContextV1 waitingConverting() {
        return new NotificationSourceContextV1(
                NotificationSourceReadResult.TEMPORARILY_UNAVAILABLE,
                1L,
                1L,
                "RESERVATION_CONVERTING",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    @TestConfiguration
    static class FakeSourceConfig {

        @Bean
        @Primary
        TestPickupSource testPickupSource() {
            return new TestPickupSource();
        }

        @Bean
        @Primary
        BlockingTitleRenderer blockingTitleRenderer() {
            return new BlockingTitleRenderer();
        }

        @Bean
        @Primary
        TestWaitingSource testWaitingSource() {
            return new TestWaitingSource();
        }

    }

    static final class BlockingTitleRenderer extends NotificationTitleRenderer {

        private volatile CountDownLatch renderEntered;
        private volatile CountDownLatch releaseRender;

        void reset() {
            renderEntered = null;
            releaseRender = null;
        }

        void blockNextRender() {
            renderEntered = new CountDownLatch(1);
            releaseRender = new CountDownLatch(1);
        }

        boolean awaitRender() throws InterruptedException {
            return renderEntered.await(10, TimeUnit.SECONDS);
        }

        void releaseRender() {
            if (releaseRender != null) {
                releaseRender.countDown();
            }
        }

        @Override
        public String render(
                NotificationPurpose purpose,
                NotificationSourceContextV1 context
        ) {
            if (renderEntered != null) {
                renderEntered.countDown();
                try {
                    if (!releaseRender.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("title rendering was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("title rendering interrupted", exception);
                }
            }
            return super.render(purpose, context);
        }
    }

    static final class TestPickupSource implements PickupNotificationSource {

        private final AtomicReference<NotificationSourceContextV1> result = new AtomicReference<>();
        private final AtomicInteger reads = new AtomicInteger();
        private volatile CountDownLatch firstReadEntered;
        private volatile CountDownLatch releaseFirstRead;

        void reset(NotificationSourceContextV1 context) {
            result.set(context);
            reads.set(0);
            firstReadEntered = null;
            releaseFirstRead = null;
        }

        void blockFirstRead(NotificationSourceContextV1 context) {
            reset(context);
            firstReadEntered = new CountDownLatch(1);
            releaseFirstRead = new CountDownLatch(1);
        }

        boolean awaitFirstRead() throws InterruptedException {
            return firstReadEntered.await(10, TimeUnit.SECONDS);
        }

        void releaseFirstRead() {
            releaseFirstRead.countDown();
        }

        @Override
        public NotificationSourceContextV1 readContext(
                String resourceId,
                long expectedVersion,
                String recipientAccountId
        ) {
            if (reads.getAndIncrement() == 0 && firstReadEntered != null) {
                firstReadEntered.countDown();
                try {
                    if (!releaseFirstRead.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("first source read was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("source read interrupted", exception);
                }
            }
            return result.get();
        }
    }

    static class TestWaitingSource implements WaitingNotificationSource {

        private final AtomicReference<NotificationSourceContextV1> result = new AtomicReference<>();
        private final AtomicInteger reads = new AtomicInteger();
        private volatile CountDownLatch firstReadEntered;
        private volatile CountDownLatch releaseFirstRead;
        private volatile NotificationSourceContextV1 blockedFirstResult;
        private volatile boolean rollbackFailure;

        void reset(NotificationSourceContextV1 context) {
            result.set(context);
            reads.set(0);
            firstReadEntered = null;
            releaseFirstRead = null;
            blockedFirstResult = null;
            rollbackFailure = false;
        }

        void set(NotificationSourceContextV1 context) {
            result.set(context);
        }

        void failWithRollbackOnly() {
            rollbackFailure = true;
        }

        void blockFirstRead(NotificationSourceContextV1 context) {
            reset(context);
            blockedFirstResult = context;
            firstReadEntered = new CountDownLatch(1);
            releaseFirstRead = new CountDownLatch(1);
        }

        boolean awaitFirstRead() throws InterruptedException {
            return firstReadEntered.await(10, TimeUnit.SECONDS);
        }

        void releaseFirstRead() {
            if (releaseFirstRead != null) {
                releaseFirstRead.countDown();
            }
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public NotificationSourceContextV1 readContextForDelivery(
                NotificationPurpose purpose,
                String resourceId,
                long expectedVersion,
                String recipientAccountId
        ) {
            return readContext(purpose, resourceId, expectedVersion, recipientAccountId);
        }

        @Override
        public NotificationSourceContextV1 readContext(
                NotificationPurpose purpose,
                String resourceId,
                long expectedVersion,
                String recipientAccountId
        ) {
            if (rollbackFailure) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                throw new DataAccessResourceFailureException(
                        "test source failed inside the delivery transaction");
            }
            if (reads.getAndIncrement() == 0 && firstReadEntered != null) {
                firstReadEntered.countDown();
                try {
                    if (!releaseFirstRead.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("first source read was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("source read interrupted", exception);
                }
                return blockedFirstResult;
            }
            return result.get();
        }
    }
}
