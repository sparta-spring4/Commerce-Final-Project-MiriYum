package com.miriyum.domain.store.search.interpreter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuleInterpreterRegressionTest {

    private final RuleInterpreter interpreter = new RuleInterpreter(
            Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("사전과 구조화 문법이 같은 span을 요구하면 양쪽 조건을 모두 보존한다")
    void preservesCrossParserAmbiguity() {
        // given
        SearchVocabulary vocabulary = new SearchVocabulary(
                "catalog-v1",
                List.of(),
                List.of(),
                List.of(),
                List.of(new VocabularyEntry("TAG_TWO_PEOPLE", List.of("2명"))));

        // when
        InterpretationResult result = interpreter.interpret(new InterpretationRequest(
                "2명 예약",
                vocabulary,
                ZoneId.of("Asia/Seoul")));

        // then
        assertThat(result.condition().tagCodes()).isEmpty();
        assertThat(result.condition().partySize()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo("2명 예약");
        assertThat(result.warnings()).containsExactly(new InterpretationWarning(
                WarningCode.AMBIGUOUS_DICTIONARY_TERM,
                WarningField.DICTIONARY));
    }

    @Test
    @DisplayName("복합 입력에서 승인된 span만 제거한다")
    void interpretsCombinedInput() {
        // given
        SearchVocabulary vocabulary = regressionVocabulary();

        // when
        InterpretationResult result = interpreter.interpret(new InterpretationRequest(
                "내일 성수 데이트 파스타 2명 1~2만원 오후 7시 조용한 곳",
                vocabulary,
                ZoneId.of("Asia/Seoul")));

        // then
        assertThat(result.condition()).isEqualTo(new InterpretedSearchCondition(
                List.of("REGION_SEONGSU"),
                List.of(),
                List.of("MENU_PASTA"),
                List.of("MOOD_DATE"),
                new PriceRange(10_000L, 20_000L),
                2,
                LocalDate.of(2026, 8, 5),
                LocalTime.of(19, 0),
                "조용한 곳"));
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("SQL injection 문자열은 실행 표현으로 바꾸지 않고 키워드에 보존한다")
    void preservesSqlInjectionTextAsKeyword() {
        // given
        SearchVocabulary vocabulary = new SearchVocabulary(
                "catalog-v1",
                List.of(new VocabularyEntry("REGION_SEONGSU", List.of("성수"))),
                List.of(),
                List.of(),
                List.of());

        // when
        InterpretationResult result = interpreter.interpret(new InterpretationRequest(
                "성수 ' OR 1=1 -- 파스타",
                vocabulary,
                ZoneId.of("Asia/Seoul")));

        // then
        assertThat(result.condition().regionCodes()).containsExactly("REGION_SEONGSU");
        assertThat(result.condition().remainingKeyword()).isEqualTo("' OR 1=1 -- 파스타");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "성수 성수 파스타",
        "2명 4명 예약",
        "알 수 없는 표현",
        "",
        "１２명 내일"
    })
    @DisplayName("같은 입력 snapshot은 반복 실행해도 같은 결과를 만든다")
    void returnsSameResultForSameSnapshot(String input) {
        // given
        InterpretationRequest request = new InterpretationRequest(
                input,
                regressionVocabulary(),
                ZoneId.of("Asia/Seoul"));

        // when
        InterpretationResult first = interpreter.interpret(request);
        InterpretationResult second = interpreter.interpret(request);

        // then
        assertThat(second).isEqualTo(first);
    }

    private SearchVocabulary regressionVocabulary() {
        return new SearchVocabulary(
                "catalog-v1",
                List.of(new VocabularyEntry("REGION_SEONGSU", List.of("성수", "성수동"))),
                List.of(),
                List.of(new VocabularyEntry("MENU_PASTA", List.of("파스타"))),
                List.of(new VocabularyEntry("MOOD_DATE", List.of("데이트"))));
    }
}
