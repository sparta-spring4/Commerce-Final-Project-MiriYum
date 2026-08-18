package com.miriyum.domain.search.service;

import com.miriyum.domain.search.config.StoreSearchCandidateLimit;
import com.miriyum.domain.search.dto.contract.MenuAlternativeCandidateView;
import com.miriyum.domain.search.dto.contract.MenuAlternativeSourceView;
import com.miriyum.domain.search.expansion.SearchConceptExpansion;
import com.miriyum.domain.search.expansion.SearchConceptExpansionService;
import com.miriyum.domain.search.expansion.SearchConceptPurpose;
import com.miriyum.domain.search.expansion.SearchConceptRequest;
import com.miriyum.domain.search.geo.BoundingBox;
import com.miriyum.domain.search.repository.MenuAlternativeCandidateRepository;
import com.miriyum.domain.search.repository.MenuAlternativeCandidateRepository.MenuAlternativeInterpretationText;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MenuAlternativeCandidateQueryService {

    private static final int MAX_EXTERNAL_TEXT_LENGTH = 500;

    private final MenuAlternativeCandidateRepository repository;
    private final StoreSearchCandidateLimit candidateLimit;
    private final SearchConceptExpansionService expansionService;

    public MenuAlternativeCandidateQueryService(
            MenuAlternativeCandidateRepository repository,
            StoreSearchCandidateLimit candidateLimit,
            SearchConceptExpansionService expansionService
    ) {
        this.repository = repository;
        this.candidateLimit = candidateLimit;
        this.expansionService = expansionService;
    }

    public MenuAlternativeSourceView findSource(long storeId, long menuId) {
        MenuAlternativeSourceView source = repository.findSource(storeId, menuId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.MENU_NOT_FOUND));
        SearchConceptExpansion expansion = repository.findInterpretationText(storeId, menuId)
                .map(MenuAlternativeCandidateQueryService::externalText)
                .map(text -> expansionService.expand(new SearchConceptRequest(
                        text, SearchConceptPurpose.MENU_ALTERNATIVE)))
                .orElseGet(SearchConceptExpansion::empty);
        return new MenuAlternativeSourceView(
                source.storeId(),
                source.storeName(),
                source.menuId(),
                source.menuName(),
                source.unitPrice(),
                source.primaryCategoryCode(),
                source.secondaryCategoryCodes(),
                source.allergenInformationStatus(),
                source.allergens(),
                source.latitude(),
                source.longitude(),
                expansion.concepts());
    }

    public List<MenuAlternativeCandidateView> findSameStoreCandidates(
            MenuAlternativeSourceView source
    ) {
        int limit = candidateLimit.value();
        List<MenuAlternativeCandidateView> expanded = source.searchConcepts().isEmpty()
                ? List.of()
                : repository.findSameStoreExpandedCandidates(
                        source.storeId(), source.menuId(), source.searchConcepts(), limit);
        List<MenuAlternativeCandidateView> generic = repository.findSameStoreCandidates(
                source.storeId(), source.menuId(), limit);
        return merge(expanded, generic, limit);
    }

    public List<MenuAlternativeCandidateView> findNearbyCandidates(
            MenuAlternativeSourceView source,
            BoundingBox box
    ) {
        int limit = candidateLimit.value();
        List<MenuAlternativeCandidateView> expanded = source.searchConcepts().isEmpty()
                ? List.of()
                : repository.findNearbyExpandedCandidates(
                        source.storeId(), source.menuId(), box, source.searchConcepts(), limit);
        List<MenuAlternativeCandidateView> generic = repository.findNearbyCandidates(
                source.storeId(), source.menuId(), box, limit);
        return merge(expanded, generic, limit);
    }

    private static String externalText(MenuAlternativeInterpretationText source) {
        List<String> parts = new ArrayList<>();
        parts.add(source.name());
        parts.add(source.description());
        parts.add(source.primaryCategoryCode());
        parts.addAll(source.secondaryCategoryCodes());
        parts.addAll(source.localTags());
        String combined = parts.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining(" "));
        return combined.length() <= MAX_EXTERNAL_TEXT_LENGTH
                ? combined
                : combined.substring(0, MAX_EXTERNAL_TEXT_LENGTH);
    }

    private static List<MenuAlternativeCandidateView> merge(
            List<MenuAlternativeCandidateView> expanded,
            List<MenuAlternativeCandidateView> generic,
            int limit
    ) {
        Map<CandidateKey, MenuAlternativeCandidateView> merged = new LinkedHashMap<>();
        expanded.forEach(candidate -> merged.putIfAbsent(
                new CandidateKey(candidate.storeId(), candidate.menuId()), candidate));
        generic.forEach(candidate -> merged.putIfAbsent(
                new CandidateKey(candidate.storeId(), candidate.menuId()), candidate));
        return merged.values().stream().limit(limit).toList();
    }

    private record CandidateKey(long storeId, long menuId) {
    }
}
