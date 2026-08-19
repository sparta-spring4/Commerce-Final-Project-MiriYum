package com.miriyum.domain.payment.service;

import com.miriyum.domain.payment.dto.PaymentMonitoringContracts;
import com.miriyum.domain.payment.entity.Payment;
import com.miriyum.domain.payment.repository.PaymentLedgerEntryRepository;
import com.miriyum.domain.payment.repository.PaymentRefundRepository;
import com.miriyum.domain.payment.repository.PaymentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PaymentMonitoringQueryService {

    private static final int SOURCE_FETCH_LIMIT = 100;
    private static final String RESERVATION_DEPOSIT = "RESERVATION_DEPOSIT";
    private static final String WAITING_RESERVATION_DEPOSIT = "WAITING_RESERVATION_DEPOSIT";

    private final PaymentRepository paymentRepository;
    private final PaymentLedgerEntryRepository ledgerRepository;
    private final PaymentRefundRepository refundRepository;

    public PaymentMonitoringQueryService(
            PaymentRepository paymentRepository,
            PaymentLedgerEntryRepository ledgerRepository,
            PaymentRefundRepository refundRepository
    ) {
        this.paymentRepository = paymentRepository;
        this.ledgerRepository = ledgerRepository;
        this.refundRepository = refundRepository;
    }

    public PaymentMonitoringContracts.ReferencePage findChangedCases(
            PaymentMonitoringContracts.ChangeQuery query
    ) {
        Map<String, PaymentMonitoringContracts.CaseReference> byCase = new HashMap<>();
        for (PaymentRepository.MonitoringSnapshot snapshot :
                paymentRepository.findMonitoringChanges(
                        query.changedFrom(), query.changedTo(), Pageable.ofSize(SOURCE_FETCH_LIMIT))) {
            String caseId = caseId(snapshot);
            if (caseId == null || snapshot.getUpdatedAt().isAfter(query.asOf())
                    || (!query.sourceStatuses().isEmpty()
                    && !query.sourceStatuses().contains(snapshot.getStatus().name()))) {
                continue;
            }
            var candidate = new PaymentMonitoringContracts.CaseReference(
                    caseId, snapshot.getUpdatedAt());
            byCase.merge(caseId, candidate,
                    (left, right) -> left.statusChangedAt().isAfter(right.statusChangedAt())
                            ? left : right);
        }
        List<PaymentMonitoringContracts.CaseReference> items = byCase.values().stream()
                .filter(reference -> afterSeek(reference, query.after()))
                .sorted(Comparator.comparing(
                                PaymentMonitoringContracts.CaseReference::statusChangedAt)
                        .reversed()
                        .thenComparing(PaymentMonitoringContracts.CaseReference::caseId,
                                Comparator.reverseOrder()))
                .limit(query.limit())
                .toList();
        return new PaymentMonitoringContracts.ReferencePage(items, query.asOf(), query.asOf());
    }

    public PaymentMonitoringContracts.BatchResult findCases(
            PaymentMonitoringContracts.BatchQuery query
    ) {
        ParsedCases parsed = parse(query.caseIds());
        List<PaymentRepository.MonitoringSnapshot> snapshots =
                paymentRepository.findMonitoringSnapshots(
                        parsed.reservationReferences(), parsed.waitingReferences());
        Map<String, PaymentRepository.MonitoringSnapshot> latest = snapshots.stream()
                .filter(snapshot -> caseId(snapshot) != null
                        && !snapshot.getCreatedAt().isAfter(query.asOf()))
                .collect(Collectors.toMap(
                        PaymentMonitoringQueryService::caseId,
                        Function.identity(),
                        (left, right) -> left.getUpdatedAt().isAfter(right.getUpdatedAt())
                                ? left : right,
                        LinkedHashMap::new));
        List<String> futurePaymentIds = latest.values().stream()
                .filter(snapshot -> snapshot.getUpdatedAt().isAfter(query.asOf()))
                .map(PaymentRepository.MonitoringSnapshot::getPaymentId)
                .toList();
        Map<String, List<PaymentLedgerEntryRepository.MonitoringEvent>> futureHistory =
                events(futurePaymentIds);

        List<PaymentMonitoringContracts.SourceCell> cells = new ArrayList<>();
        for (String requested : query.caseIds()) {
            PaymentRepository.MonitoringSnapshot snapshot = latest.get(requested);
            if (snapshot == null) continue;
            cells.add(cell(snapshot,
                    futureHistory.getOrDefault(snapshot.getPaymentId(), List.of()), query.asOf()));
        }
        return new PaymentMonitoringContracts.BatchResult(cells, query.asOf(), query.asOf());
    }

    public Optional<PaymentMonitoringContracts.Detail> findCase(
            PaymentMonitoringContracts.DetailQuery query
    ) {
        PaymentMonitoringContracts.BatchResult batch = findCases(
                new PaymentMonitoringContracts.BatchQuery(query.asOf(), List.of(query.caseId())));
        if (batch.cells().isEmpty()) return Optional.empty();
        PaymentMonitoringContracts.SourceCell cell = batch.cells().getFirst();
        List<PaymentMonitoringContracts.LedgerEvent> ledger =
                ledgerRepository.findMonitoringEvents(List.of(cell.paymentId())).stream()
                        .filter(event -> !event.getOccurredAt().isAfter(query.asOf()))
                        .map(event -> new PaymentMonitoringContracts.LedgerEvent(
                                event.getType().name(), event.getAmountMinor(), event.getOccurredAt()))
                        .toList();
        List<PaymentMonitoringContracts.Refund> refunds =
                refundRepository.findMonitoringRefunds(List.of(cell.paymentId())).stream()
                        .filter(refund -> !refund.getUpdatedAt().isAfter(query.asOf()))
                        .map(refund -> new PaymentMonitoringContracts.Refund(
                                refund.getStatus().name(), refund.getVersion(),
                                refund.getAmountMinor(), refund.getRequestedAt(),
                                refund.getCompletedAt()))
                        .toList();
        return Optional.of(new PaymentMonitoringContracts.Detail(cell, ledger, refunds));
    }

    private static PaymentMonitoringContracts.SourceCell cell(
            PaymentRepository.MonitoringSnapshot snapshot,
            List<PaymentLedgerEntryRepository.MonitoringEvent> history,
            Instant asOf
    ) {
        if (snapshot.getUpdatedAt().isAfter(asOf)) {
            List<PaymentLedgerEntryRepository.MonitoringEvent> available = history.stream()
                    .filter(event -> !event.getOccurredAt().isAfter(asOf)).toList();
            Instant changedAt = available.isEmpty()
                    ? snapshot.getCreatedAt() : available.getLast().getOccurredAt();
            Instant historyFrom = available.isEmpty()
                    ? snapshot.getCreatedAt() : available.getFirst().getOccurredAt();
            return new PaymentMonitoringContracts.SourceCell(
                    caseId(snapshot), snapshot.getPaymentId(), "UNAVAILABLE", 0,
                    changedAt, asOf, asOf,
                    PaymentMonitoringContracts.Completeness.UNAVAILABLE,
                    PaymentMonitoringContracts.ReconciliationStatus.UNKNOWN,
                    historyFrom, snapshot.getAmountMinor(), 0, snapshot.getCurrency());
        }
        return new PaymentMonitoringContracts.SourceCell(
                caseId(snapshot), snapshot.getPaymentId(), snapshot.getStatus().name(),
                snapshot.getVersion(), snapshot.getUpdatedAt(), asOf, asOf,
                PaymentMonitoringContracts.Completeness.COMPLETE,
                reconciliation(snapshot.getStatus()), snapshot.getCreatedAt(),
                snapshot.getAmountMinor(), snapshot.getRefundedAmountMinor(),
                snapshot.getCurrency());
    }

    private Map<String, List<PaymentLedgerEntryRepository.MonitoringEvent>> events(
            List<String> paymentIds
    ) {
        if (paymentIds.isEmpty()) return Map.of();
        return ledgerRepository.findMonitoringEvents(paymentIds).stream()
                .collect(Collectors.groupingBy(
                        PaymentLedgerEntryRepository.MonitoringEvent::getPaymentId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    private static PaymentMonitoringContracts.ReconciliationStatus reconciliation(
            Payment.Status status
    ) {
        return status == Payment.Status.RECONCILIATION_REQUIRED
                ? PaymentMonitoringContracts.ReconciliationStatus.REQUIRED
                : PaymentMonitoringContracts.ReconciliationStatus.MATCHED;
    }

    private static String caseId(PaymentRepository.MonitoringSnapshot snapshot) {
        return switch (snapshot.getSourceType()) {
            case RESERVATION_DEPOSIT -> "reservation-hold:" + snapshot.getSourceReferenceId();
            case WAITING_RESERVATION_DEPOSIT -> "waiting:" + snapshot.getSourceReferenceId();
            default -> null;
        };
    }

    private static boolean afterSeek(
            PaymentMonitoringContracts.CaseReference reference,
            PaymentMonitoringContracts.Seek seek
    ) {
        return seek == null
                || reference.statusChangedAt().isBefore(seek.statusChangedAt())
                || (reference.statusChangedAt().equals(seek.statusChangedAt())
                && reference.caseId().compareTo(seek.caseId()) < 0);
    }

    private static ParsedCases parse(List<String> caseIds) {
        return new ParsedCases(
                caseIds.stream()
                        .filter(value -> value.startsWith("reservation-hold:"))
                        .map(PaymentMonitoringQueryService::reference).toList(),
                caseIds.stream()
                        .filter(value -> value.startsWith("waiting:"))
                        .map(PaymentMonitoringQueryService::reference).toList());
    }

    private static String reference(String caseId) {
        return caseId.substring(caseId.indexOf(':') + 1);
    }

    private record ParsedCases(
            List<String> reservationReferences,
            List<String> waitingReferences
    ) {
    }
}
