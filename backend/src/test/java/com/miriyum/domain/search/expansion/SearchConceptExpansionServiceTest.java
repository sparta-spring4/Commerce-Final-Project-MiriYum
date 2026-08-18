package com.miriyum.domain.search.expansion;

import static com.miriyum.domain.search.expansion.SearchConceptFailureReason.TIMEOUT;
import static com.miriyum.domain.search.expansion.SearchConceptPurpose.STORE_SEARCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.search.config.OpenAiSearchInterpretationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    void capsProviderConceptsAtConfiguredMaximum() {
        var request = new SearchConceptRequest("얼큰한 국물", STORE_SEARCH);
        given(interpreter.interpret(request)).willReturn(new SearchConceptExpansion(
                List.of("하나", "둘", "셋", "넷", "다섯"), 10, 5));

        SearchConceptExpansion result = new SearchConceptExpansionService(
                properties(true, 3), interpreter, new SimpleMeterRegistry())
                .expand(request);

        assertThat(result.concepts()).containsExactly("하나", "둘", "셋");
    }

    private SearchConceptExpansionService service(
            boolean enabled,
            SimpleMeterRegistry registry
    ) {
        return new SearchConceptExpansionService(
                properties(enabled, 8), interpreter, registry);
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
