package com.miriyum.domain.platformoperator.adminmonitoring.service;

import com.miriyum.domain.menuhold.dto.MenuHoldMonitoringContracts;
import com.miriyum.domain.menuhold.service.MenuHoldMonitoringQueryService;
import com.miriyum.domain.payment.dto.PaymentMonitoringContracts;
import com.miriyum.domain.payment.service.PaymentMonitoringQueryService;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringRequests.ListQuery;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CaseDetail;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CasePage;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CaseSummary;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CaseType;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.Completeness;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.DependencyFailure;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.LedgerCell;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.LedgerState;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.LifecycleStatus;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.LinkedLedgerSummary;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.MaskingLevel;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.MenuItem;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.PaymentLedgerEvent;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.PaymentRefund;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.ReconciliationStatus;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.Source;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.Transition;
import com.miriyum.domain.platformoperator.adminmonitoring.exception.AdminMonitoringErrorCode;
import com.miriyum.domain.platformoperator.adminmonitoring.service.AdminMonitoringCursorCodec.CursorState;
import com.miriyum.domain.platformoperator.adminmonitoring.service.AdminMonitoringCursorCodec.GlobalSeek;
import com.miriyum.domain.platformoperator.adminmonitoring.service.AdminMonitoringCursorCodec.SourceSeek;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.reservation.dto.contract.ReservationMonitoringContracts;
import com.miriyum.domain.reservation.service.ReservationMonitoringQueryService;
import com.miriyum.domain.reservation.waiting.dto.WaitingMonitoringContracts;
import com.miriyum.domain.reservation.waiting.service.WaitingMonitoringQueryService;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnExpression("'${miriyum.platform-operator.enabled:false}' == 'true' and "
        + "'${miriyum.admin-monitoring.enabled:false}' == 'true'")
public class AdminMonitoringQueryService {

    private static final int SOURCE_PAGE_SIZE = 100;
    private static final int MAX_SOURCE_PAGES = 10;
    private static final Comparator<Candidate> CANDIDATE_ORDER =
            Comparator.comparing(Candidate::statusChangedAt).reversed()
                    .thenComparing(Candidate::caseType)
                    .thenComparing(Candidate::caseId, Comparator.reverseOrder());

    private final ReservationMonitoringQueryService reservations;
    private final WaitingMonitoringQueryService waitings;
    private final MenuHoldMonitoringQueryService menuHolds;
    private final PaymentMonitoringQueryService payments;
    private final AdminMonitoringStatusMapper mapper;
    private final AdminMonitoringCursorCodec cursors;
    private final AdminMonitoringAuthorizationService authorization;
    private final Clock clock;

    public AdminMonitoringQueryService(
            ReservationMonitoringQueryService reservations,
            WaitingMonitoringQueryService waitings,
            MenuHoldMonitoringQueryService menuHolds,
            PaymentMonitoringQueryService payments,
            AdminMonitoringStatusMapper mapper,
            AdminMonitoringCursorCodec cursors,
            AdminMonitoringAuthorizationService authorization,
            Clock clock
    ) {
        this.reservations = reservations;
        this.waitings = waitings;
        this.menuHolds = menuHolds;
        this.payments = payments;
        this.mapper = mapper;
        this.cursors = cursors;
        this.authorization = authorization;
        this.clock = clock;
    }

