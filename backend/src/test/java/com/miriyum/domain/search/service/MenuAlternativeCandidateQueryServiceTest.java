package com.miriyum.domain.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.search.config.StoreSearchCandidateLimit;
import com.miriyum.domain.search.dto.contract.MenuAlternativeSourceView;
import com.miriyum.domain.search.dto.contract.MenuAlternativeCandidateView;
import com.miriyum.domain.search.geo.BoundingBox;
import com.miriyum.domain.search.repository.MenuAlternativeCandidateRepository;
import com.miriyum.domain.search.semantic.SemanticMenuDocument;
import com.miriyum.domain.search.semantic.SemanticMenuHit;
import com.miriyum.domain.search.semantic.SemanticMenuSearchService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MenuAlternativeCandidateQueryServiceTest {

    @Mock
    private MenuAlternativeCandidateRepository repository;

    @Mock
    private SemanticMenuSearchService semanticSearchService;

    @Test
    void appliesSharedCandidateLimitToSameStoreAndNearbyQueries() {
        StoreSearchCandidateLimit limit = new StoreSearchCandidateLimit(321);
        MenuAlternativeCandidateQueryService service =
                new MenuAlternativeCandidateQueryService(repository, limit);
        MenuAlternativeSourceView source = new MenuAlternativeSourceView(
                10L, "source", 20L, "menu", 10_000, "MAIN", List.of(),
                "REGISTERED", List.of(), BigDecimal.valueOf(37.5),
                BigDecimal.valueOf(127.0));
        BoundingBox box = new BoundingBox(37.0, 38.0, 126.0, 128.0);
        when(repository.findSameStoreCandidates(10L, 20L, 321)).thenReturn(List.of());
        when(repository.findNearbyCandidates(10L, 20L, box, 321)).thenReturn(List.of());

        service.findSameStoreCandidates(source);
        service.findNearbyCandidates(source, box);

        verify(repository).findSameStoreCandidates(10L, 20L, 321);
        verify(repository).findNearbyCandidates(10L, 20L, box, 321);
    }

    @Test
    void usesSemanticHitsForSimilarSameStoreAlternatives() {
        StoreSearchCandidateLimit limit = new StoreSearchCandidateLimit(321);
        MenuAlternativeCandidateQueryService service =
                new MenuAlternativeCandidateQueryService(
                        repository, limit, semanticSearchService);
        MenuAlternativeSourceView source = new MenuAlternativeSourceView(
                10L, "source", 20L, "김치찌개", 10_000, "MAIN", List.of(),
                "REGISTERED", List.of(), BigDecimal.valueOf(37.5),
                BigDecimal.valueOf(127.0));
        SemanticMenuDocument document = new SemanticMenuDocument(
                20L, 10L, 2, "김치찌개 얼큰한 국물");
        SemanticMenuHit hit = new SemanticMenuHit(21L, 10L, 1, 0.92);
        MenuAlternativeCandidateView candidate = new MenuAlternativeCandidateView(
                10L, "source", 21L, "부대찌개", 11_000, "MAIN", List.of(),
                "REGISTERED", List.of(), null, null);
        when(repository.findCurrent(20L)).thenReturn(Optional.of(document));
        when(semanticSearchService.search(document.text(), 321)).thenReturn(List.of(hit));
        when(repository.findSameStoreSemanticCandidates(
                10L, 20L, List.of(hit), 321)).thenReturn(List.of(candidate));

        assertThat(service.findSameStoreCandidates(source)).containsExactly(candidate);
    }
}
