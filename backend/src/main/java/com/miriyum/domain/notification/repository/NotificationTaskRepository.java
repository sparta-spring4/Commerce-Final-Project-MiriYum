package com.miriyum.domain.notification.repository;

import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.entity.NotificationPurpose;
import com.miriyum.domain.notification.entity.NotificationResourceType;
import com.miriyum.domain.notification.entity.NotificationSourceDomain;
import com.miriyum.domain.notification.entity.NotificationTaskStatus;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
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
                    timestamp(event.occurredAt()),
                    timestamp(event.scheduledAt()),
                    event.expiresAt() == null ? null : timestamp(event.expiresAt()),
                    event.timingPolicyVersion(),
                    event.correlationId(),
                    NotificationSourceEventV1.CONTRACT_VERSION,
                    fingerprint,
                    timestamp(event.scheduledAt())
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
                               expires_at, correlation_id, attempt_count,
                               lease_token IS NOT NULL AS lease_recovery
                          FROM notification_tasks
                         WHERE status = 'PENDING'
                           AND scheduled_at <= FROM_UNIXTIME(?)
                           AND (next_attempt_at IS NULL OR next_attempt_at <= FROM_UNIXTIME(?))
                           AND (lease_until IS NULL OR lease_until <= FROM_UNIXTIME(?))
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
                        instant(resultSet.getTimestamp("expires_at")),
                        resultSet.getString("correlation_id"),
                        resultSet.getInt("attempt_count"),
                        resultSet.getBoolean("lease_recovery")
                ),
                epochSeconds(now),
                epochSeconds(now),
                epochSeconds(now)
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
        Timestamp occurredAt = boundary == null ? null : Timestamp.from(boundary.occurredAt());
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
                        instant(resultSet.getTimestamp("occurred_at")),
                        instant(resultSet.getTimestamp("created_at")),
                        instant(resultSet.getTimestamp("delivered_at"))
                ),
                recipientAccountId,
                occurredAt,
                occurredAt,
                occurredAt,
                notificationId,
                limit
        );
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
                       lease_until = TIMESTAMPADD(MICROSECOND, ?, NOW(6)),
                       attempt_count = attempt_count + 1,
                       version = version + 1
                 WHERE notification_id = ? AND status = 'PENDING'
                """,
                workerId,
                leaseToken,
                leaseDurationMicros,
                task.notificationId()
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
                leaseToken
        );
    }

    public Optional<DeliveryCompletion> completeDelivery(
            LeasedTask task,
            String title,
            Instant sourceExpiresAt
    ) {
        BigDecimal sourceExpiry = sourceExpiresAt == null
                ? null
                : epochSeconds(sourceExpiresAt);
        int updated = jdbcTemplate.update("""
                UPDATE notification_tasks task
                  JOIN (SELECT FROM_UNIXTIME(?) AS source_expires_at) boundary
                   SET task.status = CASE
                               WHEN task.expires_at IS NOT NULL
                                    AND NOW(6) >= task.expires_at THEN 'CANCELLED'
                               WHEN boundary.source_expires_at IS NOT NULL
                                    AND NOW(6) >= boundary.source_expires_at THEN 'CANCELLED'
                               ELSE 'DELIVERED'
                           END,
                       task.next_attempt_at = NULL,
                       task.lease_owner = NULL, task.lease_token = NULL,
                       task.lease_until = NULL,
                       task.last_error_code = CASE
                               WHEN task.expires_at IS NOT NULL
                                    AND NOW(6) >= task.expires_at THEN 'TASK_EXPIRED'
                               WHEN boundary.source_expires_at IS NOT NULL
                                    AND NOW(6) >= boundary.source_expires_at
                                    THEN 'SOURCE_SUPERSEDED'
                               ELSE NULL
                           END,
                       task.title = CASE
                               WHEN (task.expires_at IS NULL OR NOW(6) < task.expires_at)
                                    AND (boundary.source_expires_at IS NULL
                                         OR NOW(6) < boundary.source_expires_at)
                                    THEN ?
                               ELSE NULL
                           END,
                       task.delivered_at = CASE
                               WHEN (task.expires_at IS NULL OR NOW(6) < task.expires_at)
                                    AND (boundary.source_expires_at IS NULL
                                         OR NOW(6) < boundary.source_expires_at)
                                    THEN NOW(6)
                               ELSE NULL
                           END,
                       task.version = task.version + 1
                 WHERE task.notification_id = ?
                   AND task.status = 'PENDING'
                   AND task.lease_token = ?
                   AND task.lease_until > NOW(6)
                """,
                sourceExpiry,
                title,
                task.notificationId(),
                task.leaseToken()
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
                   SET next_attempt_at = TIMESTAMPADD(MICROSECOND, ?, NOW(6)),
                       lease_owner = NULL, lease_token = NULL,
                       lease_until = NULL, last_error_code = ?, version = version + 1
                 WHERE notification_id = ?
                   AND status = 'PENDING'
                   AND lease_token = ?
                   AND lease_until > NOW(6)
                   AND (expires_at IS NULL
                        OR TIMESTAMPADD(MICROSECOND, ?, NOW(6)) < expires_at)
                """,
                retryDelayMicros,
                reason,
                task.notificationId(),
                task.leaseToken(),
                retryDelayMicros
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
                       delivered_at = CASE WHEN ? THEN NOW(6) ELSE NULL END,
                       version = version + 1
                 WHERE notification_id = ?
                   AND status = 'PENDING'
                   AND lease_token = ?
                   AND lease_until > NOW(6)
                """,
                status,
                errorCode,
                title,
                delivered,
                task.notificationId(),
                task.leaseToken()
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

    private static Timestamp timestamp(java.time.OffsetDateTime value) {
        return Timestamp.from(value.toInstant());
    }

    private static BigDecimal epochSeconds(Instant value) {
        return BigDecimal.valueOf(value.getEpochSecond())
                .add(BigDecimal.valueOf(value.getNano(), 9));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
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
            String leaseToken
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
