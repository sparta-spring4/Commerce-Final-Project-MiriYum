package com.miriyum.domain.menuhold.dto;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MenuHoldMonitoringContracts {

    private static final Duration MAX_CHANGE_RANGE = Duration.ofDays(31);

    private MenuHoldMonitoringContracts() {
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
            requireText(caseId, "caseId");
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
            if (storeId != null) {
                requirePositiveId(storeId, "storeId");
            }
            sourceStatuses = sourceStatuses == null ? Set.of() : Set.copyOf(sourceStatuses);
            if (sourceStatuses.stream().anyMatch(status -> status == null || status.isBlank())) {
                throw new IllegalArgumentException("sourceStatuses contain blank status");
            }
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
        }
    }

    public record CaseReference(String caseId, String storeId, Instant statusChangedAt) {
        public CaseReference {
            requireReservationCaseId(caseId);
            requirePositiveId(storeId, "storeId");
            requireInstant(statusChangedAt, "statusChangedAt");
        }
    }

    public record ReferencePage(
            List<CaseReference> items,
            Instant asOf,
            Instant dataThrough
    ) {
        public ReferencePage {
            items = items == null ? List.of() : List.copyOf(items);
            validateTimeBoundary(asOf, dataThrough);
            requireDistinctCaseIds(items.stream().map(CaseReference::caseId).toList());
        }
    }

    public record BatchQuery(Instant asOf, List<String> caseIds) {
        public BatchQuery {
            requireInstant(asOf, "asOf");
            caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
            if (caseIds.size() > 100) {
                throw new IllegalArgumentException("at most 100 case IDs are allowed");
            }
            caseIds.forEach(MenuHoldMonitoringContracts::requireReservationCaseId);
            requireDistinctCaseIds(caseIds);
        }
    }

    public record Links(String reservationId, String reservationHoldId) {
        public Links {
            if (reservationId == null && reservationHoldId == null) {
                throw new IllegalArgumentException("at least one reservation link is required");
            }
            if (reservationId != null) {
                requirePositiveId(reservationId, "reservationId");
            }
            if (reservationHoldId != null) {
                requirePositiveId(reservationHoldId, "reservationHoldId");
            }
        }
    }

    public record ConfirmedState(
            String sourceStatus,
            long statusVersion,
            Instant statusChangedAt
    ) {
        public ConfirmedState {
            requireText(sourceStatus, "sourceStatus");
            if (statusVersion < 0) {
                throw new IllegalArgumentException("statusVersion must not be negative");
            }
            requireInstant(statusChangedAt, "statusChangedAt");
        }
    }

    public record SourceCell(
            String caseId,
            String storeId,
            ConfirmedState state,
            Instant asOf,
            Instant dataThrough,
            Completeness completeness,
            ReconciliationStatus reconciliationStatus,
            Instant historyAvailableFrom,
            Links links
    ) {
        public SourceCell {
            requireReservationCaseId(caseId);
            if (storeId != null) {
                requirePositiveId(storeId, "storeId");
            }
            validateTimeBoundary(asOf, dataThrough);
            if (state != null && state.statusChangedAt().isAfter(dataThrough)) {
                throw new IllegalArgumentException("statusChangedAt must not exceed dataThrough");
            }
            if (completeness == null || reconciliationStatus == null) {
                throw new IllegalArgumentException("source metadata is required");
            }
            if (completeness == Completeness.UNAVAILABLE) {
                if (state != null || storeId != null || links != null) {
                    throw new IllegalArgumentException(
                            "unavailable cells must not expose unconfirmed source data");
                }
            } else if (state == null || storeId == null || links == null) {
                throw new IllegalArgumentException("available cells require confirmed source data");
            }
            if (completeness == Completeness.COMPLETE && !dataThrough.equals(asOf)) {
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

    public record BatchResult(
            List<SourceCell> cells,
            Instant asOf,
            Instant dataThrough
    ) {
        public BatchResult {
            cells = cells == null ? List.of() : List.copyOf(cells);
            validateTimeBoundary(asOf, dataThrough);
            requireDistinctCaseIds(cells.stream().map(SourceCell::caseId).toList());
            if (cells.stream().anyMatch(cell -> !cell.asOf().equals(asOf))) {
                throw new IllegalArgumentException("all cells must use the batch asOf");
            }
        }
    }

    public record DetailQuery(Instant asOf, String caseId) {
        public DetailQuery {
            requireInstant(asOf, "asOf");
            requireReservationCaseId(caseId);
        }
    }

    public record Transition(
            long resultVersion,
            String beforeStatus,
            String afterStatus,
            Instant occurredAt
    ) {
        public Transition {
            if (resultVersion < 0) {
                throw new IllegalArgumentException("resultVersion must not be negative");
            }
            if (beforeStatus != null) {
                requireText(beforeStatus, "beforeStatus");
            }
            requireText(afterStatus, "afterStatus");
            requireInstant(occurredAt, "occurredAt");
        }
    }

    public record Item(String menuId, String displayName, int quantity) {
        public Item {
            requirePositiveId(menuId, "menuId");
            requireText(displayName, "displayName");
            if (quantity < 1) {
                throw new IllegalArgumentException("quantity must be positive");
            }
        }
    }

    public record Detail(SourceCell cell, List<Transition> history, List<Item> items) {
        public Detail {
            if (cell == null) {
                throw new IllegalArgumentException("cell is required");
            }
            history = history == null ? List.of() : List.copyOf(history);
            items = items == null ? List.of() : List.copyOf(items);
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

    private static void validateTimeBoundary(Instant asOf, Instant dataThrough) {
        requireInstant(asOf, "asOf");
        requireInstant(dataThrough, "dataThrough");
        if (dataThrough.isAfter(asOf)) {
            throw new IllegalArgumentException("dataThrough must not exceed asOf");
        }
    }

    private static void requireReservationCaseId(String caseId) {
        requireText(caseId, "caseId");
        if (!caseId.matches("reservation(?:-hold)?:[1-9][0-9]*")) {
            throw new IllegalArgumentException("invalid reservation case ID");
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
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void requireDistinctCaseIds(List<String> caseIds) {
        if (new HashSet<>(caseIds).size() != caseIds.size()) {
            throw new IllegalArgumentException("duplicate case IDs are not allowed");
        }
    }
}
