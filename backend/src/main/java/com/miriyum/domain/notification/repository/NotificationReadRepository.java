package com.miriyum.domain.notification.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 소비자별 알림 변경 version의 영속성 경계를 소유한다. */
@Repository
public class NotificationReadRepository {

    private final JdbcTemplate jdbcTemplate;

    public NotificationReadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public enum ReadResult {
        CHANGED,
        ALREADY_READ,
        NOT_FOUND
    }

    /** 계정 변경 상태가 아직 없으면 초기값 0을 반환한다. */
    public long findChangeVersion(long consumerAccountId) {
        List<Long> versions = jdbcTemplate.query(
                """
                SELECT change_version
                  FROM notification_consumer_change_states
                 WHERE consumer_account_id = ?
                """,
                (resultSet, rowNumber) -> resultSet.getLong("change_version"),
                consumerAccountId
        );
        return versions.isEmpty() ? 0L : versions.getFirst();
    }

    /**
     * 공개 변경 전에 계정별 version 행을 생성하고 잠근다.
     */
    public long lockOrCreateChangeState(long consumerAccountId) {
        // 먼저 계정별 상태 행을 확보해 모든 경로에서 계정 행 -> 알림 이력 순서로 잠근다.
        jdbcTemplate.update(
                """
                INSERT INTO notification_consumer_change_states (
                    consumer_account_id,
                    change_version
                )
                VALUES (?, 0)
                ON DUPLICATE KEY UPDATE
                    change_version = change_version
                """,
                consumerAccountId
        );
        return lockChangeState(consumerAccountId);
    }

    private long lockChangeState(long consumerAccountId) {
        List<Long> versions = jdbcTemplate.query(
                """
                SELECT change_version
                  FROM notification_consumer_change_states
                 WHERE consumer_account_id = ?
                 FOR UPDATE
                """,
                (resultSet, rowNumber) -> resultSet.getLong("change_version"),
                consumerAccountId
        );
        if (versions.size() != 1) {
            throw new IllegalStateException("notification change state lock was not acquired");
        }
        return versions.getFirst();
    }

    /** 잠긴 계정 상태를 새 공개 전달보다 뒤의 단조 version으로 이동한다. */
    public void advanceForDelivery(long consumerAccountId, long notificationId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE notification_consumer_change_states
                   SET change_version = GREATEST(change_version + 1, ?)
                 WHERE consumer_account_id = ?
                """,
                notificationId,
                consumerAccountId
        );
        if (updated != 1) {
            throw new IllegalStateException("notification change version was not advanced");
        }
    }

    /** 실제 읽음 변경 뒤 계정 version을 정확히 한 단계 전진시킨다. */
    public void advanceForRead(long consumerAccountId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE notification_consumer_change_states
                   SET change_version = change_version + 1
                 WHERE consumer_account_id = ?
                """,
                consumerAccountId
        );
        if (updated != 1) {
            throw new IllegalStateException("notification read change version was not advanced");
        }
    }

    /** 본인 공개 전달 완료 알림의 실제 미확인 집합을 집계한다. */
    public long countUnread(long consumerAccountId) {
        Long count = jdbcTemplate.queryForObject(
                publicUnreadSql("COUNT(*)"), Long.class, consumerAccountId);
        return count == null ? 0L : count;
    }

    /** 본인 공개 알림 하나의 최초 읽음 시각을 DB 현재 시각으로 기록한다. */
    public ReadResult markOneRead(long consumerAccountId, long notificationId) {
        int changed = jdbcTemplate.update(
                """
                UPDATE notification_tasks task
                   SET task.read_at = UTC_TIMESTAMP(6)
                 WHERE task.notification_id = ?
                   AND task.recipient_account_id = ?
                   AND task.status = 'DELIVERED'
                   AND task.delivered_at IS NOT NULL
                   AND task.title IS NOT NULL
                   AND task.read_at IS NULL
                   AND EXISTS (
                       SELECT 1
                         FROM notification_channel_attempts attempt
                        WHERE attempt.notification_id = task.notification_id
                          AND attempt.channel = 'IN_APP'
                          AND attempt.status = 'DELIVERED'
                   )
                """,
                notificationId,
                consumerAccountId
        );
        if (changed == 1) {
            return ReadResult.CHANGED;
        }
        Integer visible = jdbcTemplate.queryForObject(
                publicUnreadSql("COUNT(*)")
                        .replace("task.read_at IS NULL", "task.notification_id = ?"),
                Integer.class,
                consumerAccountId,
                notificationId
        );
        return visible != null && visible == 1 ? ReadResult.ALREADY_READ : ReadResult.NOT_FOUND;
    }

    /** 직렬화 시점까지 본인에게 공개된 미확인 알림을 같은 DB 시각으로 읽음 처리한다. */
    public int markAllRead(long consumerAccountId) {
        return jdbcTemplate.update(
                """
                UPDATE notification_tasks task
                   SET task.read_at = UTC_TIMESTAMP(6)
                 WHERE task.recipient_account_id = ?
                   AND task.status = 'DELIVERED'
                   AND task.delivered_at IS NOT NULL
                   AND task.title IS NOT NULL
                   AND task.read_at IS NULL
                   AND EXISTS (
                       SELECT 1
                         FROM notification_channel_attempts attempt
                        WHERE attempt.notification_id = task.notification_id
                          AND attempt.channel = 'IN_APP'
                          AND attempt.status = 'DELIVERED'
                   )
                """,
                consumerAccountId
        );
    }

    private String publicUnreadSql(String projection) {
        return """
                SELECT %s
                  FROM notification_tasks task
                 WHERE task.recipient_account_id = ?
                   AND task.status = 'DELIVERED'
                   AND task.delivered_at IS NOT NULL
                   AND task.title IS NOT NULL
                   AND task.read_at IS NULL
                   AND EXISTS (
                       SELECT 1
                         FROM notification_channel_attempts attempt
                        WHERE attempt.notification_id = task.notification_id
                          AND attempt.channel = 'IN_APP'
                          AND attempt.status = 'DELIVERED'
                   )
                """.formatted(projection);
    }
}
