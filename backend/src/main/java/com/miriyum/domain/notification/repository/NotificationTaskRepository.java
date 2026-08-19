package com.miriyum.domain.notification.repository;

import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;
import com.miriyum.domain.notification.entity.NotificationTaskStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationTaskRepository {

    private static final String INSERT = """
            INSERT INTO notification_tasks (
                source_domain, source_event_id, purpose,
                recipient_account_id, recipient_relation_version,
                resource_type, resource_id, resource_version, source_state,
                occurred_at, scheduled_at, expires_at, timing_policy_version,
                correlation_id, contract_version, payload_fingerprint,
                status, next_attempt_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public NotificationTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public StoredTask insertOrFind(NotificationSourceEventV1 event, String fingerprint) {
        boolean inserted;
        try {
            int affected = jdbcTemplate.update(
                    INSERT,
                    event.sourceDomain().name(),
                    event.sourceEventId(),
                    event.purpose().name(),
                    Long.parseLong(event.recipientAccountId()),
                    event.recipientRelationVersion(),
                    event.resourceType().name(),
                    Long.parseLong(event.resourceId()),
                    event.resourceVersion(),
                    event.sourceState(),
                    utcLocalDateTime(event.occurredAt()),
                    utcLocalDateTime(event.scheduledAt()),
                    event.expiresAt() == null ? null : utcLocalDateTime(event.expiresAt()),
                    event.timingPolicyVersion(),
                    event.correlationId(),
                    NotificationSourceEventV1.CONTRACT_VERSION,
                    fingerprint,
                    utcLocalDateTime(event.scheduledAt())
            );
            if (affected != 1) {
                throw new IllegalStateException("notification task was not recorded");
            }
            inserted = true;
        } catch (DuplicateKeyException duplicateLogicalIdentity) {
            inserted = false;
        }
        StoredTask stored = findByLogicalIdentityForShare(event);
        return new StoredTask(stored.notificationId(), stored.payloadFingerprint(), inserted);
    }

    public Optional<DueTask> findNextDueForUpdate(Instant now) {
        List<DueTask> rows = jdbcTemplate.query("""
                        SELECT notification_id, source_domain, purpose,
                               recipient_account_id, recipient_relation_version,
                               resource_type, resource_id, resource_version, source_state,
                               expires_at, correlation_id, attempt_count, version,
                               lease_token IS NOT NULL AS lease_recovery
                          FROM notification_tasks
                         WHERE status = 'PENDING'
                           AND scheduled_at <= ?
                           AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                           AND (lease_until IS NULL OR lease_until <= ?)
                         ORDER BY COALESCE(next_attempt_at, scheduled_at), notification_id
                         LIMIT 1
                         FOR UPDATE SKIP LOCKED
                        """,
                (resultSet, rowNumber) -> new DueTask(
                        resultSet.getLong("notification_id"),
                        NotificationSourceDomain.valueOf(resultSet.getString("source_domain")),
                        NotificationPurpose.valueOf(resultSet.getString("purpose")),
                        resultSet.getLong("recipient_account_id"),
                        resultSet.getLong("recipient_relation_version"),
                        NotificationResourceType.valueOf(resultSet.getString("resource_type")),
                        resultSet.getLong("resource_id"),
                        resultSet.getLong("resource_version"),
                        resultSet.getString("source_state"),
                        utcInstant(resultSet.getObject("expires_at", LocalDateTime.class)),
                        resultSet.getString("correlation_id"),
                        resultSet.getInt("attempt_count"),
                        resultSet.getLong("version"),
                        resultSet.getBoolean("lease_recovery")
                ),
                utcLocalDateTime(now),
                utcLocalDateTime(now),
                utcLocalDateTime(now)
        );
        return rows.stream().findFirst();
    }

    /**
     * 소비자에게 실제 IN_APP 전달이 완료된 작업만 고정 keyset 순서로 조회한다.
     */
    public List<HistoryTask> findDeliveredInAppHistory(
            long recipientAccountId,
            HistoryBoundary boundary,
            int limit
    ) {
        LocalDateTime occurredAt = boundary == null
                ? null
                : utcLocalDateTime(boundary.occurredAt());
        Long notificationId = boundary == null ? null : boundary.notificationId();
        return jdbcTemplate.query("""
                        SELECT task.notification_id, task.source_domain, task.purpose,
                               task.recipient_relation_version,
                               task.resource_type, task.resource_id, task.resource_version,
                               task.title, task.occurred_at, task.created_at, task.delivered_at
                          FROM notification_tasks task
                          JOIN notification_channel_attempts attempt
                            ON attempt.notification_id = task.notification_id
                           AND attempt.channel = 'IN_APP'
                           AND attempt.status = 'DELIVERED'
                         WHERE task.recipient_account_id = ?
                           AND task.status = 'DELIVERED'
                           AND task.delivered_at IS NOT NULL
                           AND task.title IS NOT NULL
                           AND (? IS NULL
                                OR task.occurred_at < ?
                                OR (task.occurred_at = ? AND task.notification_id < ?))
                         ORDER BY task.occurred_at DESC, task.notification_id DESC
                         LIMIT ?
                        """,
                (resultSet, rowNumber) -> new HistoryTask(
                        resultSet.getLong("notification_id"),
                        NotificationSourceDomain.valueOf(resultSet.getString("source_domain")),
                        NotificationPurpose.valueOf(resultSet.getString("purpose")),
                        resultSet.getLong("recipient_relation_version"),
                        NotificationResourceType.valueOf(resultSet.getString("resource_type")),
                        resultSet.getLong("resource_id"),
                        resultSet.getLong("resource_version"),
                        resultSet.getString("title"),
                        utcInstant(resultSet.getObject("occurred_at", LocalDateTime.class)),
                        resultSet.getTimestamp("created_at").toInstant(),
                        utcInstant(resultSet.getObject("delivered_at", LocalDateTime.class))
                ),
                recipientAccountId,
                occurredAt,
                occurredAt,
                occurredAt,
                notificationId,
                limit
        );
    }

    /** 소비자 공개 이력에 실제로 나타나는 IN_APP 전달 완료 작업의 최대 ID다. */
    public long findDeliveredInAppHighWatermark(long recipientAccountId) {
        Long watermark = jdbcTemplate.queryForObject("""
                        SELECT COALESCE(MAX(task.notification_id), 0)
                          FROM notification_tasks task
                          JOIN notification_channel_attempts attempt
                            ON attempt.notification_id = task.notification_id
                           AND attempt.channel = 'IN_APP'
                           AND attempt.status = 'DELIVERED'
                         WHERE task.recipient_account_id = ?
                           AND task.status = 'DELIVERED'
                           AND task.delivered_at IS NOT NULL
                           AND task.title IS NOT NULL
                        """,
                Long.class,
                recipientAccountId
        );
        return watermark == null ? 0L : watermark;
    }

    public LeasedTask claim(
            DueTask task,
            String workerId,
            String leaseToken,
            long leaseDurationMillis
    ) {
        long leaseDurationMicros = Math.multiplyExact(leaseDurationMillis, 1_000L);
        int updated = jdbcTemplate.update("""
                UPDATE notification_tasks
                   SET lease_owner = ?, lease_token = ?,
                       lease_until = TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6)),
                       attempt_count = attempt_count + 1,
                       version = version + 1
                 WHERE notification_id = ?
                   AND status = 'PENDING'
                   AND version = ?
                """,
                workerId,
                leaseToken,
                leaseDurationMicros,
                task.notificationId(),
                task.taskVersion()
        );
        if (updated != 1) {
            throw new IllegalStateException("notification task lease was not acquired");
        }
        return new LeasedTask(
                task.notificationId(),
                task.sourceDomain(),
                task.purpose(),
                task.recipientAccountId(),
                task.recipientRelationVersion(),
                task.resourceType(),
                task.resourceId(),
                task.resourceVersion(),
                task.sourceState(),
                task.expiresAt(),
                task.correlationId(),
                task.attemptCount() + 1,
                leaseToken,
                task.taskVersion() + 1L
        );
    }

    public Optional<DeliveryCompletion> completeDelivery(
            LeasedTask task,
            String title,
            Instant sourceExpiresAt
    ) {
        LocalDateTime sourceExpiry = sourceExpiresAt == null
                ? null
                : utcLocalDateTime(sourceExpiresAt);
        int updated = jdbcTemplate.update("""
                UPDATE notification_tasks task
                  JOIN (SELECT CAST(? AS DATETIME(6)) AS source_expires_at) boundary
                   SET task.status = CASE
                               WHEN task.expires_at IS NOT NULL
                                    AND UTC_TIMESTAMP(6) >= task.expires_at THEN 'CANCELLED'
                               WHEN boundary.source_expires_at IS NOT NULL
                                    AND UTC_TIMESTAMP(6) >= boundary.source_expires_at THEN 'CANCELLED'
                               ELSE 'DELIVERED'
                           END,
                       task.next_attempt_at = NULL,
                       task.lease_owner = NULL, task.lease_token = NULL,
                       task.lease_until = NULL,
                       task.last_error_code = CASE
                               WHEN task.expires_at IS NOT NULL
                                    AND UTC_TIMESTAMP(6) >= task.expires_at THEN 'TASK_EXPIRED'
                               WHEN boundary.source_expires_at IS NOT NULL
                                    AND UTC_TIMESTAMP(6) >= boundary.source_expires_at
                                    THEN 'SOURCE_SUPERSEDED'
                               ELSE NULL
                           END,
                       task.title = CASE
                               WHEN (task.expires_at IS NULL OR UTC_TIMESTAMP(6) < task.expires_at)
                                    AND (boundary.source_expires_at IS NULL
                                         OR UTC_TIMESTAMP(6) < boundary.source_expires_at)
                                    THEN ?
                               ELSE NULL
                           END,
                       task.delivered_at = CASE
                               WHEN (task.expires_at IS NULL OR UTC_TIMESTAMP(6) < task.expires_at)
                                    AND (boundary.source_expires_at IS NULL
                                         OR UTC_TIMESTAMP(6) < boundary.source_expires_at)
                                    THEN UTC_TIMESTAMP(6)
                               ELSE NULL
                           END,
                       task.version = task.version + 1
                 WHERE task.notification_id = ?
                   AND task.status = 'PENDING'
                   AND task.lease_token = ?
                   AND task.lease_until > UTC_TIMESTAMP(6)
                   AND task.version = ?
                """,
                sourceExpiry,
                title,
                task.notificationId(),
                task.leaseToken(),
                task.claimedTaskVersion()
        );
        if (updated != 1) {
            return Optional.empty();
        }
        return Optional.of(jdbcTemplate.queryForObject("""
                        SELECT status, last_error_code
                          FROM notification_tasks
                         WHERE notification_id = ?
                        """,
                (resultSet, rowNumber) -> new DeliveryCompletion(
                        NotificationTaskStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("last_error_code")
                ),
                task.notificationId()
        ));
    }

    public boolean markCancelled(LeasedTask task, String reason) {
        return updateTerminal(task, "CANCELLED", reason, null, false);
    }

    public boolean markFailed(LeasedTask task, String reason) {
        return updateTerminal(task, "FAILED", reason, null, false);
    }

    public boolean scheduleRetry(
            LeasedTask task,
            String reason,
            long retryDelayMillis
    ) {
        long retryDelayMicros = Math.multiplyExact(retryDelayMillis, 1_000L);
        return jdbcTemplate.update("""
                UPDATE notification_tasks
                   SET next_attempt_at = TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6)),
                       lease_owner = NULL, lease_token = NULL,
                       lease_until = NULL, last_error_code = ?, version = version + 1
                 WHERE notification_id = ?
                   AND status = 'PENDING'
                   AND lease_token = ?
                   AND lease_until > UTC_TIMESTAMP(6)
                   AND version = ?
                   AND (expires_at IS NULL
                        OR TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6)) < expires_at)
                """,
                retryDelayMicros,
                reason,
                task.notificationId(),
                task.leaseToken(),
                task.claimedTaskVersion(),
                retryDelayMicros
        ) == 1;
    }

    public boolean scheduleWaitingConversionHold(
            LeasedTask task,
            String reason,
            long retryDelayMillis
    ) {
        long retryDelayMicros = Math.multiplyExact(retryDelayMillis, 1_000L);
        return jdbcTemplate.update("""
                UPDATE notification_tasks
                   SET attempt_count = GREATEST(attempt_count - 1, 0),
                       next_attempt_at = TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6)),
                       lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                       last_error_code = ?, version = version + 1
                 WHERE notification_id = ?
                   AND status = 'PENDING'
                   AND lease_token = ?
                   AND lease_until > UTC_TIMESTAMP(6)
                   AND version = ?
                   AND (expires_at IS NULL
                        OR TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6)) < expires_at)
                """,
                retryDelayMicros,
                reason,
                task.notificationId(),
                task.leaseToken(),
                task.claimedTaskVersion(),
                retryDelayMicros
        ) == 1;
    }

    public List<PendingWaitingEntryTask> findPendingWaitingEntryTasksForUpdate(
            long waitingTeamId
    ) {
        return jdbcTemplate.query("""
                        SELECT notification_id, version, correlation_id,
                               lease_token IS NOT NULL AS claimed
                          FROM notification_tasks
                         WHERE source_domain = 'WAITING'
                           AND purpose = 'WAITING_ENTRY_IMMINENT'
                           AND resource_type = 'WAITING_TEAM'
                           AND resource_id = ?
                           AND status = 'PENDING'
                         ORDER BY notification_id
                         FOR UPDATE
                        """,
                (resultSet, rowNumber) -> new PendingWaitingEntryTask(
                        resultSet.getLong("notification_id"),
                        resultSet.getLong("version"),
                        resultSet.getString("correlation_id"),
                        resultSet.getBoolean("claimed")
                ),
                waitingTeamId
        );
    }

    public boolean reevaluateWaitingEntryTask(PendingWaitingEntryTask task) {
        return jdbcTemplate.update("""
                UPDATE notification_tasks
                   SET attempt_count = GREATEST(attempt_count - ?, 0),
                       next_attempt_at = UTC_TIMESTAMP(6),
                       lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                       last_error_code = NULL,
                       version = version + 1
                 WHERE notification_id = ?
                   AND status = 'PENDING'
                   AND version = ?
                """,
                task.claimed() ? 1 : 0,
                task.notificationId(),
                task.taskVersion()
        ) == 1;
    }

    private boolean updateTerminal(
            LeasedTask task,
            String status,
            String errorCode,
            String title,
            boolean delivered
    ) {
        return jdbcTemplate.update("""
                UPDATE notification_tasks
                   SET status = ?, next_attempt_at = NULL,
                       lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                       last_error_code = ?, title = ?,
                       delivered_at = CASE WHEN ? THEN UTC_TIMESTAMP(6) ELSE NULL END,
                       version = version + 1
                 WHERE notification_id = ?
                   AND status = 'PENDING'
                   AND lease_token = ?
                   AND lease_until > UTC_TIMESTAMP(6)
                   AND version = ?
                """,
                status,
                errorCode,
                title,
                delivered,
                task.notificationId(),
                task.leaseToken(),
                task.claimedTaskVersion()
        ) == 1;
    }

    private StoredTask findByLogicalIdentityForShare(NotificationSourceEventV1 event) {
        List<StoredTask> rows = jdbcTemplate.query("""
                        SELECT notification_id, payload_fingerprint
                          FROM notification_tasks
                         WHERE source_domain = ?
                           AND source_event_id = ?
                           AND recipient_account_id = ?
                           AND purpose = ?
                           AND resource_type = ?
                           AND resource_id = ?
                           AND resource_version = ?
                           FOR SHARE
                        """,
                (resultSet, rowNumber) -> new StoredTask(
                        resultSet.getLong("notification_id"),
                        resultSet.getString("payload_fingerprint"),
                        false
                ),
                event.sourceDomain().name(),
                event.sourceEventId(),
                Long.parseLong(event.recipientAccountId()),
                event.purpose().name(),
                event.resourceType().name(),
                Long.parseLong(event.resourceId()),
                event.resourceVersion()
        );
        if (rows.size() != 1) {
            throw new IllegalStateException("notification logical identity did not converge");
        }
        return rows.getFirst();
    }

    private static LocalDateTime utcLocalDateTime(OffsetDateTime value) {
        return utcLocalDateTime(value.toInstant());
    }

    private static LocalDateTime utcLocalDateTime(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant utcInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    public record StoredTask(long notificationId, String payloadFingerprint, boolean inserted) {
    }

    public record DueTask(
            long notificationId,
            NotificationSourceDomain sourceDomain,
            NotificationPurpose purpose,
            long recipientAccountId,
            long recipientRelationVersion,
            NotificationResourceType resourceType,
            long resourceId,
            long resourceVersion,
            String sourceState,
            Instant expiresAt,
            String correlationId,
            int attemptCount,
            long taskVersion,
            boolean leaseRecovery
    ) {
    }

    public record LeasedTask(
            long notificationId,
            NotificationSourceDomain sourceDomain,
            NotificationPurpose purpose,
            long recipientAccountId,
            long recipientRelationVersion,
            NotificationResourceType resourceType,
            long resourceId,
            long resourceVersion,
            String sourceState,
            Instant expiresAt,
            String correlationId,
            int attemptCount,
            String leaseToken,
            long claimedTaskVersion
    ) {
    }

    public record PendingWaitingEntryTask(
            long notificationId,
            long taskVersion,
            String correlationId,
            boolean claimed
    ) {
    }

    public record DeliveryCompletion(NotificationTaskStatus status, String reason) {
    }

    public record HistoryBoundary(Instant occurredAt, long notificationId) {
    }

    public record HistoryTask(
            long notificationId,
            NotificationSourceDomain sourceDomain,
            NotificationPurpose purpose,
            long recipientRelationVersion,
            NotificationResourceType resourceType,
            long resourceId,
            long resourceVersion,
            String title,
            Instant occurredAt,
            Instant createdAt,
            Instant deliveredAt
    ) {
    }
}
