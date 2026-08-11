package com.miriyum.domain.search.interpreter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchVocabularyTest {

    @Test
    @DisplayName("사전 목록과 별칭을 방어 복사한다")
    void defensivelyCopiesVocabulary() {
        // given
        List<String> aliases = new ArrayList<>(List.of("성수"));
        List<VocabularyEntry> regions = new ArrayList<>(
                List.of(new VocabularyEntry("REGION_SEONGSU", aliases)));

        // when
        SearchVocabulary vocabulary =
                new SearchVocabulary("catalog-v1", regions, List.of(), List.of(), List.of());
        aliases.add("서울숲");
        regions.clear();

        // then
        assertThat(vocabulary.regions()).hasSize(1);
        assertThat(vocabulary.regions().getFirst().aliases()).containsExactly("성수");
        assertThatThrownBy(() -> vocabulary.regions()
                .add(new VocabularyEntry("REGION_OTHER", List.of("기타"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("같은 종류에서 정규화 별칭이 다른 코드를 가리키면 거부한다")
    void rejectsAmbiguousAliasWithinType() {
        // when & then
        assertThatThrownBy(() -> new SearchVocabulary(
                "catalog-v1",
                List.of(
                        new VocabularyEntry("REGION_A", List.of("ＡBC")),
                        new VocabularyEntry("REGION_B", List.of("abc"))),
                List.of(),
                List.of(),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alias");
    }

    @Test
    @DisplayName("입력과 같은 공백 정규화 뒤 충돌하는 별칭을 거부한다")
    void rejectsAliasCollisionAfterWhitespaceNormalization() {
        // when & then
        assertThatThrownBy(() -> new SearchVocabulary(
                "catalog-v1",
                List.of(
                        new VocabularyEntry("REGION_A", List.of("  성수\t")),
                        new VocabularyEntry("REGION_B", List.of("성수"))),
                List.of(),
                List.of(),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alias");
    }

    @Test
    @DisplayName("정규화 뒤 비는 제어 문자 별칭을 거부한다")
    void rejectsAliasThatNormalizesToEmpty() {
        // when & then
        assertThatThrownBy(() -> new SearchVocabulary(
                "catalog-v1",
                List.of(new VocabularyEntry("REGION_CONTROL", List.of("\u0000\t"))),
                List.of(),
                List.of(),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alias");
    }

    @Test
    @DisplayName("가격 범위가 역전되면 거부한다")
    void rejectsReversedPriceRange() {
        // when & then
        assertThatThrownBy(() -> new PriceRange(20_000L, 10_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minInclusive");
    }

    @Test
    @DisplayName("해석 결과의 코드와 경고 목록을 방어 복사한다")
    void defensivelyCopiesInterpretationResult() {
        // given
        List<String> regions = new ArrayList<>(List.of("REGION_SEONGSU"));
        List<InterpretationWarning> warnings = new ArrayList<>(List.of(
                new InterpretationWarning(WarningCode.AMBIGUOUS_TIME, WarningField.TIME)));

        // when
        InterpretedSearchCondition condition = new InterpretedSearchCondition(
                regions,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                "파스타");
        InterpretationResult result =
                new InterpretationResult("rule-v1", "catalog-v1", condition, warnings);
        regions.clear();
        warnings.clear();

        // then
        assertThat(result.condition().regionCodes()).containsExactly("REGION_SEONGSU");
        assertThat(result.warnings()).containsExactly(
                new InterpretationWarning(WarningCode.AMBIGUOUS_TIME, WarningField.TIME));
        assertThatThrownBy(() -> result.condition().regionCodes().add("REGION_OTHER"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.warnings().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