    public CasePage list(PlatformOperatorPrincipal principal, ListQuery query) {
        authorization.requireRead(principal);
        Instant requestedAt = clock.instant();
        if (query.changedTo().isAfter(requestedAt)) {
            throw new ServiceException(AdminMonitoringErrorCode.INVALID_MONITORING_FILTER);
        }
        CursorState start = query.cursor() == null
                ? new CursorState(query.changedTo(), null, null, null, null, null)
                : cursors.decode(query.cursor(), query);
        Instant asOf = start.asOf();

        boolean wantsReservation = query.includes(CaseType.RESERVATION);
        boolean wantsWaiting = query.includes(CaseType.WAITING);
        Map<Source, DependencyFailure> failures = new LinkedHashMap<>();
        List<Instant> dataThrough = new ArrayList<>();
        List<ChangeScan> scans = new ArrayList<>();
        ChangeScan reservationScan = wantsReservation ? new ChangeScan(
                Source.RESERVATION, start.reservationAfter(), after -> {
                var page = reservations.findChangedCases(new ReservationMonitoringContracts.ChangeQuery(
                        asOf, query.changedFrom(), query.changedTo(), query.storeId(),
                        reservationStatuses(query), reservationSeek(after), SOURCE_PAGE_SIZE));
                return new ChangePage(page.items().stream().map(item -> candidate(
                        Source.RESERVATION, CaseType.RESERVATION,
                        item.caseId(), item.storeId(), item.statusChangedAt())).toList(), page.dataThrough());
            }) : null;
        ChangeScan waitingScan = wantsWaiting ? new ChangeScan(
                Source.WAITING, start.waitingAfter(), after -> {
                var page = waitings.findChangedCases(new WaitingMonitoringContracts.ChangeQuery(
                        asOf, query.changedFrom(), query.changedTo(), query.storeId(),
                        query.statusesFor(Source.WAITING), waitingSeek(after), SOURCE_PAGE_SIZE));
                return new ChangePage(page.items().stream().map(item -> candidate(
                        Source.WAITING, CaseType.WAITING,
                        item.caseId(), item.storeId(), item.statusChangedAt())).toList(), page.dataThrough());
            }) : null;
        ChangeScan menuHoldScan = wantsReservation ? new ChangeScan(
                Source.MENU_HOLD, start.menuHoldAfter(), after -> {
                var page = menuHolds.findChangedCases(new MenuHoldMonitoringContracts.ChangeQuery(
                        asOf, query.changedFrom(), query.changedTo(), query.storeId(),
                        query.statusesFor(Source.MENU_HOLD), menuHoldSeek(after),
                        SOURCE_PAGE_SIZE));
                return new ChangePage(page.items().stream().map(item -> candidate(
                        Source.MENU_HOLD, CaseType.RESERVATION,
                        item.caseId(), item.storeId(), item.statusChangedAt())).toList(), page.dataThrough());
            }) : null;
        ChangeScan paymentScan = new ChangeScan(
                Source.PAYMENT, start.paymentAfter(), after -> {
            var page = payments.findChangedCases(new PaymentMonitoringContracts.ChangeQuery(
                    asOf, query.changedFrom(), query.changedTo(), query.storeId(),
                    query.statusesFor(Source.PAYMENT), paymentSeek(after),
                    SOURCE_PAGE_SIZE));
            return new ChangePage(page.items().stream().map(item -> {
                CaseType itemType = caseType(item.caseId());
                return candidate(
                        Source.PAYMENT, itemType, item.caseId(), null,
                        item.statusChangedAt(), query.includes(itemType));
            }).toList(), page.dataThrough());
        });
        if (reservationScan != null) scans.add(reservationScan);
        if (waitingScan != null) scans.add(waitingScan);
        if (menuHoldScan != null) scans.add(menuHoldScan);
        scans.add(paymentScan);
        scans.forEach(scan -> scan.fetch(failures, dataThrough));

        if (!(wantsReservation && reservationScan != null && reservationScan.readSucceeded())
                && !(wantsWaiting && waitingScan != null && waitingScan.readSucceeded())) {
            throw unavailable();
        }
        refillUnresolvedRawBoundary(scans, query.size(), failures, dataThrough);

        PageAssembly assembly;
        while (true) {
            Map<String, Candidate> candidateIndex = new LinkedHashMap<>();
            scans.stream().flatMap(scan -> scan.occurrences().stream())
                    .forEach(candidate -> addCandidate(candidateIndex, candidate));
            List<Candidate> candidates = new ArrayList<>(candidateIndex.values());
            Hydrated hydrated = hydrate(asOf, candidates, failures, dataThrough);
            if (!(wantsReservation && hydrated.reservationAvailable())
                    && !(wantsWaiting && hydrated.waitingAvailable())) {
                throw unavailable();
            }
            assembly = assemble(query, start.lastEvaluated(), candidates, asOf, hydrated, failures);
            PageAssembly currentAssembly = assembly;
            List<ChangeScan> refill = scans.stream()
                    .filter(ChangeScan::hasMore)
                    .filter(scan -> currentAssembly.items().size() < query.size()
                            || scan.lastFetchedAt().compareTo(
                                    currentAssembly.lastEvaluated().statusChangedAt()) >= 0)
                    .toList();
            if (refill.isEmpty()) break;
            refill.forEach(scan -> scan.fetch(failures, dataThrough));
        }

        if (!(wantsReservation && !failures.containsKey(Source.RESERVATION))
                && !(wantsWaiting && !failures.containsKey(Source.WAITING))) {
            throw unavailable();
        }

        boolean trustworthy = failures.isEmpty();
        boolean hasMore = assembly.hasBufferedAfter() || scans.stream().anyMatch(ChangeScan::hasMore);
        SourceSeek reservationAfter = safeCheckpoint(reservationScan, assembly);
        SourceSeek waitingAfter = safeCheckpoint(waitingScan, assembly);
        SourceSeek menuHoldAfter = safeCheckpoint(menuHoldScan, assembly);
        SourceSeek paymentAfter = safeCheckpoint(paymentScan, assembly);
        String nextCursor = trustworthy && hasMore && assembly.lastEvaluated() != null
                ? cursors.encode(new CursorState(
                        asOf, assembly.lastEvaluated(), reservationAfter, waitingAfter,
                        menuHoldAfter, paymentAfter), query)
                : null;
        Completeness completeness = failures.isEmpty()
                ? pageCompleteness(assembly.items(), asOf, dataThrough)
                : Completeness.PARTIAL;
        return new CasePage(
                assembly.items(),
                asOf,
                minimum(dataThrough, asOf),
                completeness,
                List.copyOf(failures.values()),
                nextCursor);
    }

    private static void refillUnresolvedRawBoundary(
            List<ChangeScan> scans,
            int requestedSize,
            Map<Source, DependencyFailure> failures,
            List<Instant> dataThrough
    ) {
        while (true) {
            Map<String, Candidate> candidateIndex = new LinkedHashMap<>();
            scans.stream().flatMap(scan -> scan.occurrences().stream())
                    .forEach(candidate -> addCandidate(candidateIndex, candidate));
            List<Candidate> eligible = candidateIndex.values().stream()
                    .filter(Candidate::eligible)
                    .sorted(CANDIDATE_ORDER)
                    .toList();
            Instant boundary = eligible.size() < requestedSize
                    ? null : eligible.get(requestedSize - 1).statusChangedAt();
            List<ChangeScan> refill = scans.stream()
                    .filter(ChangeScan::hasMore)
                    .filter(scan -> boundary == null
                            || !scan.lastFetchedAt().isBefore(boundary))
                    .toList();
            if (refill.isEmpty()) return;
            refill.forEach(scan -> scan.fetch(failures, dataThrough));
        }
    }

    private PageAssembly assemble(
            ListQuery query,
            GlobalSeek start,
            List<Candidate> candidates,
            Instant asOf,
            Hydrated hydrated,
            Map<Source, DependencyFailure> failures
    ) {
        List<PreparedCandidate> prepared = candidates.stream().map(candidate -> {
            BuiltSummary built = summary(candidate, asOf, hydrated, failures);
            Candidate ordered = built == null ? candidate
                    : candidate.withStatusChangedAt(built.summary().statusChangedAt());
            return new PreparedCandidate(ordered, built);
        }).sorted(Comparator.comparing(PreparedCandidate::ordered, CANDIDATE_ORDER)).toList();
        Map<String, Candidate> orderByCase = new LinkedHashMap<>();
        prepared.forEach(value -> orderByCase.put(value.ordered().caseId(), value.ordered()));

        List<CaseSummary> items = new ArrayList<>();
        GlobalSeek lastEvaluated = start;
        int remaining = 0;
        int evaluated = 0;
        for (PreparedCandidate candidate : prepared) {
            if (start != null && compare(candidate.ordered(), start) <= 0) {
                continue;
            }
            remaining++;
            if (items.size() == query.size()) continue;
            evaluated++;
            lastEvaluated = seek(candidate.ordered());
            if (candidate.built() != null && matches(query, candidate.built())) {
                items.add(candidate.built().summary());
            }
        }
        return new PageAssembly(
                List.copyOf(items), lastEvaluated, evaluated < remaining, Map.copyOf(orderByCase));
    }

