package com.miriyum.domain.auth.riskevent;

import com.miriyum.domain.auth.refreshtoken.PendingRefreshTokenRiskEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** MySQL unique key로 동일 재사용 사건의 중복 기록을 막는다. */
@Component
public class JdbcAuthRiskEventStore implements AuthRiskEventStore {

    private static final String INSERT_IF_ABSENT = """
            INSERT IGNORE INTO auth_risk_events (
                event_key, namespace, account_id, family_id, token_hash,
                source_event, origin_event, policy_version, occurred_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAuthRiskEventStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(PendingRefreshTokenRiskEvent event) {
        jdbcTemplate.update(
                INSERT_IF_ABSENT,
                event.eventKey(),
                event.namespace().value(),
                event.accountId(),
                event.familyId(),
                event.tokenHash(),
                event.sourceEvent(),
                event.originEvent(),
                event.policyVersion(),
                event.occurredAt());
    }
}
