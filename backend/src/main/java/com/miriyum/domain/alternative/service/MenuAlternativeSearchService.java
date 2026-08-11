package com.miriyum.domain.alternative.service;

import com.miriyum.domain.alternative.model.AlternativeMenuCandidate;
import com.miriyum.domain.alternative.model.AlternativeMenuSource;
import com.miriyum.domain.alternative.model.AlternativeReasonCode;
import com.miriyum.domain.alternative.model.EligibleAlternative;
import com.miriyum.domain.alternative.model.MenuAlternativeMode;
import com.miriyum.domain.alternative.model.MenuAlternativeResult;
import com.miriyum.domain.alternative.model.MenuAlternativeSearchCommand;
import com.miriyum.domain.alternative.model.ResolvedAlternativeItem;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityQuery;
import com.miriyum.domain.menuhold.service.MenuInventoryTransactionService;
import com.miriyum.domain.reservation.dto.request.ReservationAvailabilityCondition;
import com.miriyum.domain.reservation.dto.request.ReservationTimeRequest;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionStatus;
import com.miriyum.domain.reservation.dto.response.ResolvedReservationTime;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.reservation.service.ReservationTimeResolutionService;
import com.miriyum.domain.search.dto.contract.MenuAlternativeCandidateView;
import com.miriyum.domain.search.dto.contract.MenuAlternativeSourceView;
import com.miriyum.domain.search.geo.BoundingBoxCalculator;
import com.miriyum.domain.search.geo.GeoCoordinate;
import com.miriyum.domain.search.geo.HaversineDistanceCalculator;
import com.miriyum.domain.search.geo.StoreDistanceEligibility;
import com.miriyum.domain.search.service.MenuAlternativeCandidateQueryService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MenuAlternativeSearchService {
    private final MenuAlternativeCandidateQueryService candidateQuery;
    private final ReservationService reservationService;
    private final ReservationTimeResolutionService reservationTimeResolutionService;
    private final MenuInventoryTransactionService inventoryService;
    private final MenuAlternativeEligibility eligibility;

    public MenuAlternativeSearchService(MenuAlternativeCandidateQueryService candidateQuery,
            ReservationService reservationService,
            ReservationTimeResolutionService reservationTimeResolutionService,
            MenuInventoryTransactionService inventoryService,
            MenuAlternativeEligibility eligibility) {
        this.candidateQuery = candidateQuery;
        this.reservationService = reservationService;
        this.reservationTimeResolutionService = reservationTimeResolutionService;
        this.inventoryService = inventoryService;
        this.eligibility = eligibility;
    }

    @Transactional(readOnly = true)
    public MenuAlternativeResult search(long storeId, long menuId,
            MenuAlternativeSearchCommand command) {
        MenuAlternativeSourceView sourceView = candidateQuery.findSource(storeId, menuId);
        ResolvedWindow sourceWindow = resolveWindow(storeId, command, true);
        AlternativeMenuSource source = new AlternativeMenuSource(storeId, menuId,
                sourceView.unitPrice(), sourceView.primaryCategoryCode(),
                sourceView.secondaryCategoryCodes());
        List<EligibleAlternative> same = eligible(source,
                candidateQuery.findSameStoreCandidates(sourceView), command.excludedAllergenCodes());
        List<ResolvedAlternativeItem> sameItems = same.isEmpty()
                || !availableStores(List.of(storeId), command).contains(storeId)
                ? List.of()
                : inStock(same, sourceWindow, command.quantity()).stream()
                        .sorted(itemComparator(MenuAlternativeOrdering.sameStoreComparator()))
                        .limit(command.size()).toList();
        if (!sameItems.isEmpty()) {
            return result(sourceView, command, sourceWindow, MenuAlternativeMode.SAME_STORE, sameItems);
        }
        if (sourceView.latitude() == null || sourceView.longitude() == null) {
            return result(sourceView, command, sourceWindow,
                    MenuAlternativeMode.REGION_SELECTION_REQUIRED, List.of());
        }

        GeoCoordinate origin = new GeoCoordinate(sourceView.latitude().doubleValue(),
                sourceView.longitude().doubleValue());
        var box = BoundingBoxCalculator.around(origin,
                StoreDistanceEligibility.MAX_DISTANCE_METERS);
        List<MenuAlternativeCandidateView> nearbyViews = candidateQuery.findNearbyCandidates(
                sourceView, box);
        List<AlternativeMenuCandidate> nearbyCandidates = nearbyViews.stream()
                .map(this::candidate)
                .filter(value -> value.latitude() != null && value.longitude() != null)
                .map(value -> withDistance(value, origin))
                .filter(value -> value.distanceMeters().doubleValue()
                        <= StoreDistanceEligibility.MAX_DISTANCE_METERS)
                .toList();
        List<EligibleAlternative> nearbyEligible = nearbyCandidates.stream()
                .map(value -> eligibility.evaluate(source, value,
                        command.excludedAllergenCodes()).orElse(null))
                .filter(Objects::nonNull).toList();
        List<Long> storeIds = nearbyEligible.stream().map(value -> value.candidate().storeId())
                .distinct().sorted().toList();
        Set<Long> availableStores = availableStores(storeIds, command);
        List<Long> availableStoreIds = storeIds.stream()
                .filter(availableStores::contains)
                .toList();
        Map<Long, ResolvedWindow> windows = resolveWindows(
                availableStoreIds, command, false);
        List<ResolvedAlternativeItem> nearbyItems = new ArrayList<>();
        nearbyEligible.stream().filter(value -> windows.containsKey(value.candidate().storeId()))
                .collect(Collectors.groupingBy(
                        value -> windows.get(value.candidate().storeId()),
                        LinkedHashMap::new, Collectors.toList()))
                .forEach((window, values) -> nearbyItems.addAll(
                        inStock(values, window, command.quantity())));
        nearbyItems.sort(itemComparator(MenuAlternativeOrdering.nearbyStoreComparator()));
        List<ResolvedAlternativeItem> page = nearbyItems.stream().limit(command.size()).toList();
        return result(sourceView, command, sourceWindow, page.isEmpty()
                ? MenuAlternativeMode.NO_ALTERNATIVE : MenuAlternativeMode.NEARBY_STORE, page);
    }

    private Set<Long> availableStores(List<Long> storeIds, MenuAlternativeSearchCommand command) {
        if (storeIds.isEmpty()) return Set.of();
        List<ReservationAvailabilityResult> results = reservationService.getAvailabilities(storeIds,
                new ReservationAvailabilityCondition(command.serviceDate(), command.startTime(),
                        command.startOffset(), command.partySize(), command.includesInfants()));
        if (results == null || results.size() != storeIds.size()) unavailable();
        for (int index = 0; index < storeIds.size(); index++) {
            if (results.get(index) == null || results.get(index).storeId() != storeIds.get(index)) {
                unavailable();
            }
        }
        return results.stream().filter(value -> value.availability()
                        == ReservationAvailabilityStatus.AVAILABLE)
                .map(ReservationAvailabilityResult::storeId).collect(Collectors.toUnmodifiableSet());
    }

    private List<EligibleAlternative> eligible(AlternativeMenuSource source,
            List<MenuAlternativeCandidateView> values, Set<String> excluded) {
        return values.stream().map(this::candidate)
                .map(value -> eligibility.evaluate(source, value, excluded).orElse(null))
                .filter(Objects::nonNull).toList();
    }

    private AlternativeMenuCandidate candidate(MenuAlternativeCandidateView value) {
        return new AlternativeMenuCandidate(value.storeId(), value.storeName(), value.menuId(),
                value.menuName(), value.unitPrice(), value.primaryCategoryCode(),
                value.secondaryCategoryCodes(), value.allergenInformationStatus(),
                value.allergens(), value.latitude(), value.longitude(), null);
    }

    private AlternativeMenuCandidate withDistance(AlternativeMenuCandidate value,
            GeoCoordinate origin) {
        double meters = HaversineDistanceCalculator.distanceMeters(origin,
                new GeoCoordinate(value.latitude().doubleValue(), value.longitude().doubleValue()));
        return new AlternativeMenuCandidate(value.storeId(), value.storeName(), value.menuId(),
                value.menuName(), value.unitPrice(), value.primaryCategoryCode(),
                value.secondaryCategoryCodes(), value.allergenInformationStatus(), value.allergens(),
                value.latitude(), value.longitude(), BigDecimal.valueOf(meters));
    }

    private List<ResolvedAlternativeItem> inStock(List<EligibleAlternative> values,
            ResolvedWindow window, int quantity) {
        if (values.isEmpty()) return List.of();
        List<Long> menuIds = values.stream().map(value -> value.candidate().menuId())
                .distinct().sorted().toList();
        List<MenuInventoryAvailability> buckets = inventoryService.findExistingOnlineAvailability(
                new MenuInventoryAvailabilityQuery(menuIds, window.start().toLocalDate(),
                        window.start().toLocalTime(), window.end().toLocalDate(),
                        window.end().toLocalTime()));
        if (buckets == null || buckets.stream().anyMatch(Objects::isNull)) unavailable();
        Map<Long, MenuInventoryAvailability> byId = new LinkedHashMap<>();
        for (var bucket : buckets) {
            if (byId.put(bucket.menuId(), bucket) != null || !menuIds.contains(bucket.menuId())
                    || !window.timeZoneId().equals(bucket.timeZoneId())
                    || !window.start().toLocalDateTime().equals(LocalDateTime.of(
                            bucket.serviceDate(), bucket.startTime()))
                    || !window.end().toLocalDateTime().equals(LocalDateTime.of(
                            bucket.endDate(), bucket.endTime()))) unavailable();
        }
        Map<Long, EligibleAlternative> eligibleById = values.stream().collect(Collectors.toMap(
                value -> value.candidate().menuId(), Function.identity()));
        return buckets.stream().filter(bucket -> bucket.availabilityStatus()
                        == MenuInventoryAvailability.AvailabilityStatus.AVAILABLE
                        && bucket.availableOnlineQuantity() >= quantity)
                .map(bucket -> item(eligibleById.get(bucket.menuId()), bucket)).toList();
    }

    private ResolvedAlternativeItem item(EligibleAlternative value,
            MenuInventoryAvailability bucket) {
        var candidate = value.candidate();
        List<AlternativeReasonCode> reasons = new ArrayList<>(value.reasonCodes());
        reasons.add(AlternativeReasonCode.IN_STOCK);
        return new ResolvedAlternativeItem(candidate.storeId(), candidate.storeName(),
                candidate.menuId(), candidate.menuName(), candidate.unitPrice(),
                bucket.availableOnlineQuantity(), value.secondaryCategoryMatchCount(),
                value.absolutePriceDifference(),
                candidate.distanceMeters(), candidate.latitude(), candidate.longitude(), reasons);
    }

    private ResolvedWindow resolveWindow(long storeId, MenuAlternativeSearchCommand command,
            boolean source) {
        return resolveWindows(List.of(storeId), command, source).get(storeId);
    }

    private Map<Long, ResolvedWindow> resolveWindows(List<Long> storeIds,
            MenuAlternativeSearchCommand command, boolean source) {
        if (storeIds.isEmpty()) return Map.of();
        List<ReservationTimeResolutionResult> results =
                reservationTimeResolutionService.resolveReservationTimes(
                        storeIds, new ReservationTimeRequest(command.serviceDate(),
                                command.startTime(), command.startOffset()));
        if (results == null || results.size() != storeIds.size()) unavailable();
        Map<Long, ResolvedWindow> windows = new LinkedHashMap<>();
        for (int index = 0; index < storeIds.size(); index++) {
            long storeId = storeIds.get(index);
            ReservationTimeResolutionResult result = results.get(index);
            if (result == null || result.storeId() != storeId) unavailable();
            if (result.status() != ReservationTimeResolutionStatus.RESOLVED) {
                if (source) {
                    throw new ServiceException(ReservationErrorCode.OUTSIDE_RESERVATION_WINDOW);
                }
                continue;
            }
            windows.put(storeId, resolvedWindow(storeId, result, command));
        }
        return Map.copyOf(windows);
    }

    private ResolvedWindow resolvedWindow(long storeId,
            ReservationTimeResolutionResult result, MenuAlternativeSearchCommand command) {
        ResolvedReservationTime time = result.time();
        if (time == null || time.policyStoreId() != storeId
                || !time.serviceDate().equals(command.serviceDate())) unavailable();
        try {
            ZoneId zone = ZoneId.of(time.timeZoneId());
            var start = time.startAt().atZone(zone);
            var end = time.serviceEndAt().atZone(zone);
            if (start.getOffset().getTotalSeconds() != time.startOffsetSeconds()
                    || end.getOffset().getTotalSeconds() != time.serviceEndOffsetSeconds()) unavailable();
            return new ResolvedWindow(start.toOffsetDateTime(), end.toOffsetDateTime(),
                    time.timeZoneId());
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static java.util.Comparator<ResolvedAlternativeItem> itemComparator(
            java.util.Comparator<EligibleAlternative> policyComparator) {
        return (left, right) -> policyComparator.compare(asEligible(left), asEligible(right));
    }

    private static EligibleAlternative asEligible(ResolvedAlternativeItem item) {
        return new EligibleAlternative(new AlternativeMenuCandidate(item.storeId(), item.storeName(),
                item.menuId(), item.menuName(), item.unitPrice(), "", List.of(), "REGISTERED",
                List.of(), item.latitude(), item.longitude(), item.distanceMeters()),
                item.secondaryCategoryMatchCount(), item.absolutePriceDifference(),
                item.reasonCodes());
    }

    private static MenuAlternativeResult result(MenuAlternativeSourceView source,
            MenuAlternativeSearchCommand command, ResolvedWindow window, MenuAlternativeMode mode,
            List<ResolvedAlternativeItem> items) {
        return new MenuAlternativeResult(source.storeId(), source.menuId(), command.quantity(),
                window.start(), window.end(), window.timeZoneId(), mode, items);
    }

    private static void unavailable() {
        throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    private record ResolvedWindow(java.time.OffsetDateTime start,
            java.time.OffsetDateTime end, String timeZoneId) {}
}
