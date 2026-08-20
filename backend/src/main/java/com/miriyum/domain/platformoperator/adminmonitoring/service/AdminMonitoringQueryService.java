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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "miriyum.admin-monitoring", name = "enabled", havingValue = "true")
public class AdminMonitoringQueryService {

    private static final int SOURCE_PAGE_SIZE = 100;
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
        CursorState start = query.cursor() == null
                ? new CursorState(clock.instant(), null, null, null)
                : cursors.decode(query.cursor(), query);
        Instant asOf = start.asOf();
        if (query.changedTo().isAfter(asOf)) {
            throw new ServiceException(AdminMonitoringErrorCode.INVALID_MONITORING_FILTER);
        }

        boolean wantsReservation = query.includes(CaseType.RESERVATION);
        boolean wantsWaiting = query.includes(CaseType.WAITING);
        Map<Source, DependencyFailure> failures = new LinkedHashMap<>();
        List<Instant> dataThrough = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        boolean reservationSucceeded = false;
        boolean waitingSucceeded = false;
        boolean reservationHasMore = false;
        boolean waitingHasMore = false;

        if (wantsReservation) {
            try {
                var page = reservations.findChangedCases(new ReservationMonitoringContracts.ChangeQuery(
                        asOf, query.changedFrom(), query.changedTo(), query.storeId(),
                        reservationStatuses(query), reservationSeek(start.reservationAfter()), SOURCE_PAGE_SIZE));
                reservationSucceeded = true;
                reservationHasMore = page.items().size() == SOURCE_PAGE_SIZE;
                dataThrough.add(page.dataThrough());
                page.items().forEach(item -> candidates.add(new Candidate(
                        CaseType.RESERVATION, item.caseId(), item.storeId(), item.statusChangedAt())));
            } catch (ServiceException failure) {
                failures.put(Source.RESERVATION, failure(Source.RESERVATION, failure));
            }
        }
        if (wantsWaiting) {
            try {
                var page = waitings.findChangedCases(new WaitingMonitoringContracts.ChangeQuery(
                        asOf, query.changedFrom(), query.changedTo(), query.storeId(),
                        query.statusesFor(Source.WAITING), waitingSeek(start.waitingAfter()), SOURCE_PAGE_SIZE));
                waitingSucceeded = true;
                waitingHasMore = page.items().size() == SOURCE_PAGE_SIZE;
                dataThrough.add(page.dataThrough());
                page.items().forEach(item -> candidates.add(new Candidate(
                        CaseType.WAITING, item.caseId(), item.storeId(), item.statusChangedAt())));
            } catch (ServiceException failure) {
                failures.put(Source.WAITING, failure(Source.WAITING, failure));
            }
        }
        if (!(wantsReservation && reservationSucceeded)
                && !(wantsWaiting && waitingSucceeded)) {
            throw unavailable();
        }

        candidates.sort(CANDIDATE_ORDER);
        Hydrated hydrated = hydrate(asOf, candidates, failures, dataThrough);
        if (!(wantsReservation && hydrated.reservationAvailable())
                && !(wantsWaiting && hydrated.waitingAvailable())) {
            throw unavailable();
        }

        List<CaseSummary> items = new ArrayList<>();
        SourceSeek reservationAfter = start.reservationAfter();
        SourceSeek waitingAfter = start.waitingAfter();
        GlobalSeek lastEvaluated = start.lastEvaluated();
        int evaluated = 0;
        for (Candidate candidate : candidates) {
            BuiltSummary built = summary(candidate, asOf, hydrated, failures);
            evaluated++;
            lastEvaluated = new GlobalSeek(
                    candidate.statusChangedAt(), candidate.caseType(), candidate.caseId());
            if (candidate.caseType() == CaseType.RESERVATION) {
                reservationAfter = new SourceSeek(candidate.statusChangedAt(), candidate.caseId());
            } else {
                waitingAfter = new SourceSeek(candidate.statusChangedAt(), candidate.caseId());
            }
            if (built != null && matches(query, built)) {
                items.add(built.summary());
                if (items.size() == query.size()) break;
            }
        }

