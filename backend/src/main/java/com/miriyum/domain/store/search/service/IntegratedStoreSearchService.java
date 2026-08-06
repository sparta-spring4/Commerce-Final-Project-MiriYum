package com.miriyum.domain.store.search.service;

import com.miriyum.domain.reservation.dto.request.ReservationAvailabilityCondition;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.search.dto.IntegratedStoreSearchData;
import com.miriyum.domain.store.search.dto.IntegratedStoreSearchItem;
import com.miriyum.domain.store.search.dto.NormalizedSearchCondition;
import com.miriyum.domain.store.search.dto.PublicStoreCoordinates;
import com.miriyum.domain.store.search.dto.PublicStoreModes;
import com.miriyum.domain.store.search.dto.ReservationAvailability;
import com.miriyum.domain.store.search.config.StoreSearchCandidateLimit;
import com.miriyum.domain.store.search.interpreter.InterpretationResult;
import com.miriyum.domain.store.search.interpreter.InterpretedSearchCondition;
import com.miriyum.domain.store.search.interpreter.PriceRange;
import com.miriyum.domain.store.search.query.IntegratedStoreSearchQuery;
import com.miriyum.domain.store.search.repository.IntegratedStoreSearchCandidate;
import com.miriyum.domain.store.search.repository.IntegratedStoreSearchRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 결정적 해석, QueryDSL 후보와 최신 Store·Reservation 상태를 조합한다. */
@Service
public class IntegratedStoreSearchService {

    private final IntegratedSearchInterpreter interpreter;
    private final IntegratedStoreSearchRepository repository;
    private final ReservationService reservationService;
    private final StoreSearchCandidateLimit candidateLimit;

    public IntegratedStoreSearchService(
            IntegratedSearchInterpreter interpreter,
            IntegratedStoreSearchRepository repository,
            ReservationService reservationService,
            StoreSearchCandidateLimit candidateLimit
    ) {
        this.interpreter = interpreter;
        this.repository = repository;
        this.reservationService = reservationService;
        this.candidateLimit = candidateLimit;
    }

    /** 검색 원문을 저장하지 않고 현재 MySQL 상태를 재검증한 cursor 결과를 반환한다. */
    @Transactional(readOnly = true)
    public IntegratedStoreSearchData search(
            String searchInput,
            boolean includesInfants,
            boolean availableOnly,
            String sort,
            String cursor,
            Integer size
    ) {
        InterpretationResult interpretation = interpreter.interpret(searchInput);
        InterpretedSearchCondition condition = interpretation.condition();
        boolean completeReservation = hasCompleteReservation(condition);
        if ((includesInfants || availableOnly) && !completeReservation) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }

        int requestedSize = size == null ? 20 : size;
        List<IntegratedStoreSearchItem> items = new ArrayList<>(requestedSize);
        String scanCursor = cursor;
        String responseCursor = null;
        boolean finished = false;
        int scannedCandidates = 0;
        int scanLimit = candidateLimit.value();
        while (!finished
                && items.size() < requestedSize
                && scannedCandidates < scanLimit) {
            IntegratedStoreSearchQuery query = IntegratedStoreSearchQuery.from(
                    condition, sort, scanCursor, requestedSize);
            var slice = repository.search(query);
            List<IntegratedStoreSearchCandidate> original = slice.content();
            List<IntegratedStoreSearchCandidate> before =
                    repository.refreshCurrentlyPublic(original);
            Map<Long, ReservationAvailability> availability = availabilityById(
                    before, condition, includesInfants);
            List<IntegratedStoreSearchCandidate> after =
                    repository.refreshCurrentlyPublic(before);
            Map<Long, IntegratedStoreSearchCandidate> currentById = new LinkedHashMap<>();
            after.forEach(candidate -> currentById.put(candidate.storeId(), candidate));

            IntegratedStoreSearchCandidate lastProcessed = null;
            for (IntegratedStoreSearchCandidate originalCandidate : original) {
                if (scannedCandidates >= scanLimit) {
                    finished = true;
                    responseCursor = null;
                    break;
                }
                scannedCandidates++;
                lastProcessed = originalCandidate;
                IntegratedStoreSearchCandidate current =
                        currentById.get(originalCandidate.storeId());
                if (current == null) {
                    continue;
                }
                ReservationAvailability candidateAvailability = availability.getOrDefault(
                        current.storeId(),
                        completeReservation
                                ? ReservationAvailability.UNAVAILABLE
                                : ReservationAvailability.NOT_REQUESTED);
                candidateAvailability = reconcileCurrentState(
                        current, candidateAvailability, completeReservation);
                if (availableOnly
                        && candidateAvailability != ReservationAvailability.AVAILABLE) {
                    continue;
                }
                items.add(toItem(current, candidateAvailability));
                if (items.size() == requestedSize) {
                    boolean hasMore = scannedCandidates < scanLimit
                            && (!originalCandidate.equals(original.getLast())
                            || slice.nextCursor() != null);
                    responseCursor = hasMore
                            ? repository.cursorAfter(query, originalCandidate)
                            : null;
                    finished = true;
                    break;
                }
            }
            if (finished) {
                break;
            }
            if (scannedCandidates >= scanLimit) {
                responseCursor = null;
                finished = true;
                break;
            }
            scanCursor = slice.nextCursor();
            if (scanCursor == null || original.isEmpty()) {
                responseCursor = null;
                finished = true;
            } else if (lastProcessed != null) {
                responseCursor = scanCursor;
            }
        }

