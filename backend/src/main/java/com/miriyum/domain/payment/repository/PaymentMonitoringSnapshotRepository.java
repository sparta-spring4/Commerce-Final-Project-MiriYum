package com.miriyum.domain.payment.repository;

import com.miriyum.domain.payment.entity.Payment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentMonitoringSnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    public PaymentMonitoringSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Snapshot> findChangedCases(
            LocalDateTime changedFrom,
            LocalDateTime changedTo,
            LocalDateTime asOf,
            Long storeId,
            String statusesCsv,
            LocalDateTime afterChangedAt,
            String afterCaseId,
            int limit
    ) {
        return jdbcTemplate.query("""
                WITH ranked AS (
                    SELECT s.payment_id,
                           s.source_type,
                           s.source_reference_id,
                           s.case_type,
                           s.case_reference_id,
                           s.store_id,
                           s.source_status,
                           s.status_version,
                           (SELECT MIN(h.captured_at)
                              FROM payment_monitoring_snapshots h
                             WHERE h.payment_pk = s.payment_pk) AS history_available_from,
                           s.status_changed_at,
                           s.amount_minor,
                           s.refunded_amount_minor,
                           s.currency,
                           CASE
                               WHEN s.case_type = 'RESERVATION_HOLD'
                                   THEN CONCAT('reservation-hold:', s.case_reference_id)
                               WHEN s.case_type = 'RESERVATION'
                                   THEN CONCAT('reservation:', s.case_reference_id)
                               WHEN s.case_type = 'WAITING'
                                   THEN CONCAT('waiting:', s.case_reference_id)
                           END AS case_id,
                           ROW_NUMBER() OVER (
                               PARTITION BY s.case_type, s.case_reference_id
                               ORDER BY s.status_changed_at DESC,
                                        s.payment_monitoring_snapshot_id DESC
                           ) AS rn
                      FROM payment_monitoring_snapshots s
                     WHERE s.status_changed_at BETWEEN ? AND ?
                       AND s.status_changed_at <= ?
                       AND s.captured_at <= ?
                       AND (? IS NULL OR s.store_id = ?)
                       AND (? = '' OR FIND_IN_SET(s.source_status, ?) > 0)
                       AND s.case_type IN ('RESERVATION_HOLD', 'RESERVATION', 'WAITING')
                )
                SELECT payment_id, source_type, source_reference_id,
                       case_type, case_reference_id, store_id,
                       source_status, status_version, history_available_from,
                       status_changed_at, amount_minor, refunded_amount_minor,
                       currency, case_id
                  FROM ranked
                 WHERE rn = 1
                   AND (? IS NULL
                        OR status_changed_at < ?
                        OR (status_changed_at = ? AND case_id < ?))
                 ORDER BY status_changed_at DESC, case_id DESC
                 LIMIT ?
                """, PaymentMonitoringSnapshotRepository::snapshot,
                timestamp(changedFrom), timestamp(changedTo), timestamp(asOf), timestamp(asOf),
                storeId, storeId,
                statusesCsv, statusesCsv,
                timestamp(afterChangedAt), timestamp(afterChangedAt),
                timestamp(afterChangedAt), afterCaseId,
                limit);
    }

    public List<Snapshot> findLatestCases(
            String caseType,
            String caseReferencesCsv,
            LocalDateTime asOf
    ) {
        return jdbcTemplate.query("""
                SELECT s.payment_id, s.source_type, s.source_reference_id,
                       s.case_type, s.case_reference_id, s.store_id,
                       s.source_status, s.status_version,
                       (SELECT MIN(h.captured_at)
                          FROM payment_monitoring_snapshots h
                         WHERE h.payment_pk = s.payment_pk) AS history_available_from,
                       s.status_changed_at, s.amount_minor, s.refunded_amount_minor,
                       s.currency,
                       CASE
                           WHEN s.case_type = 'RESERVATION_HOLD'
                               THEN CONCAT('reservation-hold:', s.case_reference_id)
                           WHEN s.case_type = 'RESERVATION'
                               THEN CONCAT('reservation:', s.case_reference_id)
                           WHEN s.case_type = 'WAITING'
                               THEN CONCAT('waiting:', s.case_reference_id)
                       END AS case_id
                  FROM payment_monitoring_snapshots s
                 WHERE s.case_type = ?
                   AND FIND_IN_SET(s.case_reference_id, ?) > 0
                   AND s.payment_monitoring_snapshot_id = (
                       SELECT latest.payment_monitoring_snapshot_id
                         FROM payment_monitoring_snapshots latest
                        WHERE latest.payment_pk = s.payment_pk
                          AND latest.status_changed_at <= ?
                          AND latest.captured_at <= ?
                        ORDER BY latest.status_changed_at DESC,
                                 latest.payment_monitoring_snapshot_id DESC
                        LIMIT 1
                   )
                 ORDER BY s.status_changed_at DESC, s.payment_id DESC
                """, PaymentMonitoringSnapshotRepository::snapshot,
                caseType, caseReferencesCsv, timestamp(asOf), timestamp(asOf));
    }

    public List<Existence> findExistingCases(
            String caseType,
            String caseReferencesCsv,
            LocalDateTime asOf
    ) {
        return jdbcTemplate.query("""
                SELECT monitoring_case_type, monitoring_case_reference_id, created_at
                  FROM payments
                 WHERE monitoring_case_type = ?
                   AND FIND_IN_SET(monitoring_case_reference_id, ?) > 0
                   AND created_at <= ?
                """, (resultSet, rowNumber) -> new Existence(
                        resultSet.getString("monitoring_case_type"),
                        resultSet.getString("monitoring_case_reference_id"),
                        instant(resultSet, "created_at")),
                caseType, caseReferencesCsv, timestamp(asOf));
    }

    private static Snapshot snapshot(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Snapshot(
                resultSet.getString("case_id"),
                resultSet.getString("payment_id"),
                resultSet.getString("source_type"),
                resultSet.getString("source_reference_id"),
                resultSet.getString("case_type"),
                resultSet.getString("case_reference_id"),
                resultSet.getLong("store_id"),
                Payment.Status.valueOf(resultSet.getString("source_status")),
                resultSet.getLong("status_version"),
                instant(resultSet, "history_available_from"),
                instant(resultSet, "status_changed_at"),
                resultSet.getLong("amount_minor"),
                resultSet.getLong("refunded_amount_minor"),
                resultSet.getString("currency"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        LocalDateTime value = resultSet.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    public record Snapshot(
            String caseId,
            String paymentId,
            String sourceType,
            String sourceReferenceId,
            String caseType,
            String caseReferenceId,
            long storeId,
            Payment.Status status,
            long version,
            Instant historyAvailableFrom,
            Instant statusChangedAt,
            long amountMinor,
            long refundedAmountMinor,
            String currency
    ) {
    }

    public record Existence(String caseType, String caseReferenceId, Instant createdAt) {
    }
}
