package com.miriyum.domain.payment.dto;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Payment가 플랫폼 모니터링 계층에 공개하는 비밀값 없는 조회 계약이다. */
public final class PaymentMonitoringContracts {

    private static final Duration MAX_CHANGE_RANGE = Duration.ofDays(31);

    private PaymentMonitoringContracts() {
    }

    public enum Completeness {
        COMPLETE,
        DELAYED,
        PARTIAL,
        UNAVAILABLE
    }

    public enum ReconciliationStatus {
        MATCHED,
        REQUIRED,
        UNKNOWN
    }

    public record Seek(Instant statusChangedAt, String caseId) {
        public Seek {
            requireInstant(statusChangedAt, "statusChangedAt");
            requireCaseId(caseId);
        }
    }

    public record ChangeQuery(
            Instant asOf,
            Instant changedFrom,
            Instant changedTo,
            String storeId,
            Set<String> sourceStatuses,
            Seek after,
            int limit
    ) {
        public ChangeQuery {
            requireInstant(asOf, "asOf");
            requireInstant(changedFrom, "changedFrom");
            requireInstant(changedTo, "changedTo");
            if (changedFrom.isAfter(changedTo) || changedTo.isAfter(asOf)) {
                throw new IllegalArgumentException("invalid change range");
            }
            if (Duration.between(changedFrom, changedTo).compareTo(MAX_CHANGE_RANGE) > 0) {
                throw new IllegalArgumentException("change range exceeds 31 days");
            }
            if (storeId != null) requirePositiveId(storeId, "storeId");
            sourceStatuses = sourceStatuses == null ? Set.of() : Set.copyOf(sourceStatuses);
            if (sourceStatuses.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("sourceStatuses contain blank status");
            }
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
        }
    }

    public record CaseReference(String caseId, Instant statusChangedAt) {
        public CaseReference {
            requireCaseId(caseId);
            requireInstant(statusChangedAt, "statusChangedAt");
        }
    }

    public record ReferencePage(List<CaseReference> items, Instant asOf, Instant dataThrough) {
        public ReferencePage {
            items = items == null ? List.of() : List.copyOf(items);
            validateBoundary(asOf, dataThrough);
            requireDistinct(items.stream().map(CaseReference::caseId).toList());
        }
    }

    public record BatchQuery(Instant asOf, List<String> caseIds) {
        public BatchQuery {
            requireInstant(asOf, "asOf");
            caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
            if (caseIds.size() > 100) {
                throw new IllegalArgumentException("at most 100 case IDs are allowed");
            }
            caseIds.forEach(PaymentMonitoringContracts::requireCaseId);
            requireDistinct(caseIds);
        }
    }

    public record ConfirmedState(
            String paymentId,
            String sourceStatus,
            long statusVersion,
            Instant statusChangedAt,
            long amountMinor,
            long refundedAmountMinor,
            String currency
    ) {
        public ConfirmedState {
            requirePositiveId(paymentId, "paymentId");
            requireText(sourceStatus, "sourceStatus");
            if (statusVersion < 0 || amountMinor < 0 || refundedAmountMinor < 0
                    || refundedAmountMinor > amountMinor) {
                throw new IllegalArgumentException("invalid version or amount");
            }
            requireInstant(statusChangedAt, "statusChangedAt");
            if (currency == null || !currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("currency must be an ISO 4217 code");
            }
        }
    }

