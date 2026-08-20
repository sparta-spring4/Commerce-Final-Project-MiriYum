package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.contract.ReservationMonitoringContracts;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCheckInAudit;
import com.miriyum.domain.reservation.entity.ReservationFulfillmentAudit;
import com.miriyum.domain.reservation.entity.ReservationHold;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.entity.ReservationHoldTransitionAudit;
import com.miriyum.domain.reservation.entity.ReservationNoShowAudit;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationCheckInAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.domain.reservation.repository.ReservationFulfillmentAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldTransitionAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationNoShowAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
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
public class ReservationMonitoringQueryService {

    private final ReservationRepository reservationRepository;
    private final ReservationHoldRepository holdRepository;
    private final ReservationHoldTransitionAuditRepository holdAuditRepository;
    private final ReservationDepositProcessRepository processRepository;
    private final ReservationCheckInAuditRepository checkInAuditRepository;
    private final ReservationFulfillmentAuditRepository fulfillmentAuditRepository;
    private final ReservationNoShowAuditRepository noShowAuditRepository;

    public ReservationMonitoringQueryService(
            ReservationRepository reservationRepository,
            ReservationHoldRepository holdRepository,
            ReservationHoldTransitionAuditRepository holdAuditRepository,
            ReservationDepositProcessRepository processRepository,
            ReservationCheckInAuditRepository checkInAuditRepository,
            ReservationFulfillmentAuditRepository fulfillmentAuditRepository,
            ReservationNoShowAuditRepository noShowAuditRepository
    ) {
        this.reservationRepository = reservationRepository;
        this.holdRepository = holdRepository;
        this.holdAuditRepository = holdAuditRepository;
        this.processRepository = processRepository;
        this.checkInAuditRepository = checkInAuditRepository;
        this.fulfillmentAuditRepository = fulfillmentAuditRepository;
        this.noShowAuditRepository = noShowAuditRepository;
    }

    public ReservationMonitoringContracts.ReferencePage findChangedCases(
            ReservationMonitoringContracts.ChangeQuery query
    ) {
        return sourceRead(() -> doFindChangedCases(query));
    }

    private ReservationMonitoringContracts.ReferencePage doFindChangedCases(
            ReservationMonitoringContracts.ChangeQuery query
    ) {
        Pageable page = Pageable.ofSize(query.limit());
        Long storeId = query.storeId() == null ? null : Long.parseLong(query.storeId());
        boolean allStatuses = query.sourceStatuses().isEmpty();
        java.util.Set<ReservationStatus> reservationStatuses = statuses(
                query.sourceStatuses(), ReservationStatus.class);
        java.util.Set<ReservationHoldStatus> holdStatuses = statuses(
                query.sourceStatuses(), ReservationHoldStatus.class);
        Instant afterChangedAt = query.after() == null
                ? null : query.after().statusChangedAt();
        String afterCaseId = query.after() == null ? null : query.after().caseId();
        List<Reservation> reservations = !allStatuses && reservationStatuses.isEmpty()
                ? List.of()
                : reservationRepository.findMonitoringChanges(
                        query.changedFrom(), query.changedTo(), storeId, allStatuses,
                        reservationStatuses.stream().map(Enum::name).sorted()
                                .collect(Collectors.joining(",")),
                        afterChangedAt, afterCaseId, page);
        List<ReservationHoldTransitionAudit> holdAudits =
                !allStatuses && holdStatuses.isEmpty()
                        ? List.of()
                        : holdAuditRepository.findMonitoringChanges(
                                query.changedFrom(), query.changedTo(), storeId, allStatuses,
                                allStatuses ? EnumSet.allOf(ReservationHoldStatus.class)
                                        : holdStatuses,
                                afterChangedAt, afterCaseId, page);
        List<ReservationCheckInAudit> checkIns =
                !allStatuses && reservationStatuses.isEmpty()
                        ? List.of()
                        : checkInAuditRepository.findMonitoringChanges(
                                query.changedFrom(), query.changedTo(), storeId, allStatuses,
                                reservationStatuses.stream().map(Enum::name).sorted()
                                        .collect(Collectors.joining(",")),
                                afterChangedAt, afterCaseId, page);

        List<Long> finalIds = new ArrayList<>(reservations.stream().map(Reservation::getId).toList());
        checkIns.stream().map(ReservationCheckInAudit::getReservationId).forEach(finalIds::add);
        Map<Long, Long> holdByFinal = links(List.of(), finalIds).stream()
                .filter(link -> link.getFinalReservationId() != null)
                .collect(Collectors.toMap(
                        ReservationDepositProcessRepository.MonitoringLink::getFinalReservationId,
                        ReservationDepositProcessRepository.MonitoringLink::getReservationHoldId,
                        (first, ignored) -> first));

        Map<String, ReservationMonitoringContracts.CaseReference> candidates = new HashMap<>();
        for (Reservation reservation : reservations) {
            ReservationState state = reservationChange(
                    reservation, query.changedFrom(), query.changedTo());
            if (state == null) continue;
            putLatest(candidates, new ReservationMonitoringContracts.CaseReference(
                    caseId(holdByFinal.get(reservation.getId()), reservation.getId()),
                    reservation.getStoreId().toString(), state.changedAt()));
        }
        for (ReservationHoldTransitionAudit audit : holdAudits) {
            ReservationHold hold = holdRepository.findById(audit.getReservationHoldId()).orElse(null);
            if (hold == null || (storeId != null && !hold.getStoreId().equals(storeId))) continue;
            putLatest(candidates, new ReservationMonitoringContracts.CaseReference(
                    caseId(audit.getReservationHoldId(), null),
                    hold.getStoreId().toString(), audit.getOccurredAt()));
        }
        for (ReservationCheckInAudit audit : checkIns) {
            putLatest(candidates, new ReservationMonitoringContracts.CaseReference(
                    caseId(holdByFinal.get(audit.getReservationId()), audit.getReservationId()),
                    audit.getStoreId().toString(), audit.getOccurredAt()));
        }

        List<ReservationMonitoringContracts.CaseReference> result = candidates.values().stream()
                .sorted(Comparator.comparing(
                                ReservationMonitoringContracts.CaseReference::statusChangedAt)
                        .reversed()
                        .thenComparing(
                                ReservationMonitoringContracts.CaseReference::caseId,
                                Comparator.reverseOrder()))
                .limit(query.limit())
                .toList();
        return new ReservationMonitoringContracts.ReferencePage(result, query.asOf(), query.asOf());
    }