    private static SourceSeek safeCheckpoint(ChangeScan scan, PageAssembly assembly) {
        if (scan == null) return null;
        SourceSeek checkpoint = scan.startAfter();
        GlobalSeek boundary = assembly.lastEvaluated();
        if (boundary == null) return checkpoint;
        for (Candidate occurrence : scan.occurrences()) {
            Candidate ordered = assembly.orderByCase().getOrDefault(occurrence.caseId(), occurrence);
            if (ordered.eligible() && compare(ordered, boundary) > 0) break;
            checkpoint = occurrence.sourceSeeks().get(scan.source());
        }
        return checkpoint;
    }

    private static GlobalSeek seek(Candidate candidate) {
        return new GlobalSeek(
                candidate.statusChangedAt(), candidate.caseType(), candidate.caseId());
    }

    private static int compare(Candidate candidate, GlobalSeek seek) {
        return CANDIDATE_ORDER.compare(candidate, new Candidate(
                seek.caseType(), seek.caseId(), null, seek.statusChangedAt(), Map.of(), true));
    }

    public CaseDetail get(
            PlatformOperatorPrincipal principal,
            CaseType caseType,
            String caseId,
            Instant requestedAsOf
    ) {
        authorization.requireRead(principal);
        Instant currentAsOf = clock.instant();
        Instant asOf = requestedAsOf == null ? currentAsOf : requestedAsOf;
        if (asOf.isAfter(currentAsOf) || !validCaseId(caseType, caseId)) {
            throw new ServiceException(AdminMonitoringErrorCode.INVALID_MONITORING_FILTER);
        }
        return caseType == CaseType.RESERVATION
                ? reservationDetail(principal, caseId, asOf, currentAsOf)
                : waitingDetail(principal, caseId, asOf, currentAsOf);
    }

    private CaseDetail reservationDetail(
            PlatformOperatorPrincipal principal,
            String caseId,
            Instant asOf,
            Instant currentAsOf
    ) {
        ReservationMonitoringContracts.Detail primary;
        try {
            primary = reservations.findCase(new ReservationMonitoringContracts.DetailQuery(asOf, caseId))
                    .orElseThrow(AdminMonitoringQueryService::notFound);
        } catch (ServiceException failure) {
            if (failure.getErrorCode() == AdminMonitoringErrorCode.MONITORING_CASE_NOT_FOUND) throw failure;
            throw unavailable();
        }
        ReservationMonitoringContracts.LedgerCell primaryCell = primaryLedger(primary.snapshot().ledgers());
        long caseVersion = primaryCell.statusVersion() + 1L;
        long assignmentVersion = asOf.equals(currentAsOf)
                ? caseVersion
                : currentReservationVersion(caseId, currentAsOf);
        authorization.requireDetail(principal, caseId, assignmentVersion);

        Map<Source, DependencyFailure> failures = new LinkedHashMap<>();
        List<LedgerCell> ledgers = primary.snapshot().ledgers().stream()
                .map(AdminMonitoringQueryService::ledger).toList();
        List<Transition> history = new ArrayList<>(primary.events().stream()
                .map(AdminMonitoringQueryService::transition).toList());
        List<MenuItem> menuItems = List.of();
        List<PaymentLedgerEvent> paymentLedger = List.of();
        List<PaymentRefund> refunds = List.of();
        boolean paymentLedgerTruncated = false;
        boolean refundsTruncated = false;

        try {
            Optional<MenuHoldMonitoringContracts.Detail> detail =
                    menuHolds.findCase(new MenuHoldMonitoringContracts.DetailQuery(asOf, caseId));
            if (detail.isPresent()) {
                var value = detail.get();
                ledgers = append(ledgers, ledger(value.cell()));
                history.addAll(value.history().stream().map(AdminMonitoringQueryService::transition).toList());
                menuItems = value.items().stream()
                        .map(item -> new MenuItem(item.menuId(), item.displayName(), item.quantity())).toList();
            }
        } catch (ServiceException failure) {
            failures.put(Source.MENU_HOLD, failure(Source.MENU_HOLD, failure));
            ledgers = append(ledgers, unavailable(Source.MENU_HOLD, asOf));
        }
        try {
            Optional<PaymentMonitoringContracts.Detail> detail =
                    payments.findCase(new PaymentMonitoringContracts.DetailQuery(asOf, caseId));
            if (detail.isPresent()) {
                var value = detail.get();
                ledgers = append(ledgers, ledger(value.cell()));
                paymentLedger = value.ledger().stream().map(event -> new PaymentLedgerEvent(
                        event.eventType(), event.amountMinor(), event.occurredAt())).toList();
                refunds = value.refunds().stream().map(refund -> new PaymentRefund(
                        refund.sourceStatus(), refund.statusVersion(), refund.amountMinor(),
                        refund.requestedAt(), refund.completedAt())).toList();
                paymentLedgerTruncated = value.ledgerTruncated();
                refundsTruncated = value.refundsTruncated();
            }
        } catch (ServiceException failure) {
            failures.put(Source.PAYMENT, failure(Source.PAYMENT, failure));
            ledgers = append(ledgers, unavailable(Source.PAYMENT, asOf));
        }

        LifecycleStatus lifecycle = mapper.reservationLifecycle(
                primaryCell.sourceStatus(), primary.events().stream()
                        .map(ReservationMonitoringContracts.Event::eventType).collect(java.util.stream.Collectors.toSet()));
        history.sort(Comparator.comparing(Transition::occurredAt));
        return new CaseDetail(
                CaseType.RESERVATION,
                caseId,
                primary.snapshot().storeId(),
                lifecycle,
                caseVersion,
                asOf,
                minimum(ledgers.stream().map(LedgerCell::dataThrough).toList(), asOf),
                failures.isEmpty() ? cellCompleteness(ledgers) : Completeness.PARTIAL,
                MaskingLevel.MINIMIZED,
                primary.snapshot().partySize(),
                primary.snapshot().scheduledStartAt(),
                primary.snapshot().scheduledEndAt(),
                null,
                ledgers,
                history,
                menuItems,
                paymentLedger,
                paymentLedgerTruncated,
                refunds,
                refundsTruncated,
                List.copyOf(failures.values()));
    }