        return new IntegratedStoreSearchData(
                items,
                normalized(condition),
                interpretation.warnings(),
                interpretation.ruleVersion(),
                interpretation.vocabularyVersion(),
                responseCursor);
    }

    private Map<Long, ReservationAvailability> availabilityById(
            List<IntegratedStoreSearchCandidate> candidates,
            InterpretedSearchCondition condition,
            boolean includesInfants
    ) {
        Map<Long, ReservationAvailability> byId = new LinkedHashMap<>();
        if (!hasCompleteReservation(condition)) {
            candidates.forEach(candidate -> byId.put(
                    candidate.storeId(), ReservationAvailability.NOT_REQUESTED));
            return byId;
        }
        List<Long> storeIds = candidates.stream()
                .map(IntegratedStoreSearchCandidate::storeId)
                .toList();
        if (storeIds.isEmpty()) {
            return byId;
        }
        List<ReservationAvailabilityResult> results = reservationService.getAvailabilities(
                storeIds,
                new ReservationAvailabilityCondition(
                        condition.reservationDate(),
                        condition.reservationTime(),
                        null,
                        condition.partySize(),
                        includesInfants));
        if (!matches(storeIds, results)) {
            storeIds.forEach(storeId -> byId.put(
                    storeId, ReservationAvailability.UNAVAILABLE));
            return byId;
        }
        results.forEach(result -> byId.put(
                result.storeId(),
                result.availability() == ReservationAvailabilityStatus.AVAILABLE
                        ? ReservationAvailability.AVAILABLE
                        : ReservationAvailability.UNAVAILABLE));
        return byId;
    }

    private static boolean matches(
            List<Long> storeIds,
            List<ReservationAvailabilityResult> results
    ) {
        if (results == null || storeIds.size() != results.size()) {
            return false;
        }
        for (int index = 0; index < storeIds.size(); index++) {
            ReservationAvailabilityResult result = results.get(index);
            if (result == null || result.storeId() != storeIds.get(index)) {
                return false;
            }
        }
        return true;
    }

    private static ReservationAvailability reconcileCurrentState(
            IntegratedStoreSearchCandidate candidate,
            ReservationAvailability availability,
            boolean reservationRequested
    ) {
        if (!reservationRequested) {
            return ReservationAvailability.NOT_REQUESTED;
        }
        if (candidate.operationStatus() != OperationStatus.OPEN
                || !candidate.reservationEnabled()) {
            return ReservationAvailability.UNAVAILABLE;
        }
        return availability;
    }

    private static boolean hasCompleteReservation(InterpretedSearchCondition condition) {
        return condition.partySize() != null
                && condition.reservationDate() != null
                && condition.reservationTime() != null;
    }

    private static IntegratedStoreSearchItem toItem(
            IntegratedStoreSearchCandidate candidate,
            ReservationAvailability availability
    ) {
        PublicStoreCoordinates coordinates = candidate.latitude() == null
                || candidate.longitude() == null
                ? null
                : new PublicStoreCoordinates(candidate.latitude(), candidate.longitude());
        return new IntegratedStoreSearchItem(
                Long.toString(candidate.storeId()),
                candidate.name(),
                candidate.region(),
                candidate.address(),
                candidate.storeCategoryCode(),
                candidate.operationStatus(),
                new PublicStoreModes(
                        candidate.reservationEnabled(),
                        candidate.menuHoldEnabled(),
                        candidate.pickupEnabled()),
                availability,
                coordinates);
    }

    private static NormalizedSearchCondition normalized(
            InterpretedSearchCondition condition
    ) {
        PriceRange price = condition.priceRange();
        return new NormalizedSearchCondition(
                condition.regionCodes(),
                condition.storeCategoryCodes(),
                condition.menuCategoryCodes(),
                condition.tagCodes(),
                price == null ? null : price.minInclusive(),
                price == null ? null : price.maxInclusive(),
                condition.partySize(),
                condition.reservationDate(),
                condition.reservationTime(),
                condition.remainingKeyword());
    }
}