    public ReservationMonitoringContracts.BatchResult findCases(
            ReservationMonitoringContracts.BatchQuery query
    ) {
        return sourceRead(() -> doFindCases(query));
    }

    private ReservationMonitoringContracts.BatchResult doFindCases(
            ReservationMonitoringContracts.BatchQuery query
    ) {
        ParsedIds ids = parse(query.caseIds());
        List<ReservationDepositProcessRepository.MonitoringLink> links =
                links(ids.holdIds(), ids.reservationIds());
        Map<Long, Long> finalByHold = links.stream()
                .filter(link -> link.getFinalReservationId() != null)
                .collect(Collectors.toMap(
                        ReservationDepositProcessRepository.MonitoringLink::getReservationHoldId,
                        ReservationDepositProcessRepository.MonitoringLink::getFinalReservationId,
                        (first, ignored) -> first));
        List<Long> finalIds = new ArrayList<>(ids.reservationIds());
        finalIds.addAll(finalByHold.values());
        Map<Long, ReservationHold> holds = holdRepository.findAllByIdIn(ids.holdIds()).stream()
                .collect(Collectors.toMap(ReservationHold::getId, Function.identity()));
        Map<Long, Reservation> reservations = reservationRepository.findAllByIdIn(finalIds).stream()
                .collect(Collectors.toMap(Reservation::getId, Function.identity()));
        Map<Long, List<ReservationHoldTransitionAudit>> holdHistory = holdHistories(ids.holdIds());

        List<ReservationMonitoringContracts.CaseSnapshot> snapshots = new ArrayList<>();
        for (String requested : query.caseIds()) {
            if (requested.startsWith("reservation-hold:")) {
                long holdId = id(requested);
                ReservationHold hold = holds.get(holdId);
                if (hold == null || hold.getCreatedAt().isAfter(query.asOf())) continue;
                Reservation finalReservation = reservations.get(finalByHold.get(holdId));
                snapshots.add(snapshot(hold, finalReservation,
                        holdHistory.getOrDefault(holdId, List.of()), query.asOf()));
            } else {
                Reservation reservation = reservations.get(id(requested));
                if (reservation == null || reservation.getCreatedAt().isAfter(query.asOf())) continue;
                snapshots.add(snapshot(reservation, requested, query.asOf()));
            }
        }
        return new ReservationMonitoringContracts.BatchResult(
                snapshots, query.asOf(), query.asOf());
    }

