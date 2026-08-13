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
}
