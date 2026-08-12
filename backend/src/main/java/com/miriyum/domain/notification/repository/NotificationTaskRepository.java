package com.miriyum.domain.notification.repository;

import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import java.sql.Timestamp;
import java.util.List;
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

    public record StoredTask(long notificationId, String payloadFingerprint, boolean inserted) {
    }
}