    public Optional<ReservationMonitoringContracts.Detail> findCase(
            ReservationMonitoringContracts.DetailQuery query
    ) {
        return sourceRead(() -> doFindCase(query));
    }

    private Optional<ReservationMonitoringContracts.Detail> doFindCase(
            ReservationMonitoringContracts.DetailQuery query
    ) {
        if (query.caseId().startsWith("reservation-hold:")) {
            long holdId = id(query.caseId());
            Optional<ReservationHold> found = holdRepository.findById(holdId);
            if (found.isEmpty() || found.orElseThrow().getCreatedAt().isAfter(query.asOf())) {
                return Optional.empty();
            }
            List<ReservationDepositProcessRepository.MonitoringLink> links =
                    links(List.of(holdId), List.of());
            Long finalId = links.stream().map(
                    ReservationDepositProcessRepository.MonitoringLink::getFinalReservationId)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            Reservation reservation = finalId == null
                    ? null : reservationRepository.findById(finalId).orElse(null);
            List<ReservationHoldTransitionAudit> holdEvents = holdAuditRepository
                    .findAllByReservationHoldIdOrderByIdAsc(holdId);
            ReservationMonitoringContracts.CaseSnapshot snapshot = snapshot(
                    found.orElseThrow(), reservation, holdEvents, query.asOf());
            return Optional.of(new ReservationMonitoringContracts.Detail(
                    snapshot, events(holdId, reservation, holdEvents, query.asOf())));
        }

        long reservationId = id(query.caseId());
        Optional<Reservation> found = reservationRepository.findById(reservationId);
        if (found.isEmpty() || found.orElseThrow().getCreatedAt().isAfter(query.asOf())) {
            return Optional.empty();
        }
        if (!links(List.of(), List.of(reservationId)).isEmpty()) {
            return Optional.empty();
        }
        Reservation reservation = found.orElseThrow();
        ReservationMonitoringContracts.CaseSnapshot snapshot =
                snapshot(reservation, query.caseId(), query.asOf());
        return Optional.of(new ReservationMonitoringContracts.Detail(
                snapshot, events(null, reservation, List.of(), query.asOf())));
    }

    private ReservationMonitoringContracts.CaseSnapshot snapshot(
            ReservationHold hold,
            Reservation reservation,
            List<ReservationHoldTransitionAudit> history,
            Instant asOf
    ) {
        List<ReservationMonitoringContracts.LedgerCell> ledgers = new ArrayList<>();
        HoldState state = holdState(hold, history, asOf);
        ledgers.add(new ReservationMonitoringContracts.LedgerCell(
                "RESERVATION_HOLD", state.status(), state.version(), state.changedAt(),
                asOf, asOf, ReservationMonitoringContracts.Completeness.COMPLETE,
                state.status().equals(ReservationHoldStatus.RECONCILIATION_REQUIRED.name())
                        ? ReservationMonitoringContracts.ReconciliationStatus.REQUIRED
                        : ReservationMonitoringContracts.ReconciliationStatus.MATCHED));
        if (reservation != null && !reservation.getCreatedAt().isAfter(asOf)) {
            ReservationState finalState = reservationState(reservation, asOf);
            ledgers.add(reservationCell(finalState, asOf));
        }
        return new ReservationMonitoringContracts.CaseSnapshot(
                caseId(hold.getId(), null), hold.getStoreId().toString(),
                hold.getParty().totalCount(), hold.getStartAt(), hold.getServiceEndAt(), ledgers);
    }

    private ReservationMonitoringContracts.CaseSnapshot snapshot(
            Reservation reservation,
            String caseId,
            Instant asOf
    ) {
        ReservationState state = reservationState(reservation, asOf);
        return new ReservationMonitoringContracts.CaseSnapshot(
                caseId, reservation.getStoreId().toString(), reservation.getParty().totalCount(),
                reservation.getStartAt(), reservation.getServiceEndAt(),
                List.of(reservationCell(state, asOf)));
    }

