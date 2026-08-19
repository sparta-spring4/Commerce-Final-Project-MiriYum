package com.miriyum.domain.search.service;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.search.config.StoreSearchCandidateLimit;
import com.miriyum.domain.search.dto.publicapi.PublicStoreCoordinates;
import com.miriyum.domain.search.dto.publicapi.PublicStoreModes;
import com.miriyum.domain.search.dto.publicapi.PublicStoreSummary;
import com.miriyum.domain.search.dto.publicapi.ReservationAvailability;
import com.miriyum.domain.search.model.StoreSearchQuery;
import com.miriyum.domain.search.repository.StoreSearchCandidate;
import com.miriyum.domain.search.repository.StoreSearchRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.domain.reservation.dto.request.ReservationAvailabilityCondition;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.service.ReservationService;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 공개 매장 후보, 예약 도메인의 일괄 가용성, 응답 직전 최신 매장 상태를 조합한다.
 */
@Service
public class StoreSearchCoreService {

    static final int AVAILABILITY_BATCH_SIZE = 200;

    private final StoreSearchCatalogPolicy catalogPolicy;
    private final StoreSearchRepository repository;
    private final ReservationService reservationService;
    private final StoreSearchCandidateLimit candidateLimit;

    /**
     * 공개 카테고리 정책, 후보 저장소, 예약 공개 서비스를 구성한다.
     */
    public StoreSearchCoreService(
            StoreSearchCatalogPolicy catalogPolicy,
            StoreSearchRepository repository,
            ReservationService reservationService,
            StoreSearchCandidateLimit candidateLimit
    ) {
        this.catalogPolicy = catalogPolicy;
        this.repository = repository;
        this.reservationService = reservationService;
        this.candidateLimit = candidateLimit;
    }

    /**
     * 예약 조건이 없는 호환 호출을 실행한다.
     */
    public Page<PublicStoreSummary> searchWithoutAvailability(StoreSearchQuery query) {
        if (query.reservationCondition() != null) {
            throw new IllegalStateException(
                    "reservation availability contract is not connected");
        }
        return search(query, false);
    }

    public Page<PublicStoreSummary> search(StoreSearchQuery query, boolean includesInfants) {
        catalogPolicy.requireActiveStoreCategory(query.storeCategoryCode());
        if (query.availableOnly()) {
            return searchAvailableOnly(query, includesInfants);
        }
        Page<StoreSearchCandidate> candidates = repository.search(query);
        List<StoreSearchCandidate> currentCandidates =
                repository.refreshCurrentlyPublic(candidates.getContent());
        List<ReservationAvailability> availabilities = availabilityFor(
                currentCandidates, query, includesInfants);
        Map<Long, ReservationAvailability> availabilityById = availabilityById(
                currentCandidates, availabilities);
        List<StoreSearchCandidate> finalCandidates =
                repository.refreshCurrentlyPublic(currentCandidates);
        List<PublicStoreSummary> summaries = finalCandidates.stream()
                .map(candidate -> toSummary(candidate,
                        reconcileLatestStoreState(
                                candidate, availabilityById.get(candidate.storeId()))))
                .toList();
        long removed = candidates.getNumberOfElements() - summaries.size();
        long total = Math.max(0L, candidates.getTotalElements() - removed);
        return new PageImpl<>(summaries, candidates.getPageable(), total);
    }

    private Page<PublicStoreSummary> searchAvailableOnly(
            StoreSearchQuery query,
            boolean includesInfants
    ) {
        long requestedOffset = (long) query.page() * query.size();
        long totalAvailable = 0;
        List<PublicStoreSummary> page = new ArrayList<>(query.size());
        List<StoreSearchCandidate> candidates = repository.searchAll(
                query, candidateLimit.value());
        for (int start = 0; start < candidates.size(); start += AVAILABILITY_BATCH_SIZE) {
            List<StoreSearchCandidate> chunk = candidates.subList(
                    start, Math.min(start + AVAILABILITY_BATCH_SIZE, candidates.size()));
            List<StoreSearchCandidate> current = repository.refreshCurrentlyPublic(chunk);
            List<ReservationAvailability> availabilities = availabilityFor(
                    current, query, includesInfants);
            Map<Long, ReservationAvailability> availabilityById = availabilityById(
                    current, availabilities);
            List<StoreSearchCandidate> finalCandidates =
                    repository.refreshCurrentlyPublic(current);
            for (StoreSearchCandidate candidate : finalCandidates) {
                if (reconcileLatestStoreState(
                        candidate, availabilityById.get(candidate.storeId()))
                        != ReservationAvailability.AVAILABLE) {
                    continue;
                }
                if (totalAvailable >= requestedOffset && page.size() < query.size()) {
                    page.add(toSummary(candidate, ReservationAvailability.AVAILABLE));
                }
                totalAvailable++;
            }
        }
        return new PageImpl<>(
                page,
                PageRequest.of(query.page(), query.size()),
                totalAvailable);
    }

    private List<ReservationAvailability> availabilityFor(
            List<StoreSearchCandidate> candidates,
            StoreSearchQuery query,
            boolean includesInfants
    ) {
        if (query.reservationCondition() == null) {
            return candidates.stream()
                    .map(ignored -> ReservationAvailability.NOT_REQUESTED)
                    .toList();
        }
        List<Long> storeIds = candidates.stream().map(StoreSearchCandidate::storeId).toList();
        if (storeIds.isEmpty()) {
            return List.of();
        }
        var condition = query.reservationCondition();
        List<ReservationAvailabilityResult> results = reservationService.getAvailabilities(
                storeIds,
                new ReservationAvailabilityCondition(
                        condition.serviceDate(), condition.startTime(), null,
                        condition.partySize(), includesInfants));
        if (!matches(storeIds, results)) {
            return storeIds.stream()
                    .map(ignored -> ReservationAvailability.UNAVAILABLE)
                    .toList();
        }
        return results.stream()
                .map(result -> result.availability() == ReservationAvailabilityStatus.AVAILABLE
                        ? ReservationAvailability.AVAILABLE
                        : ReservationAvailability.UNAVAILABLE)
                .toList();
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

    private static Map<Long, ReservationAvailability> availabilityById(
            List<StoreSearchCandidate> candidates,
            List<ReservationAvailability> availabilities
    ) {
        Map<Long, ReservationAvailability> byId = new LinkedHashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            byId.put(candidates.get(index).storeId(), availabilities.get(index));
        }
        return byId;
    }

    private static ReservationAvailability reconcileLatestStoreState(
            StoreSearchCandidate candidate,
            ReservationAvailability batchAvailability
    ) {
        if (batchAvailability == ReservationAvailability.NOT_REQUESTED) {
            return batchAvailability;
        }
        if (candidate.operationStatus()
                != com.miriyum.domain.store.enums.OperationStatus.OPEN
                || !candidate.reservationEnabled()) {
            return ReservationAvailability.UNAVAILABLE;
        }
        return batchAvailability;
    }

    private PublicStoreSummary toSummary(
            StoreSearchCandidate candidate,
            ReservationAvailability availability
    ) {
        return new PublicStoreSummary(
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
                candidate.latitude() == null
                        ? null
                        : new PublicStoreCoordinates(
                                candidate.latitude(), candidate.longitude()));
    }
}
