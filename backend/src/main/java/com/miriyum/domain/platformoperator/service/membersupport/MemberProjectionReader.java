package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSearchCriteria;
import com.miriyum.domain.auth.membersupport.MemberStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class MemberProjectionReader {
    private static final String PROJECTION = """
            WITH active AS (
                SELECT account_type, account_id,
                       MAX(level = 'PERMANENT_SUSPENSION') permanent,
                       MAX(level = 'TEMPORARY_SUSPENSION') temporary,
                       MAX(level = 'FEATURE_RESTRICTION') feature
                  FROM member_sanctions
                 WHERE status = 'APPLIED' AND applied_at <= :now
                   AND (ends_at IS NULL OR ends_at > :now)
                 GROUP BY account_type, account_id
            ), accounts AS (
                SELECT 'CONSUMER' account_type, consumer_account_id account_id,
                       password_reset_required, status = 'SUSPENDED' suspended,
                       created_at joined_at, support_version
                  FROM consumer_accounts
                UNION ALL
                SELECT 'STORE_OPERATOR', store_operator_account_id,
                       password_reset_required, status = 'SUSPENDED', created_at, support_version
                  FROM store_operator_accounts
            ), projected AS (
                SELECT accounts.*,
                       CASE
                         WHEN COALESCE(active.permanent, 0) = 1 THEN 'PERMANENTLY_SUSPENDED'
                         WHEN COALESCE(active.temporary, 0) = 1 OR accounts.suspended = 1
                           THEN 'TEMPORARILY_SUSPENDED'
                         WHEN accounts.password_reset_required = 1 THEN 'PASSWORD_RESET_REQUIRED'
                         WHEN COALESCE(active.feature, 0) = 1 THEN 'FEATURE_RESTRICTED'
                         ELSE 'ACTIVE'
                       END member_status
                  FROM accounts
                  LEFT JOIN active ON active.account_type = accounts.account_type
                                  AND active.account_id = accounts.account_id
            )
            """;
    private static final String FILTER = """
             WHERE (:accountType IS NULL OR account_type = :accountType)
               AND (:joinedFrom IS NULL OR joined_at >= :joinedFrom)
               AND (:joinedTo IS NULL OR joined_at <= :joinedTo)
               AND (:status IS NULL OR member_status = :status)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MemberProjectionReader(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Page read(MemberAccountType type, MemberStatus status, MemberSearchCriteria criteria,
                     int offset, int limit, LocalDateTime now) {
        Map<String, Object> parameters = new java.util.HashMap<>();
        parameters.put("accountType", type == null ? null : type.name());
        parameters.put("status", status == null ? null : status.name());
        parameters.put("joinedFrom", local(criteria.joinedFrom()));
        parameters.put("joinedTo", local(criteria.joinedTo()));
        parameters.put("now", now);
        parameters.put("offset", offset);
        parameters.put("limit", limit);
        List<Row> content = jdbc.query(PROJECTION + "SELECT * FROM projected" + FILTER
                        + " ORDER BY joined_at DESC, account_id DESC LIMIT :limit OFFSET :offset",
                parameters, (result, rowNumber) -> new Row(
                        MemberAccountType.valueOf(result.getString("account_type")),
                        result.getLong("account_id"),
                        result.getTimestamp("joined_at").toLocalDateTime().toInstant(ZoneOffset.UTC),
                        result.getLong("support_version"),
                        MemberStatus.valueOf(result.getString("member_status"))));
        Long total = jdbc.queryForObject(
                PROJECTION + "SELECT COUNT(*) FROM projected" + FILTER, parameters, Long.class);
        return new Page(content, total == null ? 0 : total);
    }

    private static LocalDateTime local(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public record Row(MemberAccountType accountType, long accountId, Instant joinedAt,
                      long supportVersion, MemberStatus status) {}
    public record Page(List<Row> content, long totalElements) {
        public Page { content = List.copyOf(content); }
    }
}