    private CaseDetail waitingDetail(
            PlatformOperatorPrincipal principal,
            String caseId,
            Instant asOf,
            Instant currentAsOf
    ) {
        WaitingMonitoringContracts.Detail primary;
        try {
            primary = waitings.findCase(new WaitingMonitoringContracts.DetailQuery(asOf, caseId))
                    .orElseThrow(AdminMonitoringQueryService::notFound);
        } catch (ServiceException failure) {
            if (failure.getErrorCode() == AdminMonitoringErrorCode.MONITORING_CASE_NOT_FOUND) throw failure;
            throw unavailable();
        }
        if (primary.cell().state() == null) throw unavailable();
        long caseVersion = primary.cell().state().statusVersion() + 1L;
        long assignmentVersion = asOf.equals(currentAsOf)
                ? caseVersion
                : currentWaitingVersion(caseId, currentAsOf);
        authorization.requireDetail(principal, caseId, assignmentVersion);
        Map<Source, DependencyFailure> failures = new LinkedHashMap<>();
        List<LedgerCell> ledgers = new ArrayList<>(List.of(ledger(primary.cell())));
        List<PaymentLedgerEvent> paymentLedger = List.of();
        List<PaymentRefund> refunds = List.of();
        boolean paymentLedgerTruncated = false;
        boolean refundsTruncated = false;
        try {
            Optional<PaymentMonitoringContracts.Detail> detail =
                    payments.findCase(new PaymentMonitoringContracts.DetailQuery(asOf, caseId));
            if (detail.isPresent()) {
                var value = detail.get();
                ledgers.add(ledger(value.cell()));
                paymentLedger = value.ledger().stream().map(event -> new PaymentLedgerEvent(
                        event.eventType(), event.amountMinor(), event.occurredAt())).toList();
                refunds = value.refunds().stream().map(refund -> new PaymentRefund(
                        refund.sourceStatus(), refund.statusVersion(), refund.amountMinor(),
                        refund.requestedAt(), refund.completedAt())).toList();
                paymentLedgerTruncated = value.ledgerTruncated();
                refundsTruncated = value.refundsTruncated();
            }
        } catch (ServiceException failure) {
            failures.put(Source.PAYMENT, failure(Source.PAYMENT, failure));
            ledgers.add(unavailable(Source.PAYMENT, asOf));
        }
        var state = primary.cell().state();
        List<Transition> history = primary.history().stream().map(transition -> new Transition(
                Source.WAITING, "TRANSITION", transition.resultVersion(),
                transition.beforeStatus(), transition.afterStatus(), transition.occurredAt())).toList();
        return new CaseDetail(
                CaseType.WAITING,
                caseId,
                state.storeId(),
                mapper.lifecycle(Source.WAITING, state.sourceStatus()),
                caseVersion,
                asOf,
                minimum(ledgers.stream().map(LedgerCell::dataThrough).toList(), asOf),
                failures.isEmpty() ? cellCompleteness(ledgers) : Completeness.PARTIAL,
                MaskingLevel.MINIMIZED,
                state.partySize(),
                null,
                null,
                state.queueSequence(),
                ledgers,
                history,
                List.of(),
                paymentLedger,
                paymentLedgerTruncated,
                refunds,
                refundsTruncated,
                List.copyOf(failures.values()));
    }

    private long currentReservationVersion(String caseId, Instant currentAsOf) {
        try {
            return reservations.findCase(new ReservationMonitoringContracts.DetailQuery(
                            currentAsOf, caseId))
                    .map(detail -> primaryLedger(detail.snapshot().ledgers()).statusVersion() + 1L)
                    .orElseThrow(AdminMonitoringQueryService::unavailable);
        } catch (ServiceException failure) {
            throw unavailable();
        }
    }

    private long currentWaitingVersion(String caseId, Instant currentAsOf) {
        try {
            return waitings.findCase(new WaitingMonitoringContracts.DetailQuery(currentAsOf, caseId))
                    .map(WaitingMonitoringContracts.Detail::cell)
                    .map(WaitingMonitoringContracts.SourceCell::state)
                    .map(state -> state.statusVersion() + 1L)
                    .orElseThrow(AdminMonitoringQueryService::unavailable);
        } catch (ServiceException failure) {
            throw unavailable();
        }
    }