    public record SourceCell(
            String caseId,
            ConfirmedState state,
            Instant asOf,
            Instant dataThrough,
            Completeness completeness,
            ReconciliationStatus reconciliationStatus,
            Instant historyAvailableFrom
    ) {
        public SourceCell {
            requireCaseId(caseId);
            validateBoundary(asOf, dataThrough);
            if (state != null && state.statusChangedAt().isAfter(dataThrough)) {
                throw new IllegalArgumentException("statusChangedAt must not exceed dataThrough");
            }
            if (completeness == null || reconciliationStatus == null) {
                throw new IllegalArgumentException("source metadata is required");
            }
            if (completeness == Completeness.UNAVAILABLE && state != null) {
                throw new IllegalArgumentException(
                        "unavailable cells must not expose unconfirmed payment state");
            }
            if (completeness != Completeness.UNAVAILABLE && state == null) {
                throw new IllegalArgumentException("available cells require confirmed state");
            }
            if (completeness == Completeness.COMPLETE && !asOf.equals(dataThrough)) {
                throw new IllegalArgumentException("complete data must reach asOf");
            }
            if (completeness == Completeness.DELAYED && !dataThrough.isBefore(asOf)) {
                throw new IllegalArgumentException("delayed data must precede asOf");
            }
            if (completeness != Completeness.UNAVAILABLE
                    && historyAvailableFrom != null
                    && historyAvailableFrom.isAfter(dataThrough)) {
                throw new IllegalArgumentException("history boundary must not exceed dataThrough");
            }
        }
    }

    public record BatchResult(List<SourceCell> cells, Instant asOf, Instant dataThrough) {
        public BatchResult {
            cells = cells == null ? List.of() : List.copyOf(cells);
            validateBoundary(asOf, dataThrough);
            if (cells.stream().anyMatch(cell -> !cell.asOf().equals(asOf))) {
                throw new IllegalArgumentException("all cells must use the batch asOf");
            }
        }
    }

    public record DetailQuery(Instant asOf, String caseId) {
        public DetailQuery {
            requireInstant(asOf, "asOf");
            requireCaseId(caseId);
        }
    }

    public record LedgerEvent(String eventType, long amountMinor, Instant occurredAt) {
        public LedgerEvent {
            requireText(eventType, "eventType");
            if (amountMinor < 0) throw new IllegalArgumentException("amountMinor must not be negative");
            requireInstant(occurredAt, "occurredAt");
        }
    }

    public record Refund(
            String sourceStatus,
            long statusVersion,
            long amountMinor,
            Instant requestedAt,
            Instant completedAt
    ) {
        public Refund {
            requireText(sourceStatus, "sourceStatus");
            if (statusVersion < 0 || amountMinor <= 0) {
                throw new IllegalArgumentException("invalid refund version or amount");
            }
            requireInstant(requestedAt, "requestedAt");
            if (completedAt != null && completedAt.isBefore(requestedAt)) {
                throw new IllegalArgumentException("completedAt must not precede requestedAt");
            }
        }
    }

    public record Detail(
            SourceCell cell,
            List<LedgerEvent> ledger,
            boolean ledgerTruncated,
            List<Refund> refunds,
            boolean refundsTruncated
    ) {
        public Detail {
            if (cell == null) throw new IllegalArgumentException("cell is required");
            ledger = ledger == null ? List.of() : List.copyOf(ledger);
            refunds = refunds == null ? List.of() : List.copyOf(refunds);
            Instant previous = null;
            for (LedgerEvent event : ledger) {
                if ((previous != null && event.occurredAt().isBefore(previous))
                        || event.occurredAt().isAfter(cell.asOf())) {
                    throw new IllegalArgumentException("ledger must be chronological within asOf");
                }
                previous = event.occurredAt();
            }
        }
    }

    private static void validateBoundary(Instant asOf, Instant dataThrough) {
        requireInstant(asOf, "asOf");
        requireInstant(dataThrough, "dataThrough");
        if (dataThrough.isAfter(asOf)) {
            throw new IllegalArgumentException("dataThrough must not exceed asOf");
        }
    }

    private static void requireCaseId(String value) {
        requireText(value, "caseId");
        if (!value.matches("(?:reservation(?:-hold)?|waiting):[1-9][0-9]*")) {
            throw new IllegalArgumentException("invalid monitoring case ID");
        }
    }

    private static void requirePositiveId(String value, String field) {
        requireText(value, field);
        if (!value.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException(field + " must be a positive decimal ID");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void requireInstant(Instant value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
    }

    private static void requireDistinct(List<String> values) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException("duplicate case IDs are not allowed");
        }
    }
}
