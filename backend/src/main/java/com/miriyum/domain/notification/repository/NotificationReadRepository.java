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

    /** 계정 변경 상태가 아직 없으면 호환 기반의 초기값 0을 반환한다. */
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
     * 공개 변경 전에 계정별 version 행을 생성·잠그고 legacy 공개 최대값까지 보정한다.
     *
     * <p>rolling 구간의 구 worker 전달도 이후 변경보다 작은 watermark로 남지 않는다.</p>
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
        long lockedVersion = lockChangeState(consumerAccountId);

        // rolling 호환 경로에서 current read로 커밋된 구 worker 전달을 항상 포함한다.
        List<Long> legacyPublicNotifications = jdbcTemplate.query(
                """
                SELECT task.notification_id
                  FROM notification_tasks task
                  JOIN notification_channel_attempts attempt
                    ON attempt.notification_id = task.notification_id
                   AND attempt.channel = 'IN_APP'
                   AND attempt.status = 'DELIVERED'
                 WHERE task.recipient_account_id = ?
                   AND task.status = 'DELIVERED'
                   AND task.delivered_at IS NOT NULL
                   AND task.title IS NOT NULL
                 ORDER BY task.notification_id DESC
                 LIMIT 1
                 FOR UPDATE
                """,
                (resultSet, rowNumber) -> resultSet.getLong("notification_id"),
                consumerAccountId
        );
        long legacyPublicMax = legacyPublicNotifications.isEmpty()
                ? 0L
                : legacyPublicNotifications.getFirst();
        long reconciledVersion = Math.max(lockedVersion, legacyPublicMax);
        if (reconciledVersion != lockedVersion) {
            jdbcTemplate.update(
                    """
                    UPDATE notification_consumer_change_states
                       SET change_version = ?
                     WHERE consumer_account_id = ?
                    """,
                    reconciledVersion,
                    consumerAccountId
            );
        }
        return reconciledVersion;
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
}