    private Hydrated hydrate(
            Instant asOf,
            List<Candidate> candidates,
            Map<Source, DependencyFailure> failures,
            List<Instant> dataThrough
    ) {
        List<String> reservationIds = candidates.stream()
                .filter(Candidate::eligible)
                .filter(candidate -> candidate.caseType() == CaseType.RESERVATION)
                .map(Candidate::caseId).toList();
        List<String> waitingIds = candidates.stream()
                .filter(Candidate::eligible)
                .filter(candidate -> candidate.caseType() == CaseType.WAITING)
                .map(Candidate::caseId).toList();
        Map<String, ReservationMonitoringContracts.CaseSnapshot> reservationCases = new LinkedHashMap<>();
        Map<String, WaitingMonitoringContracts.SourceCell> waitingCases = new LinkedHashMap<>();
        boolean reservationAvailable = true;
        boolean waitingAvailable = true;
        for (int start = 0; start < reservationIds.size(); start += SOURCE_PAGE_SIZE) {
            List<String> ids = reservationIds.subList(
                    start, Math.min(start + SOURCE_PAGE_SIZE, reservationIds.size()));
            try {
                var result = reservations.findCases(new ReservationMonitoringContracts.BatchQuery(asOf, ids));
                result.cases().forEach(value -> reservationCases.put(value.caseId(), value));
                dataThrough.add(result.dataThrough());
            } catch (ServiceException failure) {
                reservationAvailable = false;
                failures.put(Source.RESERVATION, failure(Source.RESERVATION, failure));
                break;
            }
        }
        for (int start = 0; start < waitingIds.size(); start += SOURCE_PAGE_SIZE) {
            List<String> ids = waitingIds.subList(
                    start, Math.min(start + SOURCE_PAGE_SIZE, waitingIds.size()));
            try {
                var result = waitings.findCases(new WaitingMonitoringContracts.BatchQuery(asOf, ids));
                result.cells().forEach(value -> waitingCases.put(value.caseId(), value));
                dataThrough.add(result.dataThrough());
            } catch (ServiceException failure) {
                waitingAvailable = false;
                failures.put(Source.WAITING, failure(Source.WAITING, failure));
                break;
            }
        }

        Map<String, MenuHoldMonitoringContracts.SourceCell> menus = new LinkedHashMap<>();
        boolean menuBatchAvailable = true;
        for (int start = 0; start < reservationIds.size(); start += SOURCE_PAGE_SIZE) {
            List<String> ids = reservationIds.subList(
                    start, Math.min(start + SOURCE_PAGE_SIZE, reservationIds.size()));
            try {
                var result = menuHolds.findCases(new MenuHoldMonitoringContracts.BatchQuery(asOf, ids));
                result.cells().forEach(value -> menus.put(value.caseId(), value));
                dataThrough.add(result.dataThrough());
            } catch (ServiceException failure) {
                menuBatchAvailable = false;
                failures.put(Source.MENU_HOLD, failure(Source.MENU_HOLD, failure));
                break;
            }
        }
        Map<String, PaymentMonitoringContracts.SourceCell> paymentCases = new LinkedHashMap<>();
        boolean paymentBatchAvailable = true;
        for (int start = 0; start < candidates.size(); start += SOURCE_PAGE_SIZE) {
            List<String> ids = candidates.subList(start, Math.min(start + SOURCE_PAGE_SIZE, candidates.size()))
                    .stream().map(Candidate::caseId).toList();
            try {
                var result = payments.findCases(new PaymentMonitoringContracts.BatchQuery(asOf, ids));
                result.cells().forEach(cell -> paymentCases.put(cell.caseId(), cell));
                dataThrough.add(result.dataThrough());
            } catch (ServiceException failure) {
                paymentBatchAvailable = false;
                failures.put(Source.PAYMENT, failure(Source.PAYMENT, failure));
                break;
            }
        }
        return new Hydrated(
                Map.copyOf(reservationCases), Map.copyOf(waitingCases), Map.copyOf(menus),
                Map.copyOf(paymentCases),
                reservationAvailable, waitingAvailable, menuBatchAvailable, paymentBatchAvailable);
    }

    private BuiltSummary summary(
            Candidate candidate,
            Instant asOf,
            Hydrated hydrated,
            Map<Source, DependencyFailure> failures
    ) {
        if (!candidate.eligible()) return null;
        if (candidate.caseType() == CaseType.RESERVATION) {
            var snapshot = hydrated.reservations().get(candidate.caseId());
            if (snapshot == null) return null;
            var primary = primaryLedger(snapshot.ledgers());
            LifecycleStatus lifecycle = mapper.lifecycle(Source.valueOf(primary.source()), primary.sourceStatus());
            if (lifecycle == LifecycleStatus.CONFIRMED
                    && candidate.statusChangedAt().isAfter(primary.statusChangedAt())) {
                try {
                    lifecycle = reservations.findCase(new ReservationMonitoringContracts.DetailQuery(
                                    asOf, candidate.caseId()))
                            .map(detail -> mapper.reservationLifecycle(
                                    primary.sourceStatus(), detail.events().stream()
                                            .map(ReservationMonitoringContracts.Event::eventType)
                                            .collect(java.util.stream.Collectors.toSet())))
                            .orElse(lifecycle);
                } catch (ServiceException failure) {
                    failures.put(Source.RESERVATION, failure(Source.RESERVATION, failure));
                    return null;
                }
            }
            List<LinkedLedgerSummary> ledgers = new ArrayList<>(snapshot.ledgers().stream()
                    .map(AdminMonitoringQueryService::summary).toList());
            Map<Source, String> states = new EnumMap<>(Source.class);
            snapshot.ledgers().forEach(cell -> states.put(Source.valueOf(cell.source()), cell.sourceStatus()));
            addMenuSummary(candidate.caseId(), hydrated, ledgers, states);
            addPaymentSummary(candidate.caseId(), hydrated, ledgers, states);
            Completeness completeness = summaryCompleteness(ledgers);
            ReconciliationStatus reconciliation = reconciliation(ledgers);
            List<Instant> throughValues = new ArrayList<>(snapshot.ledgers().stream()
                    .map(ReservationMonitoringContracts.LedgerCell::dataThrough).toList());
            Optional.ofNullable(hydrated.menus().get(candidate.caseId()))
                    .map(MenuHoldMonitoringContracts.SourceCell::dataThrough)
                    .ifPresent(throughValues::add);
            Optional.ofNullable(hydrated.payments().get(candidate.caseId()))
                    .map(PaymentMonitoringContracts.SourceCell::dataThrough)
                    .ifPresent(throughValues::add);
            Instant through = minimum(throughValues, asOf);
            List<Instant> changedValues = new ArrayList<>(snapshot.ledgers().stream()
                    .map(ReservationMonitoringContracts.LedgerCell::statusChangedAt).toList());
            changedValues.add(candidate.statusChangedAt());
            Optional.ofNullable(hydrated.menus().get(candidate.caseId()))
                    .map(MenuHoldMonitoringContracts.SourceCell::state)
                    .map(MenuHoldMonitoringContracts.ConfirmedState::statusChangedAt)
                    .ifPresent(changedValues::add);
            Optional.ofNullable(hydrated.payments().get(candidate.caseId()))
                    .map(PaymentMonitoringContracts.SourceCell::state)
                    .map(PaymentMonitoringContracts.ConfirmedState::statusChangedAt)
                    .ifPresent(changedValues::add);
            return new BuiltSummary(new CaseSummary(
                    CaseType.RESERVATION, candidate.caseId(), snapshot.storeId(), lifecycle,
                    primary.sourceStatus(), maximum(changedValues, candidate.statusChangedAt()),
                    primary.statusVersion() + 1L,
                    asOf, through, completeness, reconciliation, ledgers), states);
        }
        var cell = hydrated.waitings().get(candidate.caseId());
        if (cell == null || cell.state() == null) return null;
        var state = cell.state();
        List<LinkedLedgerSummary> ledgers = new ArrayList<>();
        ledgers.add(summary(cell));
        Map<Source, String> states = new EnumMap<>(Source.class);
        states.put(Source.WAITING, state.sourceStatus());
        addPaymentSummary(candidate.caseId(), hydrated, ledgers, states);
        List<Instant> throughValues = new ArrayList<>(List.of(cell.dataThrough()));
        Optional.ofNullable(hydrated.payments().get(candidate.caseId()))
                .map(PaymentMonitoringContracts.SourceCell::dataThrough)
                .ifPresent(throughValues::add);
        List<Instant> changedValues = new ArrayList<>(List.of(
                candidate.statusChangedAt(), state.statusChangedAt()));
        Optional.ofNullable(hydrated.payments().get(candidate.caseId()))
                .map(PaymentMonitoringContracts.SourceCell::state)
                .map(PaymentMonitoringContracts.ConfirmedState::statusChangedAt)
                .ifPresent(changedValues::add);
        return new BuiltSummary(new CaseSummary(
                CaseType.WAITING, candidate.caseId(), state.storeId(),
                mapper.lifecycle(Source.WAITING, state.sourceStatus()), state.sourceStatus(),
                maximum(changedValues, candidate.statusChangedAt()), state.statusVersion() + 1L,
                asOf, minimum(throughValues, asOf), summaryCompleteness(ledgers), reconciliation(ledgers), ledgers), states);
    }

