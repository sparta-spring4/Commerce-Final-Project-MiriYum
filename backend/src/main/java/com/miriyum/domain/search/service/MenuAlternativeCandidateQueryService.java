package com.miriyum.domain.search.service;

import com.miriyum.domain.search.dto.contract.MenuAlternativeCandidateView;
import com.miriyum.domain.search.dto.contract.MenuAlternativeSourceView;
import com.miriyum.domain.search.config.StoreSearchCandidateLimit;
import com.miriyum.domain.search.geo.BoundingBox;
import com.miriyum.domain.search.repository.MenuAlternativeCandidateRepository;
import com.miriyum.domain.search.semantic.SemanticMenuHit;
import com.miriyum.domain.search.semantic.SemanticMenuSearchService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MenuAlternativeCandidateQueryService {
    private final MenuAlternativeCandidateRepository repository;
    private final StoreSearchCandidateLimit candidateLimit;
    private final SemanticMenuSearchService semanticSearchService;

    public MenuAlternativeCandidateQueryService(MenuAlternativeCandidateRepository repository,
            StoreSearchCandidateLimit candidateLimit) {
        this(repository, candidateLimit, null);
    }

    @Autowired
    public MenuAlternativeCandidateQueryService(MenuAlternativeCandidateRepository repository,
            StoreSearchCandidateLimit candidateLimit,
            SemanticMenuSearchService semanticSearchService) {
        this.repository = repository;
        this.candidateLimit = candidateLimit;
        this.semanticSearchService = semanticSearchService;
    }

    public MenuAlternativeSourceView findSource(long storeId, long menuId) {
        return repository.findSource(storeId, menuId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.MENU_NOT_FOUND));
    }

    public List<MenuAlternativeCandidateView> findSameStoreCandidates(
            MenuAlternativeSourceView source) {
        List<SemanticMenuHit> hits = semanticHits(source);
        if (!hits.isEmpty()) {
            List<MenuAlternativeCandidateView> semantic =
                    repository.findSameStoreSemanticCandidates(
                            source.storeId(), source.menuId(), hits, candidateLimit.value());
            if (!semantic.isEmpty()) {
                return semantic;
            }
        }
        return repository.findSameStoreCandidates(
                source.storeId(), source.menuId(), candidateLimit.value());
    }

    public List<MenuAlternativeCandidateView> findNearbyCandidates(
            MenuAlternativeSourceView source, BoundingBox box) {
        List<SemanticMenuHit> hits = semanticHits(source);
        if (!hits.isEmpty()) {
            List<MenuAlternativeCandidateView> semantic =
                    repository.findNearbySemanticCandidates(
                            source.storeId(), source.menuId(), box, hits,
                            candidateLimit.value());
            if (!semantic.isEmpty()) {
                return semantic;
            }
        }
        return repository.findNearbyCandidates(
                source.storeId(), source.menuId(), box, candidateLimit.value());
    }

    private List<SemanticMenuHit> semanticHits(MenuAlternativeSourceView source) {
        if (semanticSearchService == null) {
            return List.of();
        }
        return repository.findCurrent(source.menuId())
                .map(document -> semanticSearchService.search(
                        document.text(), candidateLimit.value()))
                .orElseGet(() -> semanticSearchService.search(
                        String.join(" ", java.util.stream.Stream.concat(
                                        java.util.stream.Stream.of(
                                                source.menuName(),
                                                source.primaryCategoryCode()),
                                        source.secondaryCategoryCodes().stream())
                                .filter(value -> value != null && !value.isBlank())
                                .toList()),
                        candidateLimit.value()));
    }
}