        boolean trustworthy = failures.isEmpty();
        boolean hasMore = evaluated < candidates.size() || reservationHasMore || waitingHasMore;
        String nextCursor = trustworthy && hasMore && lastEvaluated != null
                ? cursors.encode(new CursorState(
                        asOf, lastEvaluated, reservationAfter, waitingAfter), query)
                : null;
        Completeness completeness = failures.isEmpty()
                ? pageCompleteness(items, asOf, dataThrough)
                : Completeness.PARTIAL;
        return new CasePage(
                items,
                asOf,
                minimum(dataThrough, asOf),
                completeness,
                List.copyOf(failures.values()),
                nextCursor);
    }

    public CaseDetail get(
            PlatformOperatorPrincipal principal,
            CaseType caseType,
            String caseId,
            Instant requestedAsOf
    ) {
        authorization.requireRead(principal);
        Instant asOf = requestedAsOf == null ? clock.instant() : requestedAsOf;
        if (asOf.isAfter(clock.instant()) || !validCaseId(caseType, caseId)) {
            throw new ServiceException(AdminMonitoringErrorCode.INVALID_MONITORING_FILTER);
        }
        return caseType == CaseType.RESERVATION
                ? reservationDetail(principal, caseId, asOf)
                : waitingDetail(principal, caseId, asOf);
    }

    private CaseDetail reservationDetail(
            PlatformOperatorPrincipal principal,
            String caseId,
            Instant asOf
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
        authorization.requireDetail(principal, caseId, caseVersion);

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
            Instant asOf
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
        authorization.requireDetail(principal, caseId, caseVersion);
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

    private Hydrated hydrate(
            Instant asOf,
            List<Candidate> candidates,
            Map<Source, DependencyFailure> failures,
            List<Instant> dataThrough
    ) {
        List<String> reservationIds = candidates.stream()
                .filter(candidate -> candidate.caseType() == CaseType.RESERVATION)
                .map(Candidate::caseId).toList();
        List<String> waitingIds = candidates.stream()
                .filter(candidate -> candidate.caseType() == CaseType.WAITING)
                .map(Candidate::caseId).toList();
        Map<String, ReservationMonitoringContracts.CaseSnapshot> reservationCases = Map.of();
        Map<String, WaitingMonitoringContracts.SourceCell> waitingCases = Map.of();
        boolean reservationAvailable = true;
        boolean waitingAvailable = true;
        if (!reservationIds.isEmpty()) {
            try {
                var result = reservations.findCases(new ReservationMonitoringContracts.BatchQuery(asOf, reservationIds));
                reservationCases = index(result.cases(), ReservationMonitoringContracts.CaseSnapshot::caseId);
                dataThrough.add(result.dataThrough());
            } catch (ServiceException failure) {
                reservationAvailable = false;
                failures.put(Source.RESERVATION, failure(Source.RESERVATION, failure));
            }
        }
        if (!waitingIds.isEmpty()) {
            try {
                var result = waitings.findCases(new WaitingMonitoringContracts.BatchQuery(asOf, waitingIds));
                waitingCases = index(result.cells(), WaitingMonitoringContracts.SourceCell::caseId);
                dataThrough.add(result.dataThrough());
            } catch (ServiceException failure) {
                waitingAvailable = false;
                failures.put(Source.WAITING, failure(Source.WAITING, failure));
            }
        }

        Map<String, MenuHoldMonitoringContracts.SourceCell> menus = Map.of();
        if (!reservationIds.isEmpty()) {
            try {
                var result = menuHolds.findCases(new MenuHoldMonitoringContracts.BatchQuery(asOf, reservationIds));
                menus = index(result.cells(), MenuHoldMonitoringContracts.SourceCell::caseId);
                dataThrough.add(result.dataThrough());
            } catch (ServiceException failure) {
                failures.put(Source.MENU_HOLD, failure(Source.MENU_HOLD, failure));
            }
        }
        Map<String, PaymentMonitoringContracts.SourceCell> paymentCases = new LinkedHashMap<>();
        for (int start = 0; start < candidates.size(); start += SOURCE_PAGE_SIZE) {
            List<String> ids = candidates.subList(start, Math.min(start + SOURCE_PAGE_SIZE, candidates.size()))
                    .stream().map(Candidate::caseId).toList();
            try {
                var result = payments.findCases(new PaymentMonitoringContracts.BatchQuery(asOf, ids));
                result.cells().forEach(cell -> paymentCases.put(cell.caseId(), cell));
                dataThrough.add(result.dataThrough());
            } catch (ServiceException failure) {
                failures.put(Source.PAYMENT, failure(Source.PAYMENT, failure));
                break;
            }
        }
        return new Hydrated(
                reservationCases, waitingCases, menus, Map.copyOf(paymentCases),
                reservationAvailable, waitingAvailable);
    }

    private BuiltSummary summary(
            Candidate candidate,
            Instant asOf,
            Hydrated hydrated,
            Map<Source, DependencyFailure> failures
    ) {
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
            addMenuSummary(candidate.caseId(), hydrated, failures, ledgers, states);
            addPaymentSummary(candidate.caseId(), hydrated, failures, ledgers, states);
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
            return new BuiltSummary(new CaseSummary(
                    CaseType.RESERVATION, candidate.caseId(), snapshot.storeId(), lifecycle,
                    primary.sourceStatus(), candidate.statusChangedAt(), primary.statusVersion() + 1L,
                    asOf, through, completeness, reconciliation, ledgers), states);
        }
        var cell = hydrated.waitings().get(candidate.caseId());
        if (cell == null || cell.state() == null) return null;
        var state = cell.state();
        List<LinkedLedgerSummary> ledgers = new ArrayList<>();
        ledgers.add(summary(cell));
        Map<Source, String> states = new EnumMap<>(Source.class);
        states.put(Source.WAITING, state.sourceStatus());
        addPaymentSummary(candidate.caseId(), hydrated, failures, ledgers, states);
        List<Instant> throughValues = new ArrayList<>(List.of(cell.dataThrough()));
        Optional.ofNullable(hydrated.payments().get(candidate.caseId()))
                .map(PaymentMonitoringContracts.SourceCell::dataThrough)
                .ifPresent(throughValues::add);
        return new BuiltSummary(new CaseSummary(
                CaseType.WAITING, candidate.caseId(), state.storeId(),
                mapper.lifecycle(Source.WAITING, state.sourceStatus()), state.sourceStatus(),
                candidate.statusChangedAt(), state.statusVersion() + 1L,
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
            if (!required.isEmpty() && !required.contains(built.states().get(source))) return false;
        }
        return true;
    }

    private static void addMenuSummary(
            String caseId,
            Hydrated hydrated,
            Map<Source, DependencyFailure> failures,
            List<LinkedLedgerSummary> ledgers,
            Map<Source, String> states
    ) {
        if (failures.containsKey(Source.MENU_HOLD)) {
            ledgers.add(new LinkedLedgerSummary(
                    Source.MENU_HOLD, false, Completeness.UNAVAILABLE, ReconciliationStatus.UNKNOWN));
            return;
        }
        var cell = hydrated.menus().get(caseId);
        if (cell == null) {
            ledgers.add(new LinkedLedgerSummary(
                    Source.MENU_HOLD, false, Completeness.COMPLETE, ReconciliationStatus.MATCHED));
        } else {
            ledgers.add(summary(cell));
            if (cell.state() != null) states.put(Source.MENU_HOLD, cell.state().sourceStatus());
        }
    }

    private static void addPaymentSummary(
            String caseId,
            Hydrated hydrated,
            Map<Source, DependencyFailure> failures,
            List<LinkedLedgerSummary> ledgers,
            Map<Source, String> states
    ) {
        if (failures.containsKey(Source.PAYMENT)) {
            ledgers.add(new LinkedLedgerSummary(
                    Source.PAYMENT, false, Completeness.UNAVAILABLE, ReconciliationStatus.UNKNOWN));
            return;
        }
        var cell = hydrated.payments().get(caseId);
        if (cell == null) {
            ledgers.add(new LinkedLedgerSummary(
                    Source.PAYMENT, false, Completeness.COMPLETE, ReconciliationStatus.MATCHED));
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

    private static <T> Map<String, T> index(
            List<T> values,
            java.util.function.Function<T, String> key
    ) {
        Map<String, T> result = new LinkedHashMap<>();
        values.forEach(value -> result.put(key.apply(value), value));
        return Map.copyOf(result);
    }

    private record Candidate(CaseType caseType, String caseId, String storeId, Instant statusChangedAt) {
    }

    private record BuiltSummary(CaseSummary summary, Map<Source, String> states) {
    }

    private record Hydrated(
            Map<String, ReservationMonitoringContracts.CaseSnapshot> reservations,
            Map<String, WaitingMonitoringContracts.SourceCell> waitings,
            Map<String, MenuHoldMonitoringContracts.SourceCell> menus,
            Map<String, PaymentMonitoringContracts.SourceCell> payments,
            boolean reservationAvailable,
            boolean waitingAvailable
    ) {
    }
}
