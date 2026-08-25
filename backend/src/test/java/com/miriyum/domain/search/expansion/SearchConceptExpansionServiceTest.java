package com.miriyum.domain.search.expansion;

import static com.miriyum.domain.search.expansion.SearchConceptFailureReason.TIMEOUT;
import static com.miriyum.domain.search.expansion.SearchConceptPurpose.MENU_ALTERNATIVE;
import static com.miriyum.domain.search.expansion.SearchConceptPurpose.STORE_SEARCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.search.config.OpenAiSearchInterpretationProperties;
import com.miriyum.domain.search.expansion.StructuredFoodEvidence.Dimension;
import com.miriyum.domain.search.expansion.StructuredFoodEvidence.EvidenceTerm;
import com.miriyum.domain.search.interpreter.FoodEvidenceVocabulary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchConceptExpansionServiceTest {

    @Mock
    private SearchConceptInterpreter interpreter;

    @Test
    void normalizesConceptsAndRecordsBoundedUsageMetrics() {
        var registry = new SimpleMeterRegistry();
        var request = new SearchConceptRequest("얼큰한 국물", STORE_SEARCH);
        given(interpreter.interpret(request)).willReturn(new SearchConceptExpansion(
                List.of(" 김치찌개 ", "김치찌개", "매운   국물", "찌개"),
                130,
                20));

        SearchConceptExpansion result = service(true, registry).expand(request);

        assertThat(result.concepts()).containsExactly("김치찌개", "매운 국물", "찌개");
        assertThat(registry.counter(
                "miriyum.search.llm.calls", "purpose", "store_search").count())
                .isEqualTo(1.0);
        assertThat(registry.counter(
                "miriyum.search.llm.outcomes",
                "purpose", "store_search",
                "outcome", "success").count()).isEqualTo(1.0);
        assertThat(registry.find("miriyum.search.llm.tokens")
                .tag("purpose", "store_search")
                .tag("type", "input")
                .summary().totalAmount()).isEqualTo(130.0);
        assertThat(registry.find("miriyum.search.llm.tokens")
                .tag("purpose", "store_search")
                .tag("type", "output")
                .summary().totalAmount()).isEqualTo(20.0);
    }

    @Test
    void disabledFeatureReturnsEmptyWithoutCallingProvider() {
        var request = new SearchConceptRequest("얼큰한 국물", STORE_SEARCH);

        SearchConceptExpansion result =
                service(false, new SimpleMeterRegistry()).expand(request);

        assertThat(result).isEqualTo(SearchConceptExpansion.empty());
        then(interpreter).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "얼큰한 국물 user@example.com",
            "김치찌개 010-1234-5678",
            "알레르기 없는 찌개",
            "서울 종로길 123 김치찌개",
            "예약번호 R-1234 김치찌개",
            "카드번호 1234 김치찌개",
            "땅콩 못 먹어요",
            "peanut allergy",
            "+82-10-1234-5678 김치찌개",
            "김치찌개 4111 1111 1111 1111",
            "김치찌개 37.5665 126.9780",
            "땅콩 함유 가능",
            "내일 예약하고 싶은 김치찌개",
            "사용자 ID abc 김치찌개",
            "지난주 예약한 김치찌개",
            "김철수와 지난주 먹었던 김치찌개",
            "김철수가 좋아하던 김치찌개",
            "김철수 김치찌개",
            "분위기 좋은 곳",
            "123 Main St 김치찌개",
            "김치찌개 example.com/private",
            "우유를 피하고 싶어요"
    })
    void sensitiveInputFallsBackWithoutCallingProvider(String text) {
        var registry = new SimpleMeterRegistry();

        SearchConceptExpansion result = service(true, registry).expand(
                new SearchConceptRequest(text, STORE_SEARCH));

        assertThat(result).isEqualTo(SearchConceptExpansion.empty());
        then(interpreter).shouldHaveNoInteractions();
        assertThat(registry.counter(
                "miriyum.search.llm.calls", "purpose", "store_search").count())
                .isZero();
        assertThat(registry.counter(
                "miriyum.search.llm.outcomes",
                "purpose", "store_search",
                "outcome", "sensitive_input").count()).isEqualTo(1.0);
    }

    @Test
    void timeoutFallsBackToEmptyAndRecordsReason() {
        var registry = new SimpleMeterRegistry();
        var request = new SearchConceptRequest("얼큰한 국물", STORE_SEARCH);
        given(interpreter.interpret(request))
                .willThrow(new SearchConceptProviderException(TIMEOUT));

        SearchConceptExpansion result = service(true, registry).expand(request);

        assertThat(result).isEqualTo(SearchConceptExpansion.empty());
        assertThat(registry.counter(
                "miriyum.search.llm.outcomes",
                "purpose", "store_search",
                "outcome", "timeout").count()).isEqualTo(1.0);
    }

    @Test
    void catalogOwnedAlternativeTextRetainsSensitiveDataScreeningWithoutFoodAllowlist() {
        var registry = new SimpleMeterRegistry();
        var request = new SearchConceptRequest(
                "김치찌개 돼지고기와 두부가 든 얼큰한 찌개 MAIN STEW 얼큰한",
                MENU_ALTERNATIVE);
        given(interpreter.interpret(request)).willReturn(new SearchConceptExpansion(
                List.of("김치찌개", "찌개"), 80, 10));

        SearchConceptExpansion result = service(true, registry).expand(request);

        assertThat(result.concepts()).containsExactly("김치찌개", "찌개");
        assertThat(registry.counter(
                "miriyum.search.llm.calls", "purpose", "menu_alternative").count())
                .isEqualTo(1.0);
    }

    @Test
    void providerFailureRecordsReturnedUsageWithoutExposingResponse() {
        var registry = new SimpleMeterRegistry();
        var request = new SearchConceptRequest("얼큰한 국물", STORE_SEARCH);
        given(interpreter.interpret(request)).willThrow(
                new SearchConceptProviderException(
                        SearchConceptFailureReason.REFUSAL, 31, 4));

        SearchConceptExpansion result = service(true, registry).expand(request);

        assertThat(result).isEqualTo(SearchConceptExpansion.empty());
        assertThat(registry.find("miriyum.search.llm.tokens")
                .tag("purpose", "store_search")
                .tag("type", "input")
                .summary().totalAmount()).isEqualTo(31.0);
        assertThat(registry.find("miriyum.search.llm.tokens")
                .tag("purpose", "store_search")
                .tag("type", "output")
                .summary().totalAmount()).isEqualTo(4.0);
    }

    @Test
    void capsProviderConceptsAtConfiguredMaximum() {
        var request = new SearchConceptRequest("얼큰한 국물", STORE_SEARCH);
        given(interpreter.interpret(request)).willReturn(new SearchConceptExpansion(
                List.of("하나", "둘", "셋", "넷", "다섯"), 10, 5));

        SearchConceptExpansion result = new SearchConceptExpansionService(
                properties(true, 3),
                interpreter,
                new FoodEvidenceVocabulary(),
                new SimpleMeterRegistry())
                .expand(request);

        assertThat(result.concepts()).containsExactly("하나", "둘", "셋");
    }

    @Test
    void deterministicDimensionsWinAndLlmOnlyFillsEmptyDimensions() {
        var vocabulary = new FoodEvidenceVocabulary();
        StructuredFoodEvidence deterministic = evidence(
                vocabulary,
                "마라탕",
                "칼칼한",
                null);
        StructuredFoodEvidence llm = evidence(
                vocabulary,
                "짬뽕",
                "달콤한",
                "해물");
        var request = new SearchConceptRequest("칼칼한 마라탕", STORE_SEARCH);
        given(interpreter.interpret(request)).willReturn(new SearchConceptExpansion(
                List.of("짬뽕"), llm, 20, 5));

        SearchConceptExpansion result = service(true, new SimpleMeterRegistry())
                .expand(request, deterministic);

        assertThat(result.foodEvidence().menuFamilies())
                .extracting(EvidenceTerm::id)
                .containsExactly("MALATANG");
        assertThat(result.foodEvidence().tastes())
                .extracting(EvidenceTerm::id)
                .containsExactly("SPICY_SHARP");
        assertThat(result.foodEvidence().ingredients())
                .extracting(EvidenceTerm::id)
                .containsExactly("SEAFOOD");
    }

    @Test
    void disabledProviderReturnsDeterministicEvidenceWithoutInteraction() {
        var vocabulary = new FoodEvidenceVocabulary();
        StructuredFoodEvidence deterministic = evidence(
                vocabulary,
                "마라탕",
                "칼칼한",
                null);
        var request = new SearchConceptRequest("칼칼한 마라탕", STORE_SEARCH);

        SearchConceptExpansion result = service(false, new SimpleMeterRegistry())
                .expand(request, deterministic);

        assertThat(result.foodEvidence()).isEqualTo(deterministic);
        assertThat(result.concepts()).isEmpty();
        then(interpreter).shouldHaveNoInteractions();
    }

    @Test
    void discardsLlmTermsOutsideReviewedVocabulary() {
        StructuredFoodEvidence unknown = new StructuredFoodEvidence(
                List.of(),
                List.of(new EvidenceTerm(
                        "UNREVIEWED",
                        "우주라면",
                        List.of("우주라면"),
                        StructuredFoodEvidenceSource.LLM)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var request = new SearchConceptRequest("얼큰한 국물", STORE_SEARCH);
        given(interpreter.interpret(request)).willReturn(new SearchConceptExpansion(
                List.of("우주라면"), unknown, 10, 4));

        SearchConceptExpansion result = service(true, new SimpleMeterRegistry())
                .expand(request, StructuredFoodEvidence.empty());

        assertThat(result.foodEvidence()).isEqualTo(StructuredFoodEvidence.empty());
    }

    private SearchConceptExpansionService service(
            boolean enabled,
            SimpleMeterRegistry registry
    ) {
        return new SearchConceptExpansionService(
                properties(enabled, 8),
                interpreter,
                new FoodEvidenceVocabulary(),
                registry);
    }

    private static StructuredFoodEvidence evidence(
            FoodEvidenceVocabulary vocabulary,
            String menu,
            String taste,
            String ingredient
    ) {
        List<EvidenceTerm> menus = menu == null
                ? List.of()
                : List.of(vocabulary.resolve(
                        Dimension.MENU_FAMILY,
                        menu,
                        StructuredFoodEvidenceSource.DETERMINISTIC).orElseThrow());
        List<EvidenceTerm> tastes = taste == null
                ? List.of()
                : List.of(vocabulary.resolve(
                        Dimension.TASTE,
                        taste,
                        StructuredFoodEvidenceSource.DETERMINISTIC).orElseThrow());
        List<EvidenceTerm> ingredients = ingredient == null
                ? List.of()
                : List.of(vocabulary.resolve(
                        Dimension.INGREDIENT,
                        ingredient,
                        StructuredFoodEvidenceSource.LLM).orElseThrow());
        return new StructuredFoodEvidence(
                List.of(), menus, ingredients, tastes,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static OpenAiSearchInterpretationProperties properties(
            boolean enabled,
            int maxConcepts
    ) {
        return new OpenAiSearchInterpretationProperties(
                enabled,
                "https://api.openai.com",
                enabled ? "test-secret" : "",
                "gpt-4o-mini",
                1_000,
                2_000,
                100,
                maxConcepts,
                200);
    }
}