    private static boolean matches(ListQuery query, BuiltSummary built) {
        CaseSummary summary = built.summary();
        if (query.storeId() != null && !query.storeId().equals(summary.storeId())) return false;
        if (!query.lifecycleStatuses().isEmpty()
                && !query.lifecycleStatuses().contains(summary.lifecycleStatus())) return false;
        if (!query.reconciliationStatuses().isEmpty()
                && !query.reconciliationStatuses().contains(summary.reconciliationStatus())) return false;
        for (Source source : Source.values()) {
            Set<String> required = query.statusesFor(source);
            String actual = built.states().get(source);
            if (!required.isEmpty() && (actual == null || !required.contains(actual))) return false;
        }
        return true;
    }

    private static void addMenuSummary(
            String caseId,
            Hydrated hydrated,
            List<LinkedLedgerSummary> ledgers,
            Map<Source, String> states
    ) {
        var cell = hydrated.menus().get(caseId);
        if (cell == null) {
            ledgers.add(hydrated.menuBatchAvailable()
                    ? new LinkedLedgerSummary(
                            Source.MENU_HOLD, false, Completeness.COMPLETE, ReconciliationStatus.MATCHED)
                    : new LinkedLedgerSummary(
                            Source.MENU_HOLD, false, Completeness.UNAVAILABLE, ReconciliationStatus.UNKNOWN));
        } else {
            ledgers.add(summary(cell));
            if (cell.state() != null) states.put(Source.MENU_HOLD, cell.state().sourceStatus());
        }
    }

    private static void addPaymentSummary(
            String caseId,
            Hydrated hydrated,
            List<LinkedLedgerSummary> ledgers,
            Map<Source, String> states
    ) {
        var cell = hydrated.payments().get(caseId);
        if (cell == null) {
            ledgers.add(hydrated.paymentBatchAvailable()
                    ? new LinkedLedgerSummary(
                            Source.PAYMENT, false, Completeness.COMPLETE, ReconciliationStatus.MATCHED)
                    : new LinkedLedgerSummary(
                            Source.PAYMENT, false, Completeness.UNAVAILABLE, ReconciliationStatus.UNKNOWN));
        } else {
            ledgers.add(summary(cell));
            if (cell.state() != null) states.put(Source.PAYMENT, cell.state().sourceStatus());
        }
    }

    private static ReservationMonitoringContracts.LedgerCell primaryLedger(
            List<ReservationMonitoringContracts.LedgerCell> ledgers
    ) {
        return ledgers.stream().filter(cell -> cell.source().equals("RESERVATION"))
                .findFirst().orElseGet(() -> ledgers.stream()
                        .filter(cell -> cell.source().equals("RESERVATION_HOLD"))
                        .findFirst().orElseThrow(AdminMonitoringQueryService::unavailable));
    }

    private static LedgerCell ledger(ReservationMonitoringContracts.LedgerCell cell) {
        return new LedgerCell(
                Source.valueOf(cell.source()),
                new LedgerState(cell.sourceStatus(), cell.statusVersion(), cell.statusChangedAt(),
                        null, null, null),
                cell.asOf(), cell.dataThrough(), Completeness.valueOf(cell.completeness().name()),
                ReconciliationStatus.valueOf(cell.reconciliationStatus().name()), null);
    }

    private static LedgerCell ledger(MenuHoldMonitoringContracts.SourceCell cell) {
        var state = cell.state();
        return new LedgerCell(
                Source.MENU_HOLD,
                state == null ? null : new LedgerState(
                        state.sourceStatus(), state.statusVersion(), state.statusChangedAt(), null, null, null),
                cell.asOf(), cell.dataThrough(), Completeness.valueOf(cell.completeness().name()),
                ReconciliationStatus.valueOf(cell.reconciliationStatus().name()), cell.historyAvailableFrom());
    }

    private static LedgerCell ledger(PaymentMonitoringContracts.SourceCell cell) {
        var state = cell.state();
        return new LedgerCell(
                Source.PAYMENT,
                state == null ? null : new LedgerState(
                        state.sourceStatus(), state.statusVersion(), state.statusChangedAt(),
                        state.amountMinor(), state.refundedAmountMinor(), state.currency()),
                cell.asOf(), cell.dataThrough(), Completeness.valueOf(cell.completeness().name()),
                ReconciliationStatus.valueOf(cell.reconciliationStatus().name()), cell.historyAvailableFrom());
    }

