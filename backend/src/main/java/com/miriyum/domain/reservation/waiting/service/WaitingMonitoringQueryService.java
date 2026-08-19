package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.waiting.dto.WaitingMonitoringContracts;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTransitionAuditRepository;
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
public class WaitingMonitoringQueryService {

    private static final int SOURCE_FETCH_LIMIT = 100;

    private final WaitingTeamRepository teamRepository;
    private final WaitingTransitionAuditRepository auditRepository;

    public WaitingMonitoringQueryService(
            WaitingTeamRepository teamRepository,
            WaitingTransitionAuditRepository auditRepository
    ) {
        this.teamRepository = teamRepository;
        this.auditRepository = auditRepository;
    }

    public WaitingMonitoringContracts.ReferencePage findChangedCases(
            WaitingMonitoringContracts.ChangeQuery query
    ) {
        Long storeId = query.storeId() == null ? null : Long.parseLong(query.storeId());
        Map<String, WaitingMonitoringContracts.CaseReference> references = new LinkedHashMap<>();
        for (WaitingTransitionAuditRepository.MonitoringTransition transition :
                auditRepository.findMonitoringChanges(
                        query.changedFrom(), query.changedTo(), storeId,
                        Pageable.ofSize(SOURCE_FETCH_LIMIT))) {
            String caseId = caseId(transition.getWaitingTeamId());
            if ((!query.sourceStatuses().isEmpty()
                    && !query.sourceStatuses().contains(transition.getAfterStatus().name()))
                    || !afterSeek(transition.getOccurredAt(), caseId, query.after())) {
                continue;
            }
            references.putIfAbsent(caseId, new WaitingMonitoringContracts.CaseReference(
                    caseId, Long.toString(transition.getStoreId()), transition.getOccurredAt()));
            if (references.size() == query.limit()) break;
        }
        List<WaitingMonitoringContracts.CaseReference> items = references.values().stream()
                .sorted(Comparator.comparing(
                                WaitingMonitoringContracts.CaseReference::statusChangedAt)
                        .reversed()
                        .thenComparing(WaitingMonitoringContracts.CaseReference::caseId,
                                Comparator.reverseOrder()))
                .toList();
        return new WaitingMonitoringContracts.ReferencePage(items, query.asOf(), query.asOf());
    }

    public WaitingMonitoringContracts.BatchResult findCases(
            WaitingMonitoringContracts.BatchQuery query
    ) {
        List<Long> ids = query.caseIds().stream().map(WaitingMonitoringQueryService::id).toList();
        if (ids.isEmpty()) {
            return new WaitingMonitoringContracts.BatchResult(List.of(), query.asOf(), query.asOf());
        }
        Map<Long, WaitingTeam> teams = teamRepository.findAllByIdIn(ids).stream()
                .filter(team -> !team.getCreatedAt().isAfter(query.asOf()))
                .collect(Collectors.toMap(WaitingTeam::getId, Function.identity()));
        Map<Long, List<WaitingTransitionAuditRepository.MonitoringTransition>> histories =
                histories(ids);
        List<WaitingMonitoringContracts.SourceCell> cells = new ArrayList<>();
        for (String requested : query.caseIds()) {
            WaitingTeam team = teams.get(id(requested));
            if (team == null) continue;
            cells.add(cell(team, histories.getOrDefault(team.getId(), List.of()), query.asOf()));
        }
        return new WaitingMonitoringContracts.BatchResult(cells, query.asOf(), query.asOf());
    }

    public Optional<WaitingMonitoringContracts.Detail> findCase(
            WaitingMonitoringContracts.DetailQuery query
    ) {
        WaitingMonitoringContracts.BatchResult batch = findCases(
                new WaitingMonitoringContracts.BatchQuery(query.asOf(), List.of(query.caseId())));
        if (batch.cells().isEmpty()) return Optional.empty();
        List<WaitingMonitoringContracts.Transition> history = auditRepository
                .findMonitoringHistory(List.of(id(query.caseId()))).stream()
                .filter(transition -> !transition.getOccurredAt().isAfter(query.asOf()))
                .map(WaitingMonitoringQueryService::transition)
                .toList();
        return Optional.of(new WaitingMonitoringContracts.Detail(
                batch.cells().getFirst(), history));
    }