    private ReservationMonitoringContracts.LedgerCell reservationCell(
            ReservationState state,
            Instant asOf
    ) {
        return new ReservationMonitoringContracts.LedgerCell(
                "RESERVATION", state.status(), state.version(), state.changedAt(),
                asOf, asOf, ReservationMonitoringContracts.Completeness.COMPLETE,
                ReservationMonitoringContracts.ReconciliationStatus.MATCHED);
    }

    private List<ReservationMonitoringContracts.Event> events(
            Long holdId,
            Reservation reservation,
            List<ReservationHoldTransitionAudit> holdEvents,
            Instant asOf
    ) {
        List<ReservationMonitoringContracts.Event> events = new ArrayList<>();
        int version = 0;
        for (ReservationHoldTransitionAudit audit : holdEvents) {
            if (audit.getOccurredAt().isAfter(asOf)) continue;
            events.add(new ReservationMonitoringContracts.Event(
                    "RESERVATION_HOLD",
                    audit.getBeforeStatus() == null ? "CREATED" : "TRANSITION",
                    version++,
                    audit.getBeforeStatus() == null ? null : audit.getBeforeStatus().name(),
                    audit.getAfterStatus().name(), audit.getOccurredAt()));
        }
        if (reservation != null && !reservation.getCreatedAt().isAfter(asOf)) {
            events.add(new ReservationMonitoringContracts.Event(
                    "RESERVATION", "CREATED", 0L, null,
                    ReservationStatus.CONFIRMED.name(), reservation.getCreatedAt()));
            long reservationId = reservation.getId();
            for (ReservationCheckInAudit audit : checkInAuditRepository
                    .findAllByReservationIdInOrderByOccurredAtAscIdAsc(List.of(reservationId))) {
                if (!audit.getOccurredAt().isAfter(asOf)) events.add(new ReservationMonitoringContracts.Event(
                        "RESERVATION", audit.getEventType().name(),
                        audit.getAfterStatus() == ReservationStatus.CONFIRMED ? 0L : 1L,
                        audit.getBeforeStatus().name(), audit.getAfterStatus().name(),
                        audit.getOccurredAt()));
            }
            for (ReservationFulfillmentAudit audit : fulfillmentAuditRepository
                    .findAllByReservationIdInOrderByOccurredAtAscIdAsc(List.of(reservationId))) {
                if (!audit.getOccurredAt().isAfter(asOf)) events.add(new ReservationMonitoringContracts.Event(
                        "RESERVATION", "FULFILLED", 1L,
                        audit.getBeforeStatus().name(), audit.getAfterStatus().name(),
                        audit.getOccurredAt()));
            }
            for (ReservationNoShowAudit audit : noShowAuditRepository
                    .findAllByReservationIdInOrderByOccurredAtAscIdAsc(List.of(reservationId))) {
                if (!audit.getOccurredAt().isAfter(asOf)) events.add(new ReservationMonitoringContracts.Event(
                        "RESERVATION", "NO_SHOW", 1L,
                        audit.getBeforeStatus().name(), audit.getAfterStatus().name(),
                        audit.getOccurredAt()));
            }
            if (reservation.getCancelledAt() != null && !reservation.getCancelledAt().isAfter(asOf)) {
                events.add(new ReservationMonitoringContracts.Event(
                        "RESERVATION", "CANCELLED", 1L, "CONFIRMED", "CANCELLED",
                        reservation.getCancelledAt()));
            }
        }
        return events.stream().sorted(Comparator.comparing(
                ReservationMonitoringContracts.Event::occurredAt)).toList();
    }

