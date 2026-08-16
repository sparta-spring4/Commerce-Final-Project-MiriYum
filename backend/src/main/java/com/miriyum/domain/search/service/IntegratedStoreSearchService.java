package com.miriyum.domain.search.service;

import com.miriyum.domain.reservation.dto.request.ReservationSearchAvailabilityCondition;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.service.ReservationSearchAvailabilityService;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.recommendation.ranking.RankedRecommendation;
import com.miriyum.domain.recommendation.ranking.RecommendationAvailability;
import com.miriyum.domain.recommendation.ranking.RecommendationCursorKey;
import com.miriyum.domain.recommendation.ranking.RecommendationReason;
import com.miriyum.domain.recommendation.ranking.RecommendationSearchCandidate;
import com.miriyum.domain.recommendation.ranking.RecommendationSearchSignals;
import com.miriyum.domain.recommendation.ranking.StoreRecommendationService;
import com.miriyum.domain.search.dto.publicapi.IntegratedStoreSearchData;
import com.miriyum.domain.search.dto.publicapi.IntegratedStoreSearchItem;
import com.miriyum.domain.search.dto.publicapi.NormalizedSearchCondition;
import com.miriyum.domain.search.dto.publicapi.PublicStoreCoordinates;
import com.miriyum.domain.search.dto.publicapi.PublicStoreModes;
import com.miriyum.domain.search.dto.publicapi.ReservationAvailability;
import com.miriyum.domain.search.config.StoreSearchCandidateLimit;
import com.miriyum.domain.search.interpreter.InterpretationResult;
import com.miriyum.domain.search.interpreter.InterpretedSearchCondition;
import com.miriyum.domain.search.interpreter.PriceRange;
import com.miriyum.domain.search.query.IntegratedSearchCursorCodec;
import com.miriyum.domain.search.query.IntegratedStoreSearchQuery;
import com.miriyum.domain.search.query.IntegratedStoreSearchSort;
import com.miriyum.domain.search.repository.IntegratedStoreSearchCandidate;
import com.miriyum.domain.search.repository.IntegratedStoreSearchRepository;
import com.miriyum.domain.search.repository.IntegratedStoreSearchSlice;
import com.miriyum.domain.search.semantic.SemanticMenuSearchService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 결정적 해석, QueryDSL 후보와 최신 Store·Reservation 상태를 조합한다. */
@Service
public class IntegratedStoreSearchService {

    private final IntegratedSearchInterpreter interpreter;
    private final IntegratedStoreSearchRepository repository;
    private final ReservationSearchAvailabilityService reservationService;
    private final StoreSearchCandidateLimit candidateLimit;
    private final IntegratedSearchCursorCodec cursorCodec;
    private final StoreRecommendationService recommendationService;
    private final SemanticMenuSearchService semanticSearchService;
    private final Clock clock;

    public IntegratedStoreSearchService(
            IntegratedSearchInterpreter interpreter,
            IntegratedStoreSearchRepository repository,
            ReservationSearchAvailabilityService reservationService,
            StoreSearchCandidateLimit candidateLimit,
            IntegratedSearchCursorCodec cursorCodec,
            StoreRecommendationService recommendationService,
            SemanticMenuSearchService semanticSearchService,
            Clock clock
    ) {
        this.interpreter = interpreter;
        this.repository = repository;
        this.reservationService = reservationService;
        this.candidateLimit = candidateLimit;
        this.cursorCodec = cursorCodec;
        this.recommendationService = recommendationService;
        this.semanticSearchService = semanticSearchService;
        this.clock = clock;
    }

    /** 검색 원문을 저장하지 않고 현재 MySQL 상태를 재검증한 cursor 결과를 반환한다. */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public IntegratedStoreSearchData search(
            String searchInput,
            boolean includesInfants,
            boolean availableOnly,
            String sort,
            String cursor,
            Integer size
    ) {
        return search(
                null,
                searchInput,
                includesInfants,
                availableOnly,
                sort,
                cursor,
                size);
    }

