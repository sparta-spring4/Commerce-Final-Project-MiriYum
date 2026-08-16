package com.miriyum.domain.search.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SemanticMenuSearchServiceTest {

    @Mock TextEmbeddingClient embeddingClient;
    @Mock SemanticMenuIndex index;

    @Test
    void returnsRankedMenuHitsForUnrecognizedSearchExpression() {
        given(embeddingClient.embed("얼큰한 국물")).willReturn(List.of(0.1f, 0.2f));
        given(index.search(List.of(0.1f, 0.2f), 40)).willReturn(List.of(
                new SemanticMenuHit(11L, 2L, 3, 0.91)));

        var hits = new SemanticMenuSearchService(
                true, embeddingClient, index).search("얼큰한 국물", 40);

        assertThat(hits).containsExactly(new SemanticMenuHit(11L, 2L, 3, 0.91));
    }

    @Test
    void fallsBackToNoSemanticHitsWhenExternalProviderFails() {
        given(embeddingClient.embed("얼큰한 국물"))
                .willThrow(new IllegalStateException("provider unavailable"));

        var hits = new SemanticMenuSearchService(
                true, embeddingClient, index).search("얼큰한 국물", 40);

        assertThat(hits).isEmpty();
    }

    @Test
    void doesNotCallExternalProviderWhenSemanticSearchIsDisabled() {
        var hits = new SemanticMenuSearchService(
                false, embeddingClient, index).search("얼큰한 국물", 40);

        assertThat(hits).isEmpty();
    }

    @Test
    void doesNotSendContactOrAllergyExpressionsToExternalProvider() {
        SemanticMenuSearchService service = new SemanticMenuSearchService(
                true, embeddingClient, index);

        assertThat(service.search("010-1234-5678 근처 김치찌개", 40)).isEmpty();
        assertThat(service.search("우유 알레르기 없는 메뉴", 40)).isEmpty();

        then(embeddingClient).shouldHaveNoInteractions();
        then(index).shouldHaveNoInteractions();
    }
}
