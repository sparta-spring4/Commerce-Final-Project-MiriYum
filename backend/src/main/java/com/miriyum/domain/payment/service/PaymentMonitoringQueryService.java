package com.miriyum.domain.payment.service;

import com.miriyum.domain.payment.dto.PaymentMonitoringContracts;
import com.miriyum.domain.payment.entity.Payment;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.domain.payment.repository.PaymentLedgerEntryRepository;
import com.miriyum.domain.payment.repository.PaymentMonitoringSnapshotRepository;
import com.miriyum.domain.payment.repository.PaymentRefundRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PaymentMonitoringQueryService {

    private static final int MAX_DETAIL_ROWS = 100;
    private static final String RESERVATION_DEPOSIT = "RESERVATION_DEPOSIT";
    private static final String WAITING_RESERVATION_DEPOSIT = "WAITING_RESERVATION_DEPOSIT";

    private final PaymentMonitoringSnapshotRepository snapshotRepository;
    private final PaymentLedgerEntryRepository ledgerRepository;
    private final PaymentRefundRepository refundRepository;

    public PaymentMonitoringQueryService(
            PaymentMonitoringSnapshotRepository snapshotRepository,
            PaymentLedgerEntryRepository ledgerRepository,
            PaymentRefundRepository refundRepository
    ) {
        this.snapshotRepository = snapshotRepository;
        this.ledgerRepository = ledgerRepository;
        this.refundRepository = refundRepository;
    }

    public PaymentMonitoringContracts.ReferencePage findChangedCases(
            PaymentMonitoringContracts.ChangeQuery query
    ) {
        return sourceRead(() -> doFindChangedCases(query));
    }

    private PaymentMonitoringContracts.ReferencePage doFindChangedCases(
            PaymentMonitoringContracts.ChangeQuery query
    ) {
        Long storeId = query.storeId() == null ? null : Long.valueOf(query.storeId());
        LocalDateTime afterChangedAt = query.after() == null
                ? null : utc(query.after().statusChangedAt());
        String afterCaseId = query.after() == null ? null : query.after().caseId();
        List<PaymentMonitoringSnapshotRepository.Snapshot> snapshots =
                snapshotRepository.findChangedCases(
                        utc(query.changedFrom()), utc(query.changedTo()), utc(query.asOf()),
                        storeId, String.join(",", query.sourceStatuses()),
                        afterChangedAt, afterCaseId, query.limit());
        List<PaymentMonitoringContracts.CaseReference> items = snapshots.stream()
                .map(snapshot -> new PaymentMonitoringContracts.CaseReference(
                        snapshot.caseId(), snapshot.statusChangedAt()))
                .toList();
        return new PaymentMonitoringContracts.ReferencePage(items, query.asOf(), query.asOf());
    }

    public PaymentMonitoringContracts.BatchResult findCases(
            PaymentMonitoringContracts.BatchQuery query
    ) {
        return sourceRead(() -> doFindCases(query));
    }

    private PaymentMonitoringContracts.BatchResult doFindCases(
            PaymentMonitoringContracts.BatchQuery query
    ) {
        ParsedCases parsed = parse(query.caseIds());
        List<PaymentMonitoringSnapshotRepository.Snapshot> snapshots = new ArrayList<>();
        if (!parsed.reservationReferences().isEmpty()) {
            snapshots.addAll(snapshotRepository.findLatestCases(
                    RESERVATION_DEPOSIT, String.join(",", parsed.reservationReferences()),
                    utc(query.asOf())));
        }
        if (!parsed.waitingReferences().isEmpty()) {
            snapshots.addAll(snapshotRepository.findLatestCases(
                    WAITING_RESERVATION_DEPOSIT, String.join(",", parsed.waitingReferences()),
                    utc(query.asOf())));
        }
        Map<String, PaymentMonitoringSnapshotRepository.Snapshot> latest = snapshots.stream()
                .collect(Collectors.toMap(
                        PaymentMonitoringSnapshotRepository.Snapshot::caseId,
                        Function.identity(),
                        (left, right) -> left.statusChangedAt().isAfter(right.statusChangedAt())
                                ? left : right,
                        LinkedHashMap::new));
        Map<String, PaymentMonitoringSnapshotRepository.Existence> existing = new HashMap<>();
        if (!parsed.reservationReferences().isEmpty()) {
            snapshotRepository.findExistingCases(
                            RESERVATION_DEPOSIT,
                            String.join(",", parsed.reservationReferences()), utc(query.asOf()))
                    .forEach(value -> existing.put(caseId(value), value));
        }
        if (!parsed.waitingReferences().isEmpty()) {
            snapshotRepository.findExistingCases(
                            WAITING_RESERVATION_DEPOSIT,
                            String.join(",", parsed.waitingReferences()), utc(query.asOf()))
                    .forEach(value -> existing.put(caseId(value), value));
        }

        List<PaymentMonitoringContracts.SourceCell> cells = new ArrayList<>();
        for (String requested : query.caseIds()) {
            PaymentMonitoringSnapshotRepository.Snapshot snapshot = latest.get(requested);
            if (snapshot != null) {
                cells.add(cell(snapshot, query.asOf()));
            } else if (existing.containsKey(requested)) {
                cells.add(new PaymentMonitoringContracts.SourceCell(
                        requested, null, query.asOf(), query.asOf(),
                        PaymentMonitoringContracts.Completeness.UNAVAILABLE,
                        PaymentMonitoringContracts.ReconciliationStatus.UNKNOWN,
                        null));
            }
        }
        return new PaymentMonitoringContracts.BatchResult(cells, query.asOf(), query.asOf());
    }

    public Optional<PaymentMonitoringContracts.Detail> findCase(
            PaymentMonitoringContracts.DetailQuery query
    ) {
        return sourceRead(() -> doFindCase(query));
    }

    private Optional<PaymentMonitoringContracts.Detail> doFindCase(
            PaymentMonitoringContracts.DetailQuery query
    ) {
        PaymentMonitoringContracts.BatchResult batch = findCases(
                new PaymentMonitoringContracts.BatchQuery(query.asOf(), List.of(query.caseId())));
        if (batch.cells().isEmpty()) return Optional.empty();
        PaymentMonitoringContracts.SourceCell cell = batch.cells().getFirst();
        if (cell.state() == null) {
            return Optional.of(new PaymentMonitoringContracts.Detail(
                    cell, List.of(), false, List.of(), false));
        }
        List<PaymentLedgerEntryRepository.MonitoringEvent> ledgerRows =
                ledgerRepository.findMonitoringEvents(
                        cell.state().paymentId(),
                        query.asOf(),
                        Pageable.ofSize(MAX_DETAIL_ROWS + 1));
        boolean ledgerTruncated = ledgerRows.size() > MAX_DETAIL_ROWS;
        List<PaymentMonitoringContracts.LedgerEvent> ledger =
                ledgerRows.stream().limit(MAX_DETAIL_ROWS)
                        .map(event -> new PaymentMonitoringContracts.LedgerEvent(
                                event.getType().name(), event.getAmountMinor(), event.getOccurredAt()))
                        .toList();
        List<PaymentRefundRepository.MonitoringRefund> refundRows =
                refundRepository.findMonitoringRefunds(
                        cell.state().paymentId(),
                        query.asOf(),
                        Pageable.ofSize(MAX_DETAIL_ROWS + 1));
        boolean refundsTruncated = refundRows.size() > MAX_DETAIL_ROWS;
        List<PaymentMonitoringContracts.Refund> refunds =
                refundRows.stream().limit(MAX_DETAIL_ROWS)
                        .map(refund -> new PaymentMonitoringContracts.Refund(
                                refund.getStatus().name(), refund.getVersion(),
                                refund.getAmountMinor(), refund.getRequestedAt(),
                                refund.getCompletedAt()))
                        .toList();
        return Optional.of(new PaymentMonitoringContracts.Detail(
                cell, ledger, ledgerTruncated, refunds, refundsTruncated));
    }

    private static PaymentMonitoringContracts.SourceCell cell(
            PaymentMonitoringSnapshotRepository.Snapshot snapshot,
            Instant asOf
    ) {
        return new PaymentMonitoringContracts.SourceCell(
                snapshot.caseId(),
                new PaymentMonitoringContracts.ConfirmedState(
                        snapshot.paymentId(), snapshot.status().name(),
                        snapshot.version(), snapshot.statusChangedAt(),
                        snapshot.amountMinor(), snapshot.refundedAmountMinor(),
                        snapshot.currency()),
                asOf, asOf,
                PaymentMonitoringContracts.Completeness.COMPLETE,
                reconciliation(snapshot.status()), snapshot.historyAvailableFrom());
    }

    private static PaymentMonitoringContracts.ReconciliationStatus reconciliation(
            Payment.Status status
    ) {
        return status == Payment.Status.RECONCILIATION_REQUIRED
                ? PaymentMonitoringContracts.ReconciliationStatus.REQUIRED
                : PaymentMonitoringContracts.ReconciliationStatus.MATCHED;
    }

    private static String caseId(PaymentMonitoringSnapshotRepository.Existence existence) {
        return switch (existence.sourceType()) {
            case RESERVATION_DEPOSIT -> "reservation-hold:" + existence.sourceReferenceId();
            case WAITING_RESERVATION_DEPOSIT -> "waiting:" + existence.sourceReferenceId();
            default -> throw new IllegalArgumentException("unsupported payment source type");
        };
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

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static <T> T sourceRead(Supplier<T> read) {
        try {
            return read.get();
        } catch (DataAccessException failure) {
            com.miriyum.global.exception.ServiceException unavailable =
                    new com.miriyum.global.exception.ServiceException(
                            PaymentErrorCode.MONITORING_SOURCE_UNAVAILABLE);
            unavailable.addSuppressed(failure);
            throw unavailable;
        }
    }

    private record ParsedCases(
            List<String> reservationReferences,
            List<String> waitingReferences
    ) {
    }
}
