package com.miriyum.domain.notification.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationChannelAttemptRepository {

    private final JdbcTemplate jdbcTemplate;

    public NotificationChannelAttemptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertInitialInApp(long notificationId) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO notification_channel_attempts (
                    notification_id, channel, status
                ) VALUES (?, 'IN_APP', 'PENDING')
                """, notificationId);
        if (inserted != 1) {
            throw new IllegalStateException("initial notification channel attempt was not recorded");
        }
    }

    public void markProcessing(long notificationId) {
        requireSingleUpdate(jdbcTemplate.update("""
                UPDATE notification_channel_attempts
                   SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                       last_attempted_at = UTC_TIMESTAMP(6), failure_code = NULL
                 WHERE notification_id = ?
                   AND channel = 'IN_APP'
                   AND status IN ('PENDING', 'PROCESSING')
                """, notificationId));
    }

    public void markDelivered(long notificationId) {
        updateTerminal(notificationId, "DELIVERED", null);
    }

    public void markCancelled(long notificationId, String reason) {
        updateTerminal(notificationId, "CANCELLED", reason);
    }

    public void markFailed(long notificationId, String reason) {
        updateTerminal(notificationId, "FAILED", reason);
    }

    public void markRetryPending(long notificationId, String reason) {
        requireSingleUpdate(jdbcTemplate.update("""
                UPDATE notification_channel_attempts
                   SET status = 'PENDING', failure_code = ?
                 WHERE notification_id = ?
                   AND channel = 'IN_APP'
                   AND status = 'PROCESSING'
                """, reason, notificationId));
    }

    public void markWaitingHoldPending(long notificationId, String reason) {
        requireSingleUpdate(jdbcTemplate.update("""
                UPDATE notification_channel_attempts
                   SET status = 'PENDING',
                       attempt_count = GREATEST(attempt_count - 1, 0),
                       failure_code = ?
                 WHERE notification_id = ?
                   AND channel = 'IN_APP'
                   AND status = 'PROCESSING'
                """, reason, notificationId));
    }

    public void markReevaluationPending(long notificationId, boolean claimed) {
        requireSingleUpdate(jdbcTemplate.update("""
                UPDATE notification_channel_attempts
                   SET status = 'PENDING',
                       attempt_count = GREATEST(attempt_count - ?, 0),
                       failure_code = NULL
                 WHERE notification_id = ?
                   AND channel = 'IN_APP'
                   AND status IN ('PENDING', 'PROCESSING')
                """, claimed ? 1 : 0, notificationId));
    }

    private void updateTerminal(long notificationId, String status, String reason) {
        requireSingleUpdate(jdbcTemplate.update("""
                UPDATE notification_channel_attempts
                   SET status = ?, failure_code = ?
                 WHERE notification_id = ?
                   AND channel = 'IN_APP'
                   AND status = 'PROCESSING'
                """, status, reason, notificationId));
    }

    private static void requireSingleUpdate(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("notification channel attempt did not converge");
        }
    }
}