    private Map<Long, List<WaitingTransitionAuditRepository.MonitoringTransition>> histories(
            List<Long> ids
    ) {
        return auditRepository.findMonitoringHistory(ids).stream()
                .collect(Collectors.groupingBy(
                        WaitingTransitionAuditRepository.MonitoringTransition::getWaitingTeamId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    private static WaitingMonitoringContracts.SourceCell cell(
            WaitingTeam team,
            List<WaitingTransitionAuditRepository.MonitoringTransition> all,
            Instant asOf
    ) {
        List<WaitingTransitionAuditRepository.MonitoringTransition> ordered = all.stream()
                .sorted(Comparator.comparingLong(
                        WaitingTransitionAuditRepository.MonitoringTransition::getResultVersion))
                .toList();
        WaitingTransitionAuditRepository.MonitoringTransition first =
                ordered.isEmpty() ? null : ordered.getFirst();
        WaitingTransitionAuditRepository.MonitoringTransition latest = ordered.stream()
                .filter(transition -> !transition.getOccurredAt().isAfter(asOf))
                .reduce((left, right) -> right).orElse(null);
        if (latest == null) {
            return new WaitingMonitoringContracts.SourceCell(
                    caseId(team.getId()), team.getStoreId().toString(), "UNAVAILABLE", 0,
                    team.getCreatedAt(), asOf, asOf,
                    WaitingMonitoringContracts.Completeness.UNAVAILABLE,
                    WaitingMonitoringContracts.ReconciliationStatus.UNKNOWN,
                    first == null ? null : first.getOccurredAt(),
                    team.getPartySize(), team.getQueueSequence(),
                    new WaitingMonitoringContracts.Links(null, null));
        }
        return new WaitingMonitoringContracts.SourceCell(
                caseId(team.getId()), team.getStoreId().toString(),
                latest.getAfterStatus().name(), latest.getResultVersion(),
                latest.getOccurredAt(), asOf, asOf,
                WaitingMonitoringContracts.Completeness.COMPLETE,
                WaitingMonitoringContracts.ReconciliationStatus.MATCHED,
                first == null ? null : first.getOccurredAt(),
                team.getPartySize(), team.getQueueSequence(), links(team, latest.getAfterStatus()));
    }

    private static WaitingMonitoringContracts.Links links(
            WaitingTeam team,
            WaitingTeamStatus status
    ) {
        boolean conversionVisible = status == WaitingTeamStatus.RESERVATION_CONVERTING
                || status == WaitingTeamStatus.RESERVATION_CONVERTED;
        return new WaitingMonitoringContracts.Links(
                conversionVisible ? team.getWaitingPaymentId() : null,
                status == WaitingTeamStatus.RESERVATION_CONVERTED
                        ? text(team.getReservationReferenceId()) : null);
    }

    private static WaitingMonitoringContracts.Transition transition(
            WaitingTransitionAuditRepository.MonitoringTransition source
    ) {
        return new WaitingMonitoringContracts.Transition(
                source.getResultVersion(),
                source.getBeforeStatus() == null ? null : source.getBeforeStatus().name(),
                source.getAfterStatus().name(), source.getOccurredAt());
    }

    private static boolean afterSeek(
            Instant occurredAt,
            String caseId,
            WaitingMonitoringContracts.Seek seek
    ) {
        return seek == null
                || occurredAt.isBefore(seek.statusChangedAt())
                || (occurredAt.equals(seek.statusChangedAt())
                && caseId.compareTo(seek.caseId()) < 0);
    }

    private static String caseId(long waitingTeamId) {
        return "waiting:" + waitingTeamId;
    }

    private static long id(String caseId) {
        return Long.parseLong(caseId.substring(caseId.indexOf(':') + 1));
    }

    private static String text(Long value) {
        return value == null ? null : value.toString();
    }
}
