package com.miriyum.domain.platformoperator.adminmonitoring.dto;

import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CaseType;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.LifecycleStatus;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.ReconciliationStatus;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.Source;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class AdminMonitoringRequests {

    private static final Duration MAX_CHANGE_RANGE = Duration.ofDays(31);
    private static final Map<Source, Set<String>> SOURCE_STATUSES = sourceStatuses();

    private AdminMonitoringRequests() {
    }

    public record ListQuery(
            String storeId,
            Set<CaseType> caseTypes,
            Set<LifecycleStatus> lifecycleStatuses,
            Set<String> sourceStatuses,
            Set<ReconciliationStatus> reconciliationStatuses,
            Instant changedFrom,
            Instant changedTo,
            int size,
            String cursor
    ) {
        public ListQuery {
            if (storeId != null && !storeId.matches("^[1-9][0-9]*$")) {
                throw new IllegalArgumentException("storeId must be a positive decimal ID");
            }
            caseTypes = copy(caseTypes);
            lifecycleStatuses = copy(lifecycleStatuses);
            sourceStatuses = sourceStatuses == null ? Set.of() : Set.copyOf(sourceStatuses);
            reconciliationStatuses = copy(reconciliationStatuses);
            if (changedFrom == null || changedTo == null || changedFrom.isAfter(changedTo)
                    || Duration.between(changedFrom, changedTo).compareTo(MAX_CHANGE_RANGE) > 0) {
                throw new IllegalArgumentException("changed range must be ordered and at most 31 days");
            }
            if (size < 1 || size > 100) {
                throw new IllegalArgumentException("size must be between 1 and 100");
            }
            sourceStatuses.forEach(AdminMonitoringRequests::validateSourceStatus);
            if (cursor != null && cursor.isBlank()) {
                throw new IllegalArgumentException("cursor must not be blank");
            }
        }

        public String canonicalFilter() {
            return "storeId=" + value(storeId)
                    + "\ncaseTypes=" + names(caseTypes)
                    + "\nlifecycleStatuses=" + names(lifecycleStatuses)
                    + "\nsourceStatuses=" + sourceStatuses.stream().sorted()
                    .collect(Collectors.joining(","))
                    + "\nreconciliationStatuses=" + names(reconciliationStatuses)
                    + "\nchangedFrom=" + changedFrom
                    + "\nchangedTo=" + changedTo
                    + "\nsize=" + size;
        }

        public Set<String> statusesFor(Source source) {
            String prefix = source.name() + ":";
            return sourceStatuses.stream()
                    .filter(value -> value.startsWith(prefix))
                    .map(value -> value.substring(prefix.length()))
                    .collect(Collectors.toUnmodifiableSet());
        }

        public boolean includes(CaseType caseType) {
            return caseTypes.isEmpty() || caseTypes.contains(caseType);
        }
    }

    private static void validateSourceStatus(String qualified) {
        if (qualified == null || !qualified.matches("^[A-Z_]+:[A-Z_]+$")) {
            throw new IllegalArgumentException("source status must be source-qualified");
        }
        int separator = qualified.indexOf(':');
        try {
            Source source = Source.valueOf(qualified.substring(0, separator));
            String status = qualified.substring(separator + 1);
            if (!SOURCE_STATUSES.getOrDefault(source, Set.of()).contains(status)) {
                throw new IllegalArgumentException("unknown source status");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown source status", exception);
        }
    }

    private static Map<Source, Set<String>> sourceStatuses() {
        Map<Source, Set<String>> statuses = new EnumMap<>(Source.class);
        statuses.put(Source.RESERVATION, Set.of("CONFIRMED", "CANCELLED", "FULFILLED", "NO_SHOW"));
        statuses.put(Source.RESERVATION_HOLD,
                Set.of("ACTIVE", "RECONCILIATION_REQUIRED", "CONFIRMED", "RELEASED", "EXPIRED"));
        statuses.put(Source.MENU_HOLD, Set.of(
                "ACTIVE", "RECONCILIATION_REQUIRED", "CONFIRMED", "RELEASED",
                "FULFILLED", "FORFEITED", "EXPIRED"));
        statuses.put(Source.PAYMENT, Set.of(
                "READY", "CONFIRMING", "PAID", "PARTIALLY_REFUNDED",
                "REFUNDED", "RECONCILIATION_REQUIRED"));
        statuses.put(Source.WAITING, Set.of(
                "WAITING", "CALLED", "ARRIVED", "CHECKED_IN", "CANCELLED", "NO_SHOW",
                "CLOSED_BY_STORE", "RESERVATION_CONVERTING", "RESERVATION_CONVERTED"));
        return Map.copyOf(statuses);
    }

    private static <E extends Enum<E>> Set<E> copy(Set<E> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static String names(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(","));
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
