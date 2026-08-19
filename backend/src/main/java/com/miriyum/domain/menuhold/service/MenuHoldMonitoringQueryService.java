package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.dto.MenuHoldMonitoringContracts;
import com.miriyum.domain.menuhold.entity.MenuHoldStatus;
import com.miriyum.domain.menuhold.entity.MenuHoldTransitionAudit;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.repository.MenuHoldTransitionAuditRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MenuHoldMonitoringQueryService {

    private final MenuHoldTransitionAuditRepository auditRepository;

    public MenuHoldMonitoringQueryService(
            MenuHoldTransitionAuditRepository auditRepository
    ) {
        this.auditRepository = auditRepository;
    }

    public MenuHoldMonitoringContracts.ReferencePage findChangedCases(
            MenuHoldMonitoringContracts.ChangeQuery query
    ) {
        return sourceRead(() -> doFindChangedCases(query));
    }

    private MenuHoldMonitoringContracts.ReferencePage doFindChangedCases(
            MenuHoldMonitoringContracts.ChangeQuery query
    ) {
        boolean allStatuses = query.sourceStatuses().isEmpty();
        java.util.Set<MenuHoldStatus> statuses = allStatuses
                ? EnumSet.allOf(MenuHoldStatus.class)
                : query.sourceStatuses().stream()
                        .map(MenuHoldStatus::valueOf)
                        .collect(Collectors.toUnmodifiableSet());
        List<MenuHoldTransitionAudit> changes =
                auditRepository.findMonitoringChanges(
                        query.changedFrom(), query.changedTo(),
                        query.storeId() == null ? null : Long.parseLong(query.storeId()),
                        allStatuses, statuses,
                        query.after() == null ? null : query.after().statusChangedAt(),
                        query.after() == null ? null : query.after().caseId(),
                        Pageable.ofSize(query.limit()));
        LinkedHashMap<String, MenuHoldMonitoringContracts.CaseReference> references =
                new LinkedHashMap<>();
        for (MenuHoldTransitionAudit audit : changes) {
            String caseId = caseId(audit.getReservationHoldId(), audit.getReservationId());
            if (caseId == null
                    || !matchesStore(query.storeId(), audit.getStoreId())
                    || !matchesStatus(query.sourceStatuses(), audit.getAfterStatus())) {
                continue;
            }
            references.putIfAbsent(caseId, new MenuHoldMonitoringContracts.CaseReference(
                    caseId,
                    Long.toString(audit.getStoreId()),
                    audit.getOccurredAt()));
            if (references.size() == query.limit()) {
                break;
            }
        }
        return new MenuHoldMonitoringContracts.ReferencePage(
                List.copyOf(references.values()), query.asOf(), query.asOf());
    }

    public MenuHoldMonitoringContracts.BatchResult findCases(
            MenuHoldMonitoringContracts.BatchQuery query
    ) {
        return sourceRead(() -> doFindCases(query));
    }

    private MenuHoldMonitoringContracts.BatchResult doFindCases(
            MenuHoldMonitoringContracts.BatchQuery query
    ) {
        Map<String, List<MenuHoldTransitionAudit>> histories = histories(query.caseIds());

        List<MenuHoldMonitoringContracts.SourceCell> cells = query.caseIds().stream()
                .filter(caseId -> visibleAt(histories.get(caseId), query.asOf()))
                .map(caseId -> cell(caseId, histories.get(caseId), query.asOf()))
                .toList();
        return new MenuHoldMonitoringContracts.BatchResult(cells, query.asOf(), query.asOf());
    }

    public Optional<MenuHoldMonitoringContracts.Detail> findCase(
            MenuHoldMonitoringContracts.DetailQuery query
    ) {
        return sourceRead(() -> doFindCase(query));
    }

    private Optional<MenuHoldMonitoringContracts.Detail> doFindCase(
            MenuHoldMonitoringContracts.DetailQuery query
    ) {
        List<MenuHoldTransitionAudit> all = histories(List.of(query.caseId()))
                .getOrDefault(query.caseId(), List.of());
        if (!visibleAt(all, query.asOf())) {
            return Optional.empty();
        }
        MenuHoldMonitoringContracts.SourceCell cell = cell(query.caseId(), all, query.asOf());
        List<MenuHoldMonitoringContracts.Transition> history = all.stream()
                .filter(audit -> !audit.getOccurredAt().isAfter(query.asOf()))
                .map(MenuHoldMonitoringQueryService::transition)
                .toList();
        MenuHoldTransitionAudit latest = latestAt(all, query.asOf());
        List<MenuHoldMonitoringContracts.Item> items = latest == null
                ? List.of()
                : latest.getItemSnapshots().stream()
                        .sorted(Comparator.comparingLong(
                                MenuHoldTransitionAudit.ItemSnapshot::menuId))
                        .map(item -> new MenuHoldMonitoringContracts.Item(
                                Long.toString(item.menuId()),
                                item.displayName(),
                                item.quantity()))
                        .toList();
        return Optional.of(new MenuHoldMonitoringContracts.Detail(cell, history, items));
    }

    private Map<String, List<MenuHoldTransitionAudit>> histories(List<String> caseIds) {
        ParsedCaseIds parsed = parse(caseIds);
        List<MenuHoldTransitionAudit> audits = new ArrayList<>();
        if (!parsed.reservationHoldIds().isEmpty()) {
            audits.addAll(auditRepository
                    .findByReservationHoldIdInOrderByMenuHoldIdAscResultVersionAsc(
                            parsed.reservationHoldIds()));
        }
        if (!parsed.reservationIds().isEmpty()) {
            audits.addAll(auditRepository
                    .findByReservationIdInAndReservationHoldIdIsNullOrderByMenuHoldIdAscResultVersionAsc(
                            parsed.reservationIds()));
        }
        return audits.stream()
                .collect(Collectors.groupingBy(
                        audit -> caseId(audit.getReservationHoldId(), audit.getReservationId()),
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    private static MenuHoldMonitoringContracts.SourceCell cell(
            String requestedCaseId,
            List<MenuHoldTransitionAudit> all,
            Instant asOf
    ) {
        List<MenuHoldTransitionAudit> ordered = all.stream()
                .sorted(Comparator.comparingLong(MenuHoldTransitionAudit::getResultVersion))
                .toList();
        MenuHoldTransitionAudit first = ordered.isEmpty() ? null : ordered.getFirst();
        MenuHoldTransitionAudit latest = latestAt(ordered, asOf);
        if (latest == null) {
            return new MenuHoldMonitoringContracts.SourceCell(
                    requestedCaseId,
                    null,
                    null,
                    asOf,
                    asOf,
                    MenuHoldMonitoringContracts.Completeness.UNAVAILABLE,
                    MenuHoldMonitoringContracts.ReconciliationStatus.UNKNOWN,
                    first == null ? null : first.getOccurredAt(),
                    null);
        }
        return new MenuHoldMonitoringContracts.SourceCell(
                caseId(latest.getReservationHoldId(), latest.getReservationId()),
                Long.toString(latest.getStoreId()),
                new MenuHoldMonitoringContracts.ConfirmedState(
                        latest.getAfterStatus().name(),
                        latest.getResultVersion(),
                        latest.getOccurredAt()),
                asOf,
                asOf,
                MenuHoldMonitoringContracts.Completeness.COMPLETE,
                reconciliation(latest.getAfterStatus()),
                first == null ? null : first.getOccurredAt(),
                links(latest));
    }

    private static MenuHoldTransitionAudit latestAt(
            List<MenuHoldTransitionAudit> all,
            Instant asOf
    ) {
        return all.stream()
                .filter(audit -> !audit.getOccurredAt().isAfter(asOf))
                .max(Comparator.comparingLong(MenuHoldTransitionAudit::getResultVersion))
                .orElse(null);
    }

    private static boolean visibleAt(
            List<MenuHoldTransitionAudit> all,
            Instant asOf
    ) {
        if (all == null || all.isEmpty()) {
            return false;
        }
        MenuHoldTransitionAudit first = all.stream()
                .min(Comparator.comparingLong(MenuHoldTransitionAudit::getResultVersion))
                .orElseThrow();
        if (!first.getOccurredAt().isAfter(asOf)) {
            return true;
        }
        return first.getEventType() == MenuHoldTransitionAudit.EventType.BASELINE
                && !first.getHoldCreatedAt().isAfter(asOf);
    }

    private static MenuHoldMonitoringContracts.Transition transition(
            MenuHoldTransitionAudit audit
    ) {
        return new MenuHoldMonitoringContracts.Transition(
                audit.getResultVersion(),
                audit.getBeforeStatus() == null ? null : audit.getBeforeStatus().name(),
                audit.getAfterStatus().name(),
                audit.getOccurredAt());
    }

    private static MenuHoldMonitoringContracts.Links links(
            MenuHoldTransitionAudit audit
    ) {
        return new MenuHoldMonitoringContracts.Links(
                text(audit.getReservationId()), text(audit.getReservationHoldId()));
    }

    private static MenuHoldMonitoringContracts.ReconciliationStatus reconciliation(
            MenuHoldStatus status
    ) {
        return status == MenuHoldStatus.RECONCILIATION_REQUIRED
                ? MenuHoldMonitoringContracts.ReconciliationStatus.REQUIRED
                : MenuHoldMonitoringContracts.ReconciliationStatus.MATCHED;
    }

    private static boolean matchesStore(String expected, long actual) {
        return expected == null || expected.equals(Long.toString(actual));
    }

    private static boolean matchesStatus(java.util.Set<String> expected, MenuHoldStatus actual) {
        return expected.isEmpty() || expected.contains(actual.name());
    }

    private static String caseId(Long reservationHoldId, Long reservationId) {
        if (reservationHoldId != null) {
            return "reservation-hold:" + reservationHoldId;
        }
        return reservationId == null ? null : "reservation:" + reservationId;
    }

    private static String text(Long value) {
        return value == null ? null : value.toString();
    }

    private static long id(String caseId) {
        return Long.parseLong(caseId.substring(caseId.indexOf(':') + 1));
    }

    private static <T> T sourceRead(Supplier<T> read) {
        try {
            return read.get();
        } catch (DataAccessException failure) {
            com.miriyum.global.exception.ServiceException unavailable =
                    new com.miriyum.global.exception.ServiceException(
                            MenuHoldErrorCode.MONITORING_SOURCE_UNAVAILABLE);
            unavailable.addSuppressed(failure);
            throw unavailable;
        }
    }

    private static ParsedCaseIds parse(List<String> caseIds) {
        List<Long> reservationHoldIds = caseIds.stream()
                .filter(caseId -> caseId.startsWith("reservation-hold:"))
                .map(MenuHoldMonitoringQueryService::id)
                .toList();
        List<Long> reservationIds = caseIds.stream()
                .filter(caseId -> caseId.startsWith("reservation:"))
                .map(MenuHoldMonitoringQueryService::id)
                .toList();
        return new ParsedCaseIds(reservationHoldIds, reservationIds);
    }

    private record ParsedCaseIds(List<Long> reservationHoldIds, List<Long> reservationIds) {
    }
}
