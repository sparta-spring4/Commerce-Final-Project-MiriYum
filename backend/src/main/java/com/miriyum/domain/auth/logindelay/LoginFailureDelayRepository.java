package com.miriyum.domain.auth.logindelay;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code login_failure_delays} 접근을 JdbcTemplate로 수행한다.
 *
 * <p>AUTH-006은 실패 횟수·지연 단계를 중앙 저장소에서 원자적으로 갱신하고, 여러 인증 인스턴스가
 * 동시에 실패를 기록해도 5회 기준과 단계가 우회되지 않도록 요구한다. 그래서 갱신 경로는 JPA
 * 변경 감지가 아니라 {@code INSERT ... ON DUPLICATE KEY UPDATE}로 행 확보와 배타 잠금을 한 번에
 * 처리한다({@link #lock} 참고). 잠금은 호출 도메인이 소유한 트랜잭션이 커밋될 때까지 유지된다.</p>
 */
@Repository
public class LoginFailureDelayRepository {

    private final JdbcTemplate jdbcTemplate;

    public LoginFailureDelayRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 잠금 없이 현재 상태를 읽는다. 지연 중인지 확인하는 용도이며, 기록이 없으면 비어 있다.
     */
    public Optional<LoginFailureDelay> find(String accountNamespace, long accountId) {
        List<LoginFailureDelay> found = jdbcTemplate.query(
                "SELECT consecutive_failures, delay_stage, next_attempt_allowed_at "
                        + "FROM login_failure_delays "
                        + "WHERE account_namespace = ? AND account_id = ?",
                LoginFailureDelayRepository::mapRow,
                accountNamespace, accountId);
        return found.stream().findFirst();
    }

    /**
     * 이 계정의 행을 확보하고 배타 잠금을 잡은 뒤 현재 상태를 읽는다.
     *
     * <p>행이 없으면 만들고, 있으면 {@code updated_at}만 다시 써서 배타 잠금을 잡는다. 잠금을
     * 얻으려고 굳이 갱신문을 쓰는 이유는 교착을 피하기 위해서다. {@code INSERT IGNORE}로 행을
     * 확보한 뒤 {@code SELECT ... FOR UPDATE}로 잠그면, 이미 있는 행에 대해 {@code INSERT IGNORE}가
     * 공유 잠금을 잡고 그다음 조회가 이를 배타 잠금으로 승격하게 된다. 같은 계정에 동시 실패가
     * 몰리면 서로 상대의 공유 잠금 해제를 기다리다 교착에 빠진다(MySQL이 실제로
     * {@code Deadlock found when trying to get lock}으로 거절한다).</p>
     *
     * <p>{@code INSERT ... ON DUPLICATE KEY UPDATE}는 두 경로 모두 처음부터 배타 잠금을 잡으므로
     * 승격이 없고, 동시 요청은 교착 없이 차례로 직렬화된다. 잠금은 호출 트랜잭션이 커밋될 때까지
     * 유지되므로 이어지는 조회·갱신 사이에 다른 인스턴스가 끼어들지 못한다.</p>
     *
     * @param now 호출자가 UTC 기준으로 만든 현재 시각. SQL의 {@code CURRENT_TIMESTAMP}를 쓰지 않는
     *     이유는 그 값이 DB 세션 타임존을 따라, 같은 행 안에서 애플리케이션이 UTC로 쓰는
     *     {@code next_attempt_allowed_at}과 기준이 어긋나기 때문이다(C-004는 절대 시각을 UTC로
     *     저장하도록 정한다).
     */
    public LoginFailureDelay lock(String accountNamespace, long accountId, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO login_failure_delays "
                        + "(account_namespace, account_id, consecutive_failures, delay_stage, "
                        + "next_attempt_allowed_at, created_at, updated_at) "
                        + "VALUES (?, ?, 0, 0, NULL, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE updated_at = ?",
                accountNamespace, accountId, now, now, now);

        return jdbcTemplate.queryForObject(
                "SELECT consecutive_failures, delay_stage, next_attempt_allowed_at "
                        + "FROM login_failure_delays "
                        + "WHERE account_namespace = ? AND account_id = ?",
                LoginFailureDelayRepository::mapRow,
                accountNamespace, accountId);
    }

    /**
     * 잠근 행을 새 실패 상태로 갱신한다.
     *
     * @param now 호출자가 UTC 기준으로 만든 현재 시각({@link #lock}과 같은 이유로 SQL의
     *     {@code CURRENT_TIMESTAMP}를 쓰지 않는다)
     */
    public void save(String accountNamespace, long accountId, LoginFailureDelay delay, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE login_failure_delays "
                        + "SET consecutive_failures = ?, delay_stage = ?, next_attempt_allowed_at = ?, updated_at = ? "
                        + "WHERE account_namespace = ? AND account_id = ?",
                delay.consecutiveFailures(), delay.delayStage(), delay.nextAttemptAllowedAt(), now,
                accountNamespace, accountId);
    }

    /**
     * 로그인 성공 시 실패 상태를 초기화한다. 행을 지우는 것으로 초기화를 갈음해 표가 무한히
     * 늘어나지 않게 한다.
     */
    public void reset(String accountNamespace, long accountId) {
        jdbcTemplate.update(
                "DELETE FROM login_failure_delays WHERE account_namespace = ? AND account_id = ?",
                accountNamespace, accountId);
    }

    private static LoginFailureDelay mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        java.sql.Timestamp nextAttemptAllowedAt = rs.getTimestamp("next_attempt_allowed_at");
        return new LoginFailureDelay(
                rs.getInt("consecutive_failures"),
                rs.getInt("delay_stage"),
                nextAttemptAllowedAt == null ? null : nextAttemptAllowedAt.toLocalDateTime());
    }
}
