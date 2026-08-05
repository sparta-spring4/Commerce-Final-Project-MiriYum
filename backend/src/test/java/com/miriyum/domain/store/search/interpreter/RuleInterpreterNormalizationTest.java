package com.miriyum.domain.store.search.interpreter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RuleInterpreterNormalizationTest {

    private final RuleInterpreter interpreter = new RuleInterpreter(
            Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("전각 문자와 제어 문자를 NFKC와 공백으로 정규화한다")
    void normalizesCompatibilityCharactersAndWhitespace() {
        // given
        InterpretationRequest request = new InterpretationRequest(
                "  ＡＢＣ\t１２３\n파스타  ",
                emptyVocabulary(),
                ZoneId.of("Asia/Seoul"));

        // when
        InterpretationResult result = interpreter.interpret(request);

        // then
        assertThat(result.condition().remainingKeyword()).isEqualTo("ABC 123 파스타");
        assertThat(result.ruleVersion()).isEqualTo("rule-v1");
        assertThat(result.vocabularyVersion()).isEqualTo("catalog-v1");
    }

    @Test
    @DisplayName("null 원문은 잘못된 호출로 거부한다")
    void rejectsNullInput() {
        // given
        InterpretationRequest request =
                new InterpretationRequest(null, emptyVocabulary(), ZoneId.of("Asia/Seoul"));

        // when & then
        assertThatThrownBy(() -> interpreter.interpret(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rawInput");
    }

    private SearchVocabulary emptyVocabulary() {
        return new SearchVocabulary("catalog-v1", List.of(), List.of(), List.of(), List.of());
    }
}