    /** 유효한 소비자 principal이 있으면 추천 정렬에만 자기 이력을 가산한다. */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public IntegratedStoreSearchData search(
            Long consumerAccountId,
            String searchInput,
            boolean includesInfants,
            boolean availableOnly,
            String sort,
            String cursor,
            Integer size
    ) {
        InterpretationResult interpretation = interpreter.interpret(searchInput);
        InterpretedSearchCondition condition = interpretation.condition();
        boolean reservationRequested = hasReservationDate(condition);
        if ((includesInfants || availableOnly) && !reservationRequested) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }

        int requestedSize = size == null ? 20 : size;
        String principalScope = cursorCodec.principalScope(consumerAccountId);
        IntegratedStoreSearchQuery requestQuery = IntegratedStoreSearchQuery.from(
                condition,
                includesInfants,
                availableOnly,
                principalScope,
                sort,
                cursor,
                requestedSize,
                cursorCodec);
        if (requestQuery.sort() == IntegratedStoreSearchSort.RECOMMENDATION_DESC) {
            return searchRecommendations(
                    consumerAccountId,
                    interpretation,
                    condition,
                    includesInfants,
                    availableOnly,
                    requestQuery);
        }
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
                    condition,
                    includesInfants,
                    availableOnly,
                    principalScope,
                    sort,
                    scanCursor,
                    requestedSize,
                    cursorCodec);
            CandidateBatch batch = supplementWithSemanticCandidates(
                    query, repository.search(query), scanLimit);
            IntegratedStoreSearchSlice slice = batch.slice();
            List<IntegratedStoreSearchCandidate> original = slice.content();
            List<IntegratedStoreSearchCandidate> before =
                    repository.refreshCurrentlyPublic(original);
            AvailabilityBatch availability = availabilityById(
                    before, condition, includesInfants);
            List<IntegratedStoreSearchCandidate> after = availability.valid()
                    ? repository.refreshCurrentlyPublic(before)
                    : List.of();
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
                ReservationAvailability candidateAvailability =
                        availability.values().getOrDefault(
                                current.storeId(),
                                reservationRequested
                                        ? ReservationAvailability.UNAVAILABLE
                                        : ReservationAvailability.NOT_REQUESTED);
                candidateAvailability = reconcileCurrentState(
                        current, candidateAvailability, reservationRequested);
                if (availableOnly
                        && candidateAvailability != ReservationAvailability.AVAILABLE) {
                    continue;
                }
                items.add(toItem(current, candidateAvailability));
                if (items.size() == requestedSize) {
                    boolean hasMore = !batch.semanticSupplemented()
                            && scannedCandidates < scanLimit
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
                null,
                responseCursor);
    }

    private IntegratedStoreSearchData searchRecommendations(
            Long consumerAccountId,
            InterpretationResult interpretation,
            InterpretedSearchCondition condition,
            boolean includesInfants,
            boolean availableOnly,
            IntegratedStoreSearchQuery requestQuery
    ) {
        int scanLimit = candidateLimit.value();
        int scanPageSize = Math.min(50, scanLimit);
        if (scanPageSize < 1) {
            throw new IllegalStateException("store search candidate limit must be positive");
        }
        int scannedCandidates = 0;
        String scanCursor = null;
        List<RecommendationSearchCandidate> rankingCandidates = new ArrayList<>();
        Map<Long, CandidateState> stateById = new LinkedHashMap<>();

        while (scannedCandidates < scanLimit) {
            IntegratedStoreSearchQuery scanQuery = IntegratedStoreSearchQuery.from(
                    condition,
                    includesInfants,
                    availableOnly,
                    cursorCodec.principalScope(consumerAccountId),
                    IntegratedStoreSearchSort.RELEVANCE_DESC.externalValue(),
                    scanCursor,
                    scanPageSize,
                    cursorCodec);
            CandidateBatch batch = supplementWithSemanticCandidates(
                    scanQuery, repository.search(scanQuery), scanLimit);
            IntegratedStoreSearchSlice slice = batch.slice();
            List<IntegratedStoreSearchCandidate> original = slice.content();
            List<IntegratedStoreSearchCandidate> before =
                    repository.refreshCurrentlyPublic(original);
            AvailabilityBatch availability = availabilityById(
                    before, condition, includesInfants);
            List<IntegratedStoreSearchCandidate> after = availability.valid()
                    ? repository.refreshCurrentlyPublic(before)
                    : List.of();
            Map<Long, IntegratedStoreSearchCandidate> currentById = new LinkedHashMap<>();
            after.forEach(candidate -> currentById.put(candidate.storeId(), candidate));

            for (IntegratedStoreSearchCandidate originalCandidate : original) {
                if (scannedCandidates >= scanLimit) {
                    break;
                }
                scannedCandidates++;
                IntegratedStoreSearchCandidate current =
                        currentById.get(originalCandidate.storeId());
                if (current == null) {
                    continue;
                }
                ReservationAvailability candidateAvailability =
                        availability.values().getOrDefault(
                                current.storeId(),
                                hasReservationDate(condition)
                                        ? ReservationAvailability.UNAVAILABLE
                                        : ReservationAvailability.NOT_REQUESTED);
                candidateAvailability = reconcileCurrentState(
                        current,
                        candidateAvailability,
                        hasReservationDate(condition));
                if (availableOnly
                        && candidateAvailability != ReservationAvailability.AVAILABLE) {
                    continue;
                }
                if (stateById.putIfAbsent(
                        current.storeId(),
                        new CandidateState(current, candidateAvailability)) != null) {
                    continue;
                }
                rankingCandidates.add(new RecommendationSearchCandidate(
                        current.storeId(),
                        current.relevanceTier(),
                        toRecommendationAvailability(candidateAvailability),
                        null));
            }

            scanCursor = slice.nextCursor();
            if (scanCursor == null || original.isEmpty()) {
                break;
            }
        }

        List<RankedRecommendation> ranked = recommendationService.rank(
                consumerAccountId,
                rankingCandidates,
                new RecommendationSearchSignals(
                        condition.storeCategoryCodes(),
                        condition.menuCategoryCodes(),
                        condition.tagCodes()),
                clock.instant());
        RecommendationCursorKey pageKey = requestQuery.cursor()
                .map(decoded -> parseRecommendationCursor(
                        decoded.sortValue(), decoded.storeId()))
                .orElse(null);
        List<RankedRecommendation> remaining = ranked.stream()
                .filter(candidate -> pageKey == null || pageKey.isAfter(candidate))
                .toList();
        int pageSize = Math.min(requestQuery.size(), remaining.size());
        List<RankedRecommendation> page = remaining.subList(0, pageSize);
        List<IntegratedStoreSearchItem> items = page.stream()
                .map(result -> new RankedCandidateState(
                        result,
                        stateById.get(result.candidate().storeId())))
                .filter(result -> result.state() != null)
                .map(result -> toItem(
                        result.state().candidate(),
                        result.state().availability(),
                        result.ranked().reason()))
                .toList();
        String nextCursor = remaining.size() > pageSize && !page.isEmpty()
                ? recommendationCursor(requestQuery, page.getLast())
                : null;
        return new IntegratedStoreSearchData(
                items,
                normalized(condition),
                interpretation.warnings(),
                interpretation.ruleVersion(),
                interpretation.vocabularyVersion(),
                StoreRecommendationService.RULE_VERSION,
                nextCursor);
    }

    private String recommendationCursor(
            IntegratedStoreSearchQuery requestQuery,
            RankedRecommendation last
    ) {
        RecommendationCursorKey key = RecommendationCursorKey.from(last);
        return cursorCodec.encode(
                requestQuery,
                key.serialize(),
                key.storeId());
    }

    private static RecommendationCursorKey parseRecommendationCursor(
            String value,
            long storeId
    ) {
        try {
            return RecommendationCursorKey.parse(value, storeId);
        } catch (IllegalArgumentException exception) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private AvailabilityBatch availabilityById(
            List<IntegratedStoreSearchCandidate> candidates,
            InterpretedSearchCondition condition,
            boolean includesInfants
    ) {
        Map<Long, ReservationAvailability> byId = new LinkedHashMap<>();
        if (!hasReservationDate(condition)) {
            candidates.forEach(candidate -> byId.put(
                    candidate.storeId(), ReservationAvailability.NOT_REQUESTED));
            return new AvailabilityBatch(true, byId);
        }
        List<Long> storeIds = candidates.stream()
                .map(IntegratedStoreSearchCandidate::storeId)
                .toList();
        if (storeIds.isEmpty()) {
            return new AvailabilityBatch(true, byId);
        }
        List<ReservationAvailabilityResult> results = reservationService.getAvailabilities(
                storeIds,
                new ReservationSearchAvailabilityCondition(
                        condition.reservationDate(),
                        condition.reservationTime(),
                        null,
                        condition.partySize(),
                        includesInfants));
        if (!matches(storeIds, results)) {
            return new AvailabilityBatch(false, Map.of());
        }
        results.forEach(result -> byId.put(
                result.storeId(),
                result.availability() == ReservationAvailabilityStatus.AVAILABLE
                        ? ReservationAvailability.AVAILABLE
                        : ReservationAvailability.UNAVAILABLE));
        return new AvailabilityBatch(true, byId);
    }

    private CandidateBatch supplementWithSemanticCandidates(
            IntegratedStoreSearchQuery query,
            IntegratedStoreSearchSlice exact,
            int scanLimit
    ) {
        if (query.cursor().isPresent()
                || exact.nextCursor() != null
                || exact.content().size() >= query.size()
                || query.remainingKeyword().isBlank()) {
            return new CandidateBatch(exact, false);
        }
        var hits = semanticSearchService.search(query.remainingKeyword(), scanLimit);
        if (hits.isEmpty()) {
            return new CandidateBatch(exact, false);
        }
        IntegratedStoreSearchSlice semantic = repository.searchSemantic(query, hits);
        Map<Long, IntegratedStoreSearchCandidate> merged = new LinkedHashMap<>();
        exact.content().forEach(candidate -> merged.put(candidate.storeId(), candidate));
        semantic.content().forEach(candidate -> merged.putIfAbsent(
                candidate.storeId(), candidate));
        return new CandidateBatch(new IntegratedStoreSearchSlice(
                List.copyOf(merged.values()), null), true);
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

    private static boolean hasReservationDate(InterpretedSearchCondition condition) {
        return condition.reservationDate() != null;
    }

    private static RecommendationAvailability toRecommendationAvailability(
            ReservationAvailability availability
    ) {
        return switch (availability) {
            case AVAILABLE -> RecommendationAvailability.AVAILABLE;
            case NOT_REQUESTED -> RecommendationAvailability.NOT_REQUESTED;
            case UNAVAILABLE -> RecommendationAvailability.UNAVAILABLE;
        };
    }

    private static IntegratedStoreSearchItem toItem(
            IntegratedStoreSearchCandidate candidate,
            ReservationAvailability availability
    ) {
        return toItem(candidate, availability, null);
    }

    private static IntegratedStoreSearchItem toItem(
            IntegratedStoreSearchCandidate candidate,
            ReservationAvailability availability,
            RecommendationReason reason
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
                coordinates,
                reason);
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

    private record AvailabilityBatch(
            boolean valid,
            Map<Long, ReservationAvailability> values
    ) {
    }

    private record CandidateState(
            IntegratedStoreSearchCandidate candidate,
            ReservationAvailability availability
    ) {
    }

    private record RankedCandidateState(
            RankedRecommendation ranked,
            CandidateState state
    ) {
    }

    private record CandidateBatch(
            IntegratedStoreSearchSlice slice,
            boolean semanticSupplemented
    ) {
    }
}
