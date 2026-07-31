package com.miriyum.domain.auth.logindelay;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Stores confirmed login failures and the short-lived password-comparison lease.
 */
@Repository
public class LoginFailureDelayRepository {

    private final JdbcTemplate jdbcTemplate;

    public LoginFailureDelayRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<LoginFailureDelay> find(String accountNamespace, long accountId) {
        List<LoginFailureDelay> found = jdbcTemplate.query(
                "SELECT consecutive_failures, delay_stage, next_attempt_allowed_at, "
                        + "active_attempt_token, active_attempt_expires_at "
                        + "FROM login_failure_delays "
                        + "WHERE account_namespace = ? AND account_id = ?",
                LoginFailureDelayRepository::mapRow,
                accountNamespace, accountId);
        return found.stream().findFirst();
    }

    public boolean insertAttempt(
            String accountNamespace,
            long accountId,
            String token,
            LocalDateTime expiresAt,
            LocalDateTime now
    ) {
        return jdbcTemplate.update(
                "INSERT IGNORE INTO login_failure_delays "
                        + "(account_namespace, account_id, consecutive_failures, delay_stage, "
                        + "next_attempt_allowed_at, active_attempt_token, active_attempt_expires_at, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, 0, 0, NULL, ?, ?, ?, ?)",
                accountNamespace, accountId, token, expiresAt, now, now) == 1;
    }

    public boolean acquireExistingAttempt(
            String accountNamespace,
            long accountId,
            String token,
            LocalDateTime expiresAt,
            LocalDateTime now
    ) {
        return jdbcTemplate.update(
                "UPDATE login_failure_delays "
                        + "SET active_attempt_token = ?, active_attempt_expires_at = ?, updated_at = ? "
                        + "WHERE account_namespace = ? AND account_id = ? "
                        + "AND (next_attempt_allowed_at IS NULL OR next_attempt_allowed_at <= ?) "
                        + "AND (active_attempt_token IS NULL OR active_attempt_expires_at <= ?)",
                token, expiresAt, now, accountNamespace, accountId, now, now) == 1;
    }

    public Optional<LoginFailureDelay> lockExisting(String accountNamespace, long accountId) {
        List<LoginFailureDelay> found = jdbcTemplate.query(
                "SELECT consecutive_failures, delay_stage, next_attempt_allowed_at, "
                        + "active_attempt_token, active_attempt_expires_at "
                        + "FROM login_failure_delays "
                        + "WHERE account_namespace = ? AND account_id = ? FOR UPDATE",
                LoginFailureDelayRepository::mapRow,
                accountNamespace, accountId);
        return found.stream().findFirst();
    }

    public void save(String accountNamespace, long accountId, LoginFailureDelay delay, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE login_failure_delays "
                        + "SET consecutive_failures = ?, delay_stage = ?, next_attempt_allowed_at = ?, "
                        + "active_attempt_token = NULL, active_attempt_expires_at = NULL, updated_at = ? "
                        + "WHERE account_namespace = ? AND account_id = ?",
                delay.consecutiveFailures(), delay.delayStage(), delay.nextAttemptAllowedAt(), now,
                accountNamespace, accountId);
    }

    public void resetOwnedAttempt(String accountNamespace, long accountId, String token) {
        jdbcTemplate.update(
                "DELETE FROM login_failure_delays "
                        + "WHERE account_namespace = ? AND account_id = ? AND active_attempt_token = ?",
                accountNamespace, accountId, token);
    }

    public void releaseAttempt(String accountNamespace, long accountId, String token, LocalDateTime now) {
        jdbcTemplate.update(
                "DELETE FROM login_failure_delays "
                        + "WHERE account_namespace = ? AND account_id = ? AND active_attempt_token = ? "
                        + "AND consecutive_failures = 0 AND delay_stage = 0 AND next_attempt_allowed_at IS NULL",
                accountNamespace, accountId, token);
        jdbcTemplate.update(
                "UPDATE login_failure_delays "
                        + "SET active_attempt_token = NULL, active_attempt_expires_at = NULL, updated_at = ? "
                        + "WHERE account_namespace = ? AND account_id = ? AND active_attempt_token = ?",
                now, accountNamespace, accountId, token);
    }

    private static LoginFailureDelay mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new LoginFailureDelay(
                rs.getInt("consecutive_failures"),
                rs.getInt("delay_stage"),
                toLocalDateTime(rs.getTimestamp("next_attempt_allowed_at")),
                rs.getString("active_attempt_token"),
                toLocalDateTime(rs.getTimestamp("active_attempt_expires_at")));
    }

    private static LocalDateTime toLocalDateTime(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
