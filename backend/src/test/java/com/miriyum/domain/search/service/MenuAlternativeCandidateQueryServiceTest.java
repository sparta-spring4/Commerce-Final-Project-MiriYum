package com.miriyum.domain.search.service;

import static com.miriyum.domain.search.expansion.SearchConceptPurpose.MENU_ALTERNATIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.search.config.StoreSearchCandidateLimit;
import com.miriyum.domain.search.dto.contract.MenuAlternativeCandidateView;
import com.miriyum.domain.search.dto.contract.MenuAlternativeSourceView;
import com.miriyum.domain.search.expansion.SearchConceptExpansion;
import com.miriyum.domain.search.expansion.SearchConceptExpansionService;
import com.miriyum.domain.search.expansion.SearchConceptRequest;
import com.miriyum.domain.search.geo.BoundingBox;
import com.miriyum.domain.search.repository.MenuAlternativeCandidateRepository;
import com.miriyum.domain.search.repository.MenuAlternativeCandidateRepository.MenuAlternativeInterpretationText;
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
    private SearchConceptExpansionService expansionService;

    @Test
    void expandsSourceOnceAndReusesConceptsForSameStoreAndNearbyCandidates() {
        StoreSearchCandidateLimit limit = new StoreSearchCandidateLimit(321);
        MenuAlternativeCandidateQueryService service =
                new MenuAlternativeCandidateQueryService(
                        repository, limit, expansionService);
        MenuAlternativeSourceView source = source();
        MenuAlternativeInterpretationText text = new MenuAlternativeInterpretationText(
                "김치찌개", "돼지고기와 두부가 든 얼큰한 찌개", "MAIN",
                List.of("STEW"), List.of("얼큰한"));
        String externalText = "김치찌개 돼지고기와 두부가 든 얼큰한 찌개 MAIN STEW 얼큰한";
        given(repository.findSource(10L, 20L)).willReturn(Optional.of(source));
        given(repository.findInterpretationText(10L, 20L)).willReturn(Optional.of(text));
        given(expansionService.expand(new SearchConceptRequest(
                externalText, MENU_ALTERNATIVE)))
                .willReturn(new SearchConceptExpansion(
                        List.of("김치찌개", "찌개"), 130, 20));
        MenuAlternativeCandidateView expanded = candidate(21L, "부대찌개");
        MenuAlternativeCandidateView generic = candidate(22L, "순두부찌개");
        BoundingBox box = new BoundingBox(37.0, 38.0, 126.0, 128.0);
        given(repository.findSameStoreExpandedCandidates(
                10L, 20L, List.of("김치찌개", "찌개"), 321))
                .willReturn(List.of(expanded));
        given(repository.findSameStoreCandidates(10L, 20L, 321))
                .willReturn(List.of(expanded, generic));
        given(repository.findNearbyExpandedCandidates(
                10L, 20L, box, List.of("김치찌개", "찌개"), 321))
                .willReturn(List.of(expanded));
        given(repository.findNearbyCandidates(10L, 20L, box, 321))
                .willReturn(List.of(expanded, generic));

        MenuAlternativeSourceView expandedSource = service.findSource(10L, 20L);

        assertThat(expandedSource.searchConcepts())
                .containsExactly("김치찌개", "찌개");
        assertThat(service.findSameStoreCandidates(expandedSource))
                .containsExactly(expanded, generic);
        assertThat(service.findNearbyCandidates(expandedSource, box))
                .containsExactly(expanded, generic);
        then(expansionService).should().expand(new SearchConceptRequest(
                externalText, MENU_ALTERNATIVE));
        then(expansionService).shouldHaveNoMoreInteractions();
    }

    @Test
    void providerFallbackPreservesGenericCandidateQueries() {
        MenuAlternativeCandidateQueryService service =
                new MenuAlternativeCandidateQueryService(
                        repository, new StoreSearchCandidateLimit(321), expansionService);
        MenuAlternativeSourceView source = source();
        given(repository.findSource(10L, 20L)).willReturn(Optional.of(source));
        given(repository.findInterpretationText(10L, 20L)).willReturn(Optional.empty());
        given(repository.findSameStoreCandidates(10L, 20L, 321)).willReturn(List.of());

        MenuAlternativeSourceView loaded = service.findSource(10L, 20L);

        assertThat(loaded.searchConcepts()).isEmpty();
        assertThat(service.findSameStoreCandidates(loaded)).isEmpty();
        then(expansionService).shouldHaveNoInteractions();
    }

    @Test
    void boundsAlternativeInterpretationTextBeforeExternalCall() {
        MenuAlternativeCandidateQueryService service =
                new MenuAlternativeCandidateQueryService(
                        repository, new StoreSearchCandidateLimit(321), expansionService);
        given(repository.findSource(10L, 20L)).willReturn(Optional.of(source()));
        given(repository.findInterpretationText(10L, 20L)).willReturn(Optional.of(
                new MenuAlternativeInterpretationText(
                        "김치찌개", "가".repeat(600), "MAIN", List.of(), List.of())));
        given(expansionService.expand(org.mockito.ArgumentMatchers.argThat(request ->
                request.purpose() == MENU_ALTERNATIVE && request.text().length() == 500)))
                .willReturn(SearchConceptExpansion.empty());

        MenuAlternativeSourceView loaded = service.findSource(10L, 20L);

        assertThat(loaded.searchConcepts()).isEmpty();
        then(expansionService).should().expand(org.mockito.ArgumentMatchers.argThat(request ->
                request.purpose() == MENU_ALTERNATIVE && request.text().length() == 500));
    }

    private static MenuAlternativeSourceView source() {
        return new MenuAlternativeSourceView(
                10L, "source", 20L, "김치찌개", 10_000, "MAIN", List.of(),
                "REGISTERED", List.of(), BigDecimal.valueOf(37.5),
                BigDecimal.valueOf(127.0));
    }

    private static MenuAlternativeCandidateView candidate(long id, String name) {
        return new MenuAlternativeCandidateView(
                10L, "source", id, name, 11_000, "MAIN", List.of(),
                "REGISTERED", List.of(), null, null);
    }
}