    private static LedgerCell ledger(WaitingMonitoringContracts.SourceCell cell) {
        var state = cell.state();
        return new LedgerCell(
                Source.WAITING,
                state == null ? null : new LedgerState(
                        state.sourceStatus(), state.statusVersion(), state.statusChangedAt(), null, null, null),
                cell.asOf(), cell.dataThrough(), Completeness.valueOf(cell.completeness().name()),
                ReconciliationStatus.valueOf(cell.reconciliationStatus().name()), cell.historyAvailableFrom());
    }

    private static LinkedLedgerSummary summary(ReservationMonitoringContracts.LedgerCell cell) {
        return new LinkedLedgerSummary(
                Source.valueOf(cell.source()), true, Completeness.valueOf(cell.completeness().name()),
                ReconciliationStatus.valueOf(cell.reconciliationStatus().name()));
    }

    private static LinkedLedgerSummary summary(MenuHoldMonitoringContracts.SourceCell cell) {
        return new LinkedLedgerSummary(
                Source.MENU_HOLD, cell.state() != null, Completeness.valueOf(cell.completeness().name()),
                ReconciliationStatus.valueOf(cell.reconciliationStatus().name()));
    }

    private static LinkedLedgerSummary summary(PaymentMonitoringContracts.SourceCell cell) {
        return new LinkedLedgerSummary(
                Source.PAYMENT, cell.state() != null, Completeness.valueOf(cell.completeness().name()),
                ReconciliationStatus.valueOf(cell.reconciliationStatus().name()));
    }

    private static LinkedLedgerSummary summary(WaitingMonitoringContracts.SourceCell cell) {
        return new LinkedLedgerSummary(
                Source.WAITING, cell.state() != null, Completeness.valueOf(cell.completeness().name()),
                ReconciliationStatus.valueOf(cell.reconciliationStatus().name()));
    }

    private static Transition transition(ReservationMonitoringContracts.Event event) {
        return new Transition(
                Source.valueOf(event.source()), event.eventType(), event.resultVersion(),
                event.beforeStatus(), event.afterStatus(), event.occurredAt());
    }

    private static Transition transition(MenuHoldMonitoringContracts.Transition transition) {
        return new Transition(
                Source.MENU_HOLD, "TRANSITION", transition.resultVersion(),
                transition.beforeStatus(), transition.afterStatus(), transition.occurredAt());
    }

    private static LedgerCell unavailable(Source source, Instant asOf) {
        return new LedgerCell(
                source, null, asOf, asOf, Completeness.UNAVAILABLE,
                ReconciliationStatus.UNKNOWN, null);
    }

    private static List<LedgerCell> append(List<LedgerCell> values, LedgerCell value) {
        List<LedgerCell> result = new ArrayList<>(values);
        result.add(value);
        return result;
    }

    private static Set<String> reservationStatuses(ListQuery query) {
        java.util.HashSet<String> values = new java.util.HashSet<>(query.statusesFor(Source.RESERVATION));
        values.addAll(query.statusesFor(Source.RESERVATION_HOLD));
        return Set.copyOf(values);
    }

    private static ReservationMonitoringContracts.Seek reservationSeek(SourceSeek seek) {
        return seek == null ? null : new ReservationMonitoringContracts.Seek(
                seek.statusChangedAt(), seek.caseId());
    }

    private static WaitingMonitoringContracts.Seek waitingSeek(SourceSeek seek) {
        return seek == null ? null : new WaitingMonitoringContracts.Seek(
                seek.statusChangedAt(), seek.caseId());
    }

    private static MenuHoldMonitoringContracts.Seek menuHoldSeek(SourceSeek seek) {
        return seek == null ? null : new MenuHoldMonitoringContracts.Seek(
                seek.statusChangedAt(), seek.caseId());
    }

    private static PaymentMonitoringContracts.Seek paymentSeek(SourceSeek seek) {
        return seek == null ? null : new PaymentMonitoringContracts.Seek(
                seek.statusChangedAt(), seek.caseId());
    }

    private static Completeness pageCompleteness(
            List<CaseSummary> items,
            Instant asOf,
            List<Instant> dataThrough
    ) {
        if (items.stream().anyMatch(item -> item.completeness() == Completeness.PARTIAL)) {
            return Completeness.PARTIAL;
        }
        if (items.stream().anyMatch(item -> item.completeness() == Completeness.UNAVAILABLE)) {
            return Completeness.UNAVAILABLE;
        }
        if (items.stream().anyMatch(item -> item.completeness() == Completeness.DELAYED)
                || minimum(dataThrough, asOf).isBefore(asOf)) {
            return Completeness.DELAYED;
        }
        return Completeness.COMPLETE;
    }

    private static Completeness cellCompleteness(List<LedgerCell> ledgers) {
        return ledgers.stream().map(LedgerCell::completeness)
                .max(Comparator.comparingInt(AdminMonitoringQueryService::rank))
                .orElse(Completeness.COMPLETE);
    }

    private static Completeness summaryCompleteness(List<LinkedLedgerSummary> ledgers) {
        return ledgers.stream().map(LinkedLedgerSummary::completeness)
                .max(Comparator.comparingInt(AdminMonitoringQueryService::rank))
                .orElse(Completeness.COMPLETE);
    }

    private static int rank(Completeness value) {
        return switch (value) {
            case COMPLETE -> 0;
            case DELAYED -> 1;
            case PARTIAL -> 2;
            case UNAVAILABLE -> 3;
        };
    }

    private static ReconciliationStatus reconciliation(List<LinkedLedgerSummary> ledgers) {
        if (ledgers.stream().anyMatch(value -> value.reconciliationStatus() == ReconciliationStatus.REQUIRED)) {
            return ReconciliationStatus.REQUIRED;
        }
        if (ledgers.stream().anyMatch(value -> value.reconciliationStatus() == ReconciliationStatus.UNKNOWN)) {
            return ReconciliationStatus.UNKNOWN;
        }
        return ReconciliationStatus.MATCHED;
    }

    private static Instant minimum(List<Instant> values, Instant fallback) {
        return values.stream().min(Comparator.naturalOrder()).orElse(fallback);
    }

    private static Instant maximum(List<Instant> values, Instant fallback) {
        return values.stream().filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(fallback);
    }

