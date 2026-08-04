package com.miriyum.domain.store.search.interpreter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RuleInterpreterDictionaryTest {

    private final RuleInterpreter interpreter = new RuleInterpreter(
            Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("승인 사전 코드를 추출하고 남은 키워드를 보존한다")
    void extractsApprovedDictionaryCodes() {
        // given
        SearchVocabulary vocabulary = new SearchVocabulary(
                "catalog-v1",
                List.of(new VocabularyEntry("REGION_SEONGSU", List.of("성수", "성수동"))),
                List.of(new VocabularyEntry("STORE_RESTAURANT", List.of("식당"))),
                List.of(new VocabularyEntry("MENU_PASTA", List.of("파스타"))),
                List.of(new VocabularyEntry("MOOD_DATE", List.of("데이트"))));

        // when
        InterpretationResult result = interpret("성수동 데이트 파스타 맛집", vocabulary);

        // then
        assertThat(result.condition().regionCodes()).containsExactly("REGION_SEONGSU");
        assertThat(result.condition().storeCategoryCodes()).isEmpty();
        assertThat(result.condition().menuCategoryCodes()).containsExactly("MENU_PASTA");
        assertThat(result.condition().tagCodes()).containsExactly("MOOD_DATE");
        assertThat(result.condition().remainingKeyword()).isEqualTo("맛집");
    }

    @Test
    @DisplayName("같은 표현이 서로 다른 사전 종류와 일치하면 어느 코드도 선택하지 않는다")
    void preservesCrossTypeAmbiguity() {
        // given
        SearchVocabulary vocabulary = new SearchVocabulary(
                "catalog-v1",
                List.of(),
                List.of(new VocabularyEntry("STORE_KOREAN", List.of("한식"))),
                List.of(new VocabularyEntry("MENU_KOREAN", List.of("한식"))),
                List.of());

        // when
        InterpretationResult result = interpret("한식 추천", vocabulary);

        // then
        assertThat(result.condition().storeCategoryCodes()).isEmpty();
        assertThat(result.condition().menuCategoryCodes()).isEmpty();
        assertThat(result.condition().remainingKeyword()).isEqualTo("한식 추천");
        assertThat(result.warnings()).containsExactly(new InterpretationWarning(
                WarningCode.AMBIGUOUS_DICTIONARY_TERM,
                WarningField.DICTIONARY));
    }

    @Test
    @DisplayName("같은 종류의 동일 코드는 입력 순서를 유지하며 중복 제거한다")
    void deduplicatesCodesInInputOrder() {
        // given
        SearchVocabulary vocabulary = new SearchVocabulary(
                "catalog-v1",
                List.of(
                        new VocabularyEntry("REGION_SEONGSU", List.of("성수", "성수동")),
                        new VocabularyEntry("REGION_GANGNAM", List.of("강남"))),
                List.of(),
                List.of(),
                List.of());

        // when
        InterpretationResult result = interpret("강남 성수 성수동 맛집", vocabulary);

        // then
        assertThat(result.condition().regionCodes())
                .containsExactly("REGION_GANGNAM", "REGION_SEONGSU");
        assertThat(result.condition().remainingKeyword()).isEqualTo("맛집");
    }

    @Test
    @DisplayName("소문자 변환 길이가 달라져도 원문 span을 정확히 소비한다")
    void preservesDisplayIndexesAcrossCaseFoldingExpansion() {
        // given
        SearchVocabulary vocabulary = new SearchVocabulary(
                "catalog-v1",
                List.of(new VocabularyEntry("REGION_CASE_EXPANSION", List.of("İ"))),
                List.of(),
                List.of(),
                List.of());

        // when
        InterpretationResult result = interpret("İ", vocabulary);

        // then
        assertThat(result.condition().regionCodes()).containsExactly("REGION_CASE_EXPANSION");
        assertThat(result.condition().remainingKeyword()).isEmpty();
    }

    private InterpretationResult interpret(String input, SearchVocabulary vocabulary) {
        return interpreter.interpret(new InterpretationRequest(
                input,
                vocabulary,
                ZoneId.of("Asia/Seoul")));
    }
}
