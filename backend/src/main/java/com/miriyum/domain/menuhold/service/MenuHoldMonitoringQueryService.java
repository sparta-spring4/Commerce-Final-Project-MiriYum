package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.dto.MenuHoldMonitoringContracts;
import com.miriyum.domain.menuhold.entity.MenuHold;
import com.miriyum.domain.menuhold.entity.MenuHoldStatus;
import com.miriyum.domain.menuhold.entity.MenuHoldTransitionAudit;
import com.miriyum.domain.menuhold.repository.MenuHoldRepository;
import com.miriyum.domain.menuhold.repository.MenuHoldTransitionAuditRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
public class MenuHoldMonitoringQueryService {

    private static final int MAX_TRANSITIONS_PER_CASE = 8;

    private final MenuHoldRepository holdRepository;
    private final MenuHoldTransitionAuditRepository auditRepository;

    public MenuHoldMonitoringQueryService(
            MenuHoldRepository holdRepository,
            MenuHoldTransitionAuditRepository auditRepository
    ) {
        this.holdRepository = holdRepository;
        this.auditRepository = auditRepository;
    }

    public MenuHoldMonitoringContracts.ReferencePage findChangedCases(
            MenuHoldMonitoringContracts.ChangeQuery query
    ) {
        int fetchSize = Math.multiplyExact(query.limit(), MAX_TRANSITIONS_PER_CASE);
        List<MenuHoldTransitionAudit> changes =
                auditRepository.findByOccurredAtBetweenOrderByOccurredAtDescIdDesc(
                        query.changedFrom(), query.changedTo(), Pageable.ofSize(fetchSize));
        LinkedHashMap<String, MenuHoldMonitoringContracts.CaseReference> references =
                new LinkedHashMap<>();
        for (MenuHoldTransitionAudit audit : changes) {
            String caseId = caseId(audit.getReservationHoldId(), audit.getReservationId());
            if (caseId == null
                    || !matchesStore(query.storeId(), audit.getMenuHold().getStoreId())
                    || !matchesStatus(query.sourceStatuses(), audit.getAfterStatus())
                    || !isAfterSeek(audit.getOccurredAt(), caseId, query.after())) {
                continue;
            }
            references.putIfAbsent(caseId, new MenuHoldMonitoringContracts.CaseReference(
                    caseId,
                    Long.toString(audit.getMenuHold().getStoreId()),
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
        ParsedCaseIds parsed = parse(query.caseIds());
        List<MenuHold> holds = new ArrayList<>();
        holds.addAll(holdRepository.findAllByReservationHoldIdIn(parsed.reservationHoldIds()));
        holds.addAll(holdRepository.findAllByReservationIdInAndReservationHoldIdIsNull(
                parsed.reservationIds()));
        Map<String, MenuHold> byCaseId = holds.stream().collect(Collectors.toMap(
                MenuHoldMonitoringQueryService::caseId,
                Function.identity(),
                (first, ignored) -> first));
        Map<Long, List<MenuHoldTransitionAudit>> histories = histories(holds);

        List<MenuHoldMonitoringContracts.SourceCell> cells = query.caseIds().stream()
                .map(byCaseId::get)
                .filter(java.util.Objects::nonNull)
                .map(hold -> cell(hold, histories.getOrDefault(hold.getId(), List.of()), query.asOf()))
                .toList();
        return new MenuHoldMonitoringContracts.BatchResult(cells, query.asOf(), query.asOf());
    }

    public Optional<MenuHoldMonitoringContracts.Detail> findCase(
            MenuHoldMonitoringContracts.DetailQuery query
    ) {
        Optional<MenuHold> found = findHold(query.caseId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        MenuHold hold = found.orElseThrow();
        List<MenuHoldTransitionAudit> all = auditRepository
                .findByMenuHold_IdInOrderByMenuHold_IdAscResultVersionAsc(List.of(hold.getId()));
        MenuHoldMonitoringContracts.SourceCell cell = cell(hold, all, query.asOf());
        List<MenuHoldMonitoringContracts.Transition> history = all.stream()
                .filter(audit -> !audit.getOccurredAt().isAfter(query.asOf()))
                .map(MenuHoldMonitoringQueryService::transition)
                .toList();
        List<MenuHoldMonitoringContracts.Item> items = hold.getItems().stream()
                .map(item -> new MenuHoldMonitoringContracts.Item(
                        Long.toString(item.getMenuId()),
                        item.getMenuNameSnapshot(),
                        item.getQuantity()))
                .toList();
        return Optional.of(new MenuHoldMonitoringContracts.Detail(cell, history, items));
    }

    private Map<Long, List<MenuHoldTransitionAudit>> histories(List<MenuHold> holds) {
        List<Long> holdIds = holds.stream().map(MenuHold::getId).toList();
        if (holdIds.isEmpty()) {
            return Map.of();
        }
        return auditRepository.findByMenuHold_IdInOrderByMenuHold_IdAscResultVersionAsc(holdIds)
                .stream()
                .collect(Collectors.groupingBy(
                        audit -> audit.getMenuHold().getId(),
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    private static MenuHoldMonitoringContracts.SourceCell cell(
            MenuHold hold,
            List<MenuHoldTransitionAudit> all,
            Instant asOf
    ) {
        List<MenuHoldTransitionAudit> ordered = all.stream()
                .sorted(Comparator.comparingLong(MenuHoldTransitionAudit::getResultVersion))
                .toList();
        MenuHoldTransitionAudit first = ordered.isEmpty() ? null : ordered.getFirst();
        MenuHoldTransitionAudit latest = ordered.stream()
                .filter(audit -> !audit.getOccurredAt().isAfter(asOf))
                .reduce((left, right) -> right)
                .orElse(null);
        if (latest == null) {
            return new MenuHoldMonitoringContracts.SourceCell(
                    caseId(hold),
                    Long.toString(hold.getStoreId()),
                    "UNAVAILABLE",
                    0L,
                    asOf,
                    asOf,
                    asOf,
                    MenuHoldMonitoringContracts.Completeness.UNAVAILABLE,
                    MenuHoldMonitoringContracts.ReconciliationStatus.UNKNOWN,
                    first == null ? null : first.getOccurredAt(),
                    links(first, hold));
        }
        return new MenuHoldMonitoringContracts.SourceCell(
                caseId(latest.getReservationHoldId(), latest.getReservationId()),
                Long.toString(hold.getStoreId()),
                latest.getAfterStatus().name(),
                latest.getResultVersion(),
                latest.getOccurredAt(),
                asOf,
                asOf,
                MenuHoldMonitoringContracts.Completeness.COMPLETE,
                reconciliation(latest.getAfterStatus()),
                first == null ? null : first.getOccurredAt(),
                links(latest));
    }

    private Optional<MenuHold> findHold(String caseId) {
        if (caseId.startsWith("reservation-hold:")) {
            return holdRepository.findByReservationHoldId(id(caseId));
        }
        return holdRepository.findByReservationIdAndReservationHoldIdIsNull(id(caseId));
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

    private static MenuHoldMonitoringContracts.Links links(
            MenuHoldTransitionAudit first,
            MenuHold hold
    ) {
        if (first != null) {
            return links(first);
        }
        return new MenuHoldMonitoringContracts.Links(
                text(hold.getReservationId()), text(hold.getReservationHoldId()));
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

    private static boolean isAfterSeek(
            Instant occurredAt,
            String caseId,
            MenuHoldMonitoringContracts.Seek seek
    ) {
        return seek == null
                || occurredAt.isBefore(seek.statusChangedAt())
                || (occurredAt.equals(seek.statusChangedAt())
                && caseId.compareTo(seek.caseId()) < 0);
    }

    private static String caseId(MenuHold hold) {
        return caseId(hold.getReservationHoldId(), hold.getReservationId());
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
