package com.miriyum.domain.platformoperator.repository.membersupport;

import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MemberSupportCaseSubmissionStore {
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO member_support_cases
                (case_public_id, case_type, account_type, account_id,
                 identity_verification_id, source_sanction_id, status,
                 target_support_version, reason_code, row_version,
                 submitted_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE member_support_case_id = member_support_case_id
            """;

    private final JdbcTemplate jdbc;

    public MemberSupportCaseSubmissionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertIfAbsent(MemberSupportCase supportCase) {
        jdbc.update(INSERT_IF_ABSENT, statement -> bind(statement, supportCase));
    }

    private void bind(PreparedStatement statement, MemberSupportCase supportCase) throws SQLException {
        statement.setString(1, supportCase.getPublicId());
        statement.setString(2, supportCase.getCaseType().name());
        statement.setString(3, supportCase.getAccountType().name());
        statement.setLong(4, supportCase.getAccountId());
        nullableLong(statement, 5, supportCase.getIdentityVerificationId());
        nullableLong(statement, 6, supportCase.getSourceSanctionId());
        statement.setString(7, supportCase.getStatus().name());
        statement.setLong(8, supportCase.getTargetSupportVersion());
        statement.setString(9, supportCase.getReasonCode());
        statement.setLong(10, supportCase.getRowVersion());
        Timestamp submittedAt = Timestamp.valueOf(supportCase.getSubmittedAt());
        statement.setTimestamp(11, submittedAt);
        statement.setTimestamp(12, submittedAt);
        statement.setTimestamp(13, submittedAt);
    }

    private void nullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.BIGINT);
        else statement.setLong(index, value);
    }
}
