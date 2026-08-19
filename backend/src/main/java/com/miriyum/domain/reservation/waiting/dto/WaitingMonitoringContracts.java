package com.miriyum.domain.reservation.waiting.dto;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Waiting이 플랫폼 모니터링 계층에 공개하는 최소 조회 계약이다. */
public final class WaitingMonitoringContracts {

    private WaitingMonitoringContracts() {
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

    public record CaseReference(String caseId, String storeId, Instant statusChangedAt) {
        public CaseReference {
            requireCaseId(caseId);
            requirePositiveId(storeId, "storeId");
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
            caseIds.forEach(WaitingMonitoringContracts::requireCaseId);
            requireDistinct(caseIds);
        }
    }

    public record Links(String paymentId, String reservationId) {
        public Links {
            if (paymentId != null) requirePositiveId(paymentId, "paymentId");
            if (reservationId != null) requirePositiveId(reservationId, "reservationId");
        }
    }

    public record SourceCell(
            String caseId,
            String storeId,
            String sourceStatus,
            long statusVersion,
            Instant statusChangedAt,
            Instant asOf,
            Instant dataThrough,
            Completeness completeness,
            ReconciliationStatus reconciliationStatus,
            Instant historyAvailableFrom,
            int partySize,
            long queueSequence,
            Links links
    ) {
        public SourceCell {
            requireCaseId(caseId);
            requirePositiveId(storeId, "storeId");
            requireText(sourceStatus, "sourceStatus");
            if (statusVersion < 0 || partySize < 1 || queueSequence < 1) {
                throw new IllegalArgumentException("invalid version, party size, or queue sequence");
            }
            requireInstant(statusChangedAt, "statusChangedAt");
            validateBoundary(asOf, dataThrough);
            if (statusChangedAt.isAfter(dataThrough)) {
                throw new IllegalArgumentException("statusChangedAt must not exceed dataThrough");
            }
            if (completeness == null || reconciliationStatus == null || links == null) {
                throw new IllegalArgumentException("source metadata is required");
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
            requireDistinct(cells.stream().map(SourceCell::caseId).toList());
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

    public record Transition(
            long resultVersion,
            String beforeStatus,
            String afterStatus,
            Instant occurredAt
    ) {
        public Transition {
            if (resultVersion < 0) throw new IllegalArgumentException("negative version");
            if (beforeStatus != null) requireText(beforeStatus, "beforeStatus");
            requireText(afterStatus, "afterStatus");
            requireInstant(occurredAt, "occurredAt");
        }
    }

    public record Detail(SourceCell cell, List<Transition> history) {
        public Detail {
            if (cell == null) throw new IllegalArgumentException("cell is required");
            history = history == null ? List.of() : List.copyOf(history);
            long previous = -1;
            for (Transition transition : history) {
                if (transition.resultVersion() <= previous
                        || transition.occurredAt().isAfter(cell.asOf())) {
                    throw new IllegalArgumentException("history must be ordered within asOf");
                }
                previous = transition.resultVersion();
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
        if (!value.matches("waiting:[1-9][0-9]*")) {
            throw new IllegalArgumentException("invalid waiting case ID");
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
