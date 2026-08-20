ALTER TABLE notification_tasks
    ADD COLUMN read_at DATETIME(6) NULL AFTER delivered_at,
    ADD CONSTRAINT chk_notification_read_after_delivery
        CHECK (read_at IS NULL OR (delivered_at IS NOT NULL AND read_at >= delivered_at)),
    ADD INDEX idx_notification_unread (
        recipient_account_id,
        status,
        read_at,
        delivered_at,
        notification_id
    );

UPDATE notification_tasks task
JOIN notification_channel_attempts attempt
  ON attempt.notification_id = task.notification_id
 AND attempt.channel = 'IN_APP'
 AND attempt.status = 'DELIVERED'
SET task.read_at = task.delivered_at
WHERE task.status = 'DELIVERED'
  AND task.delivered_at IS NOT NULL
  AND task.title IS NOT NULL
  AND task.read_at IS NULL;

CREATE TABLE notification_consumer_change_states (
    consumer_account_id BIGINT NOT NULL,
    change_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_account_id),
    CONSTRAINT fk_notification_change_state_consumer
        FOREIGN KEY (consumer_account_id)
        REFERENCES consumer_accounts (consumer_account_id),
    CONSTRAINT chk_notification_change_version CHECK (change_version >= 0)
) ENGINE = InnoDB;

INSERT INTO notification_consumer_change_states (
    consumer_account_id,
    change_version
)
SELECT task.recipient_account_id, MAX(task.notification_id)
  FROM notification_tasks task
  JOIN notification_channel_attempts attempt
    ON attempt.notification_id = task.notification_id
   AND attempt.channel = 'IN_APP'
   AND attempt.status = 'DELIVERED'
 WHERE task.status = 'DELIVERED'
   AND task.delivered_at IS NOT NULL
   AND task.title IS NOT NULL
 GROUP BY task.recipient_account_id;
