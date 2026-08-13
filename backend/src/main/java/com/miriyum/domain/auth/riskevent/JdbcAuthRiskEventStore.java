package com.miriyum.domain.auth.riskevent;

import com.miriyum.domain.auth.refreshtoken.PendingRefreshTokenRiskEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** MySQL unique key로 동일 재사용 사건의 중복 기록을 막는다. */
@Component
public class JdbcAuthRiskEventStore implements AuthRiskEventStore {

    // VALUES(...)는 MySQL 8.0.20부터 deprecated라 행 별칭(AS new)을 사용한다.
    // GREATEST 안의 컬럼은 테이블명으로 한정해야 한다. 별칭을 붙이면 같은 이름이 양쪽에 생겨
    // 한정하지 않은 참조가 ERROR 1052(ambiguous)로 실패한다.
    private static final String UPSERT_OCCURRENCE = """
            INSERT INTO auth_risk_events (
                event_key, namespace, account_id, family_id, token_hash,
                source_event, origin_event, policy_version, occurred_at,
                occurrence_count, last_occurred_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6)) AS new
            ON DUPLICATE KEY UPDATE
                occurrence_count =
                    GREATEST(auth_risk_events.occurrence_count, new.occurrence_count),
                last_occurred_at =
                    GREATEST(auth_risk_events.last_occurred_at, new.last_occurred_at)
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAuthRiskEventStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(PendingRefreshTokenRiskEvent event) {
        jdbcTemplate.update(
                UPSERT_OCCURRENCE,
                event.eventKey(),
                event.namespace().value(),
                event.accountId(),
                event.familyId(),
                event.tokenHash(),
                event.sourceEvent(),
                event.originEvent(),
                event.policyVersion(),
                utcDateTime(event.occurredAt()),
                event.occurrenceCount(),
                utcDateTime(event.lastOccurredAt()));
    }

    /**
     * 세 시각 컬럼의 기준을 UTC 하나로 고정한다.
     *
     * <p>{@code Instant}를 그대로 바인딩하면 드라이버가 커넥션 타임존으로 변환하므로,
     * 서버 시각으로 채우는 {@code created_at}(UTC)과 기준이 어긋난다.
     * {@code LocalDateTime}은 변환 없이 그대로 저장되므로 UTC로 미리 변환해 넘긴다.</p>
     */
    private LocalDateTime utcDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
