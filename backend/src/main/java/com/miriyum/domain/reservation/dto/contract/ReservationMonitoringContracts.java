package com.miriyum.domain.reservation.dto.contract;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ReservationMonitoringContracts {

    private ReservationMonitoringContracts() {
    }

    public enum Completeness { COMPLETE, DELAYED, PARTIAL, UNAVAILABLE }
    public enum ReconciliationStatus { MATCHED, REQUIRED, UNKNOWN }

    public record Seek(Instant statusChangedAt, String caseId) {
        public Seek {
            time(statusChangedAt, "statusChangedAt");
            ReservationMonitoringContracts.caseId(caseId);
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
            boundary(asOf, changedFrom, changedTo);
            if (storeId != null) positive(storeId, "storeId");
            sourceStatuses = sourceStatuses == null ? Set.of() : Set.copyOf(sourceStatuses);
            if (sourceStatuses.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("source status is required");
            }
            if (limit < 1 || limit > 100) throw new IllegalArgumentException("invalid limit");
        }
    }

    public record CaseReference(String caseId, String storeId, Instant statusChangedAt) {
        public CaseReference {
            ReservationMonitoringContracts.caseId(caseId);
            positive(storeId, "storeId");
            time(statusChangedAt, "statusChangedAt");
        }
    }

    public record ReferencePage(List<CaseReference> items, Instant asOf, Instant dataThrough) {
        public ReferencePage {
            items = items == null ? List.of() : List.copyOf(items);
            through(asOf, dataThrough);
            distinct(items.stream().map(CaseReference::caseId).toList());
        }
    }

    public record BatchQuery(Instant asOf, List<String> caseIds) {
        public BatchQuery {
            time(asOf, "asOf");
            caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
            if (caseIds.size() > 100) throw new IllegalArgumentException("too many case IDs");
            caseIds.forEach(ReservationMonitoringContracts::caseId);
            distinct(caseIds);
        }
    }

    public record LedgerCell(
            String source,
            String sourceStatus,
            long statusVersion,
            Instant statusChangedAt,
            Instant asOf,
            Instant dataThrough,
            Completeness completeness,
            ReconciliationStatus reconciliationStatus
    ) {
        public LedgerCell {
            text(source, "source");
            text(sourceStatus, "sourceStatus");
            if (statusVersion < 0) throw new IllegalArgumentException("negative status version");
            time(statusChangedAt, "statusChangedAt");
            through(asOf, dataThrough);
            if (statusChangedAt.isAfter(dataThrough)
                    || completeness == null || reconciliationStatus == null) {
                throw new IllegalArgumentException("invalid ledger metadata");
            }
            if (completeness == Completeness.COMPLETE && !dataThrough.equals(asOf)) {
                throw new IllegalArgumentException("complete ledger must reach asOf");
            }
            if (completeness == Completeness.DELAYED && !dataThrough.isBefore(asOf)) {
                throw new IllegalArgumentException("delayed ledger must precede asOf");
            }
        }
    }

    public record CaseSnapshot(
            String caseId,
            String storeId,
            int partySize,
            Instant scheduledStartAt,
            Instant scheduledEndAt,
            List<LedgerCell> ledgers
    ) {
        public CaseSnapshot {
            ReservationMonitoringContracts.caseId(caseId);
            positive(storeId, "storeId");
            if (partySize < 1 || scheduledStartAt == null || scheduledEndAt == null
                    || !scheduledStartAt.isBefore(scheduledEndAt)) {
                throw new IllegalArgumentException("invalid reservation schedule");
            }
            ledgers = ledgers == null ? List.of() : List.copyOf(ledgers);
            if (ledgers.isEmpty()
                    || new HashSet<>(ledgers.stream().map(LedgerCell::source).toList()).size()
                    != ledgers.size()) {
                throw new IllegalArgumentException("ledger sources must be non-empty and unique");
            }
        }
    }

    public record BatchResult(List<CaseSnapshot> cases, Instant asOf, Instant dataThrough) {
        public BatchResult {
            cases = cases == null ? List.of() : List.copyOf(cases);
            through(asOf, dataThrough);
            distinct(cases.stream().map(CaseSnapshot::caseId).toList());
            if (cases.stream().flatMap(value -> value.ledgers().stream())
                    .anyMatch(cell -> !cell.asOf().equals(asOf))) {
                throw new IllegalArgumentException("case ledgers must share asOf");
            }
        }
    }

    public record DetailQuery(Instant asOf, String caseId) {
        public DetailQuery {
            time(asOf, "asOf");
            ReservationMonitoringContracts.caseId(caseId);
        }
    }

    public record Event(
            String source,
            String eventType,
            long resultVersion,
            String beforeStatus,
            String afterStatus,
            Instant occurredAt
    ) {
        public Event {
            text(source, "source");
            text(eventType, "eventType");
            if (resultVersion < 0) throw new IllegalArgumentException("negative result version");
            if (beforeStatus != null) text(beforeStatus, "beforeStatus");
            text(afterStatus, "afterStatus");
            time(occurredAt, "occurredAt");
        }
    }

    public record Detail(CaseSnapshot snapshot, List<Event> events) {
        public Detail {
            if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
            events = events == null ? List.of() : List.copyOf(events);
            Instant previous = null;
            for (Event event : events) {
                if (previous != null && event.occurredAt().isBefore(previous)) {
                    throw new IllegalArgumentException("events must be chronologically ordered");
                }
                previous = event.occurredAt();
            }
        }
    }

    private static void boundary(Instant asOf, Instant from, Instant to) {
        time(asOf, "asOf"); time(from, "changedFrom"); time(to, "changedTo");
        if (from.isAfter(to) || to.isAfter(asOf)) throw new IllegalArgumentException("invalid range");
    }

    private static void through(Instant asOf, Instant dataThrough) {
        time(asOf, "asOf"); time(dataThrough, "dataThrough");
        if (dataThrough.isAfter(asOf)) throw new IllegalArgumentException("dataThrough exceeds asOf");
    }

    private static void caseId(String value) {
        text(value, "caseId");
        if (!value.matches("reservation(?:-hold)?:[1-9][0-9]*")) {
            throw new IllegalArgumentException("invalid reservation case ID");
        }
    }

    private static void positive(String value, String field) {
        text(value, field);
        if (!value.matches("[1-9][0-9]*")) throw new IllegalArgumentException("invalid " + field);
    }

    private static void distinct(List<String> values) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException("duplicate case IDs");
        }
    }

    private static void text(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    private static void time(Instant value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
    }
}
