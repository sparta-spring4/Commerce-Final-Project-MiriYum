package com.miriyum.domain.notification.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationTaskTransitionAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public NotificationTaskTransitionAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertInitialPending(long notificationId, String correlationId) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO notification_task_transition_audits (
                    notification_id, from_status, to_status, reason, correlation_id
                ) VALUES (?, NULL, 'PENDING', 'SOURCE_EVENT_RECORDED', ?)
                """, notificationId, correlationId);
        if (inserted != 1) {
            throw new IllegalStateException("initial notification transition was not audited");
        }
    }
}
