package com.miriyum.domain.search.expansion;

import static com.miriyum.domain.search.expansion.SearchConceptPurpose.STORE_SEARCH;
import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.search.config.OpenAiSearchInterpretationProperties;
import com.miriyum.domain.search.config.SearchInterpretationHttpConfig;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 실제 OpenAI 계약과 대표 한국어 검색 표현을 명시적으로 검증하는 opt-in 테스트다. */
@Tag("external-live")
class OpenAiSearchConceptInterpreterLiveTest {

    @Test
    void interpretsSpicySoupAsAnApprovedStewConcept() {
        OpenAiSearchInterpretationProperties properties =
                new OpenAiSearchInterpretationProperties(
                        true,
                        "https://api.openai.com",
                        System.getenv("OPENAI_API_KEY"),
                        "gpt-4o-mini",
                        2_000,
                        10_000,
                        100,
                        8,
                        200);
        OpenAiSearchConceptInterpreter interpreter =
                new OpenAiSearchConceptInterpreter(
                        new SearchInterpretationHttpConfig()
                                .openAiSearchInterpretationRestClient(properties),
                        new ObjectMapper(),
                        properties);

        SearchConceptExpansion result = interpreter.interpret(
                new SearchConceptRequest("얼큰한 국물", STORE_SEARCH));

        assertThat(result.concepts())
                .anyMatch(Set.of("김치찌개", "찌개")::contains);
    }
}
