package com.miriyum.domain.platformoperator.adminmonitoring.dto;

import java.time.Instant;
import java.util.List;

public final class AdminMonitoringResponses {

    private AdminMonitoringResponses() {
    }

    public enum CaseType { RESERVATION, WAITING }
    public enum LifecycleStatus { PENDING, CONFIRMED, CHECKED_IN, COMPLETED, CANCELLED, NO_SHOW }
    public enum Source { RESERVATION, RESERVATION_HOLD, MENU_HOLD, PAYMENT, WAITING }
    public enum Completeness { COMPLETE, DELAYED, PARTIAL, UNAVAILABLE }
    public enum ReconciliationStatus { MATCHED, REQUIRED, UNKNOWN }
    public enum MaskingLevel { MINIMIZED }

    public record LedgerState(
            String sourceStatus,
            long statusVersion,
            Instant statusChangedAt,
            Long amountMinor,
            Long refundedAmountMinor,
            String currency
    ) {
    }

    public record LedgerCell(
            Source source,
            LedgerState state,
            Instant asOf,
            Instant dataThrough,
            Completeness completeness,
            ReconciliationStatus reconciliationStatus,
            Instant historyAvailableFrom
    ) {
    }

    public record DependencyFailure(Source source, String errorCode, boolean retryable) {
    }

    public record LinkedLedgerSummary(
            Source source,
            boolean present,
            Completeness completeness,
            ReconciliationStatus reconciliationStatus
    ) {
    }

    public record CaseSummary(
            CaseType caseType,
            String caseId,
            String storeId,
            LifecycleStatus lifecycleStatus,
            String sourceStatus,
            Instant statusChangedAt,
            long caseVersion,
            Instant asOf,
            Instant dataThrough,
            Completeness completeness,
            ReconciliationStatus reconciliationStatus,
            List<LinkedLedgerSummary> ledgers
    ) {
        public CaseSummary {
            ledgers = immutable(ledgers);
        }
    }

    public record CasePage(
            List<CaseSummary> items,
            Instant asOf,
            Instant dataThrough,
            Completeness completeness,
            List<DependencyFailure> failures,
            String nextCursor
    ) {
        public CasePage {
            items = immutable(items);
            failures = immutable(failures);
        }
    }

    public record Transition(
            Source source,
            String eventType,
            long resultVersion,
            String beforeStatus,
            String afterStatus,
            Instant occurredAt
    ) {
    }

    public record MenuItem(String menuId, String displayName, int quantity) {
    }

    public record PaymentLedgerEvent(String eventType, long amountMinor, Instant occurredAt) {
    }

    public record PaymentRefund(
            String sourceStatus,
            long statusVersion,
            long amountMinor,
            Instant requestedAt,
            Instant completedAt
    ) {
    }

    public record CaseDetail(
            CaseType caseType,
            String caseId,
            String storeId,
            LifecycleStatus lifecycleStatus,
            long caseVersion,
            Instant asOf,
            Instant dataThrough,
            Completeness completeness,
            MaskingLevel maskingLevel,
            Integer partySize,
            Instant scheduledStartAt,
            Instant scheduledEndAt,
            Long queueSequence,
            List<LedgerCell> ledgers,
            List<Transition> history,
            List<MenuItem> menuItems,
            List<PaymentLedgerEvent> paymentLedger,
            boolean paymentLedgerTruncated,
            List<PaymentRefund> refunds,
            boolean refundsTruncated,
            List<DependencyFailure> failures
    ) {
        public CaseDetail {
            ledgers = immutable(ledgers);
            history = immutable(history);
            menuItems = immutable(menuItems);
            paymentLedger = immutable(paymentLedger);
            refunds = immutable(refunds);
            failures = immutable(failures);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