    private Map<Long, List<ReservationHoldTransitionAudit>> holdHistories(List<Long> holdIds) {
        if (holdIds.isEmpty()) return Map.of();
        return holdAuditRepository
                .findAllByReservationHoldIdInOrderByReservationHoldIdAscIdAsc(holdIds)
                .stream().collect(Collectors.groupingBy(
                        ReservationHoldTransitionAudit::getReservationHoldId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    private List<ReservationDepositProcessRepository.MonitoringLink> links(
            List<Long> holdIds, List<Long> finalIds) {
        if (holdIds.isEmpty() && finalIds.isEmpty()) return List.of();
        return processRepository.findMonitoringLinks(holdIds, finalIds);
    }

    private static HoldState holdState(
            ReservationHold hold,
            List<ReservationHoldTransitionAudit> history,
            Instant asOf
    ) {
        List<ReservationHoldTransitionAudit> available = history.stream()
                .filter(audit -> !audit.getOccurredAt().isAfter(asOf)).toList();
        if (available.isEmpty()) {
            return new HoldState(ReservationHoldStatus.ACTIVE.name(), 0L, hold.getCreatedAt());
        }
        ReservationHoldTransitionAudit latest = available.getLast();
        return new HoldState(latest.getAfterStatus().name(), available.size() - 1L,
                latest.getOccurredAt());
    }

    private static ReservationState reservationState(Reservation reservation, Instant asOf) {
        if (reservation.getCreatedAt().isAfter(asOf)) return null;
        if (reservation.getCancelledAt() != null && !reservation.getCancelledAt().isAfter(asOf)) {
            return new ReservationState("CANCELLED", 1L, reservation.getCancelledAt());
        }
        if (reservation.getFulfilledAt() != null && !reservation.getFulfilledAt().isAfter(asOf)) {
            return new ReservationState("FULFILLED", 1L, reservation.getFulfilledAt());
        }
        if (reservation.getNoShowAt() != null && !reservation.getNoShowAt().isAfter(asOf)) {
            return new ReservationState("NO_SHOW", 1L, reservation.getNoShowAt());
        }
        return new ReservationState("CONFIRMED", 0L, reservation.getCreatedAt());
    }

    private static ReservationState reservationChange(
            Reservation reservation,
            Instant changedFrom,
            Instant changedTo
    ) {
        List<ReservationState> changes = new ArrayList<>();
        addChange(changes, "CONFIRMED", 0L, reservation.getCreatedAt(), changedFrom, changedTo);
        addChange(changes, "CANCELLED", 1L, reservation.getCancelledAt(), changedFrom, changedTo);
        addChange(changes, "FULFILLED", 1L, reservation.getFulfilledAt(), changedFrom, changedTo);
        addChange(changes, "NO_SHOW", 1L, reservation.getNoShowAt(), changedFrom, changedTo);
        return changes.stream().max(Comparator
                .comparing(ReservationState::changedAt)
                .thenComparingInt(state -> statusPriority(state.status())))
                .orElse(null);
    }

    private static void addChange(
            List<ReservationState> changes,
            String status,
            long version,
            Instant changedAt,
            Instant changedFrom,
            Instant changedTo
    ) {
        if (changedAt != null && !changedAt.isBefore(changedFrom) && !changedAt.isAfter(changedTo)) {
            changes.add(new ReservationState(status, version, changedAt));
        }
    }

    private static int statusPriority(String status) {
        return switch (status) {
            case "NO_SHOW" -> 3;
            case "FULFILLED" -> 2;
            case "CANCELLED" -> 1;
            default -> 0;
        };
    }

    private static <E extends Enum<E>> java.util.Set<E> statuses(
            java.util.Set<String> requested,
            Class<E> type
    ) {
        if (requested.isEmpty()) return EnumSet.allOf(type);
        return java.util.Arrays.stream(type.getEnumConstants())
                .filter(value -> requested.contains(value.name()))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void putLatest(
            Map<String, ReservationMonitoringContracts.CaseReference> target,
            ReservationMonitoringContracts.CaseReference candidate) {
        target.merge(candidate.caseId(), candidate,
                (left, right) -> left.statusChangedAt().isAfter(right.statusChangedAt())
                        ? left : right);
    }

    private static boolean matches(java.util.Set<String> expected, String status) {
        return expected.isEmpty() || expected.contains(status);
    }

    private static String caseId(Long holdId, Long reservationId) {
        return holdId != null ? "reservation-hold:" + holdId : "reservation:" + reservationId;
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
                            ReservationErrorCode.RESERVATION_MONITORING_UNAVAILABLE);
            unavailable.addSuppressed(failure);
            throw unavailable;
        }
    }

    private static ParsedIds parse(List<String> caseIds) {
        return new ParsedIds(
                caseIds.stream().filter(value -> value.startsWith("reservation-hold:"))
                        .map(ReservationMonitoringQueryService::id).toList(),
                caseIds.stream().filter(value -> value.startsWith("reservation:"))
                        .map(ReservationMonitoringQueryService::id).toList());
    }

    private record ParsedIds(List<Long> holdIds, List<Long> reservationIds) { }
    private record HoldState(String status, long version, Instant changedAt) { }
    private record ReservationState(String status, long version, Instant changedAt) { }
}