    private static DependencyFailure failure(Source source, ServiceException failure) {
        return new DependencyFailure(source, failure.getErrorCode().getCode(), true);
    }

    private static boolean validCaseId(CaseType type, String caseId) {
        if (caseId == null) return false;
        return type == CaseType.RESERVATION
                ? caseId.matches("reservation(?:-hold)?:[1-9][0-9]*")
                : caseId.matches("waiting:[1-9][0-9]*");
    }

    private static ServiceException unavailable() {
        return new ServiceException(AdminMonitoringErrorCode.MONITORING_SOURCES_UNAVAILABLE);
    }

    private static ServiceException notFound() {
        return new ServiceException(AdminMonitoringErrorCode.MONITORING_CASE_NOT_FOUND);
    }

    private static Candidate candidate(
            Source source,
            CaseType caseType,
            String caseId,
            String storeId,
            Instant statusChangedAt
    ) {
        return candidate(source, caseType, caseId, storeId, statusChangedAt, true);
    }

    private static Candidate candidate(
            Source source,
            CaseType caseType,
            String caseId,
            String storeId,
            Instant statusChangedAt,
            boolean eligible
    ) {
        return new Candidate(
                caseType,
                caseId,
                storeId,
                statusChangedAt,
                Map.of(source, new SourceSeek(statusChangedAt, caseId)),
                eligible);
    }

    private static void addCandidate(Map<String, Candidate> candidates, Candidate candidate) {
        candidates.merge(candidate.caseId(), candidate, Candidate::merge);
    }

    private static CaseType caseType(String caseId) {
        return caseId.startsWith("waiting:") ? CaseType.WAITING : CaseType.RESERVATION;
    }

    private record Candidate(
            CaseType caseType,
            String caseId,
            String storeId,
            Instant statusChangedAt,
            Map<Source, SourceSeek> sourceSeeks,
            boolean eligible
    ) {
        private Candidate merge(Candidate other) {
            if (caseType != other.caseType || !caseId.equals(other.caseId)) {
                throw new IllegalArgumentException("cannot merge different monitoring cases");
            }
            Map<Source, SourceSeek> mergedSeeks = new EnumMap<>(Source.class);
            mergedSeeks.putAll(sourceSeeks);
            mergedSeeks.putAll(other.sourceSeeks);
            Candidate latest = CANDIDATE_ORDER.compare(this, other) <= 0 ? this : other;
            String mergedStoreId = latest.storeId != null ? latest.storeId
                    : (storeId != null ? storeId : other.storeId);
            return new Candidate(
                    caseType,
                    caseId,
                    mergedStoreId,
                    latest.statusChangedAt,
                    Map.copyOf(mergedSeeks),
                    eligible || other.eligible);
        }

        private Candidate withStatusChangedAt(Instant changedAt) {
            return new Candidate(caseType, caseId, storeId, changedAt, sourceSeeks, eligible);
        }
    }

    @FunctionalInterface
    private interface ChangePageReader {
        ChangePage read(SourceSeek after);
    }

    private record ChangePage(List<Candidate> items, Instant dataThrough) {
    }

    private static final class ChangeScan {
        private final Source source;
        private final SourceSeek startAfter;
        private final ChangePageReader reader;
        private final List<Candidate> occurrences = new ArrayList<>();
        private SourceSeek fetchAfter;
        private boolean hasMore = true;
        private int pages;
        private boolean readSucceeded;

        private ChangeScan(Source source, SourceSeek startAfter, ChangePageReader reader) {
            this.source = source;
            this.startAfter = startAfter;
            this.fetchAfter = startAfter;
            this.reader = reader;
        }

        private void fetch(
                Map<Source, DependencyFailure> failures,
                List<Instant> dataThrough
        ) {
            if (!hasMore) return;
            if (pages >= MAX_SOURCE_PAGES) {
                failures.put(source, new DependencyFailure(
                        source, AdminMonitoringErrorCode.MONITORING_SOURCES_UNAVAILABLE.getCode(), true));
                hasMore = false;
                return;
            }
            try {
                ChangePage page = reader.read(fetchAfter);
                pages++;
                readSucceeded = true;
                dataThrough.add(page.dataThrough());
                occurrences.addAll(page.items());
                hasMore = page.items().size() == SOURCE_PAGE_SIZE;
                if (!page.items().isEmpty()) {
                    Candidate last = page.items().getLast();
                    SourceSeek next = last.sourceSeeks().get(source);
                    if (hasMore && next.equals(fetchAfter)) {
                        failures.put(source, new DependencyFailure(
                                source, AdminMonitoringErrorCode.MONITORING_SOURCES_UNAVAILABLE.getCode(), true));
                        hasMore = false;
                    } else {
                        fetchAfter = next;
                    }
                }
            } catch (ServiceException failure) {
                failures.put(source, failure(source, failure));
                hasMore = false;
            }
        }

        private Source source() {
            return source;
        }

        private SourceSeek startAfter() {
            return startAfter;
        }

        private List<Candidate> occurrences() {
            return occurrences;
        }

        private boolean hasMore() {
            return hasMore;
        }

        private boolean readSucceeded() {
            return readSucceeded;
        }

        private Instant lastFetchedAt() {
            return occurrences.getLast().statusChangedAt();
        }
    }

    private record PreparedCandidate(Candidate ordered, BuiltSummary built) {
    }

    private record PageAssembly(
            List<CaseSummary> items,
            GlobalSeek lastEvaluated,
            boolean hasBufferedAfter,
            Map<String, Candidate> orderByCase
    ) {
    }

    private record BuiltSummary(CaseSummary summary, Map<Source, String> states) {
    }

    private record Hydrated(
            Map<String, ReservationMonitoringContracts.CaseSnapshot> reservations,
            Map<String, WaitingMonitoringContracts.SourceCell> waitings,
            Map<String, MenuHoldMonitoringContracts.SourceCell> menus,
            Map<String, PaymentMonitoringContracts.SourceCell> payments,
            boolean reservationAvailable,
            boolean waitingAvailable,
            boolean menuBatchAvailable,
            boolean paymentBatchAvailable
    ) {
    }
}
