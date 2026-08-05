package com.miriyum.domain.store.search.interpreter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class RuleInterpreterNumericTest {

    private final RuleInterpreter interpreter = new RuleInterpreter(
            Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("가격 상하한을 교집합으로 결합하고 인원을 추출한다")
    void combinesCompatiblePriceBoundsAndPartySize() {
        // when
        InterpretationResult result = interpret("1만원 이상 2만원 이하 3명 파스타");

        // then
        assertThat(result.condition().priceRange()).isEqualTo(new PriceRange(10_000L, 20_000L));
        assertThat(result.condition().partySize()).isEqualTo(3);
        assertThat(result.condition().remainingKeyword()).isEqualTo("파스타");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("모순 가격은 선택하지 않고 원문과 경고를 보존한다")
    void preservesConflictingPriceExpressions() {
        // when
        InterpretationResult result = interpret("3만원 이상 2만원 이하 조용한 곳");

        // then
        assertThat(result.condition().priceRange()).isNull();
        assertThat(result.condition().remainingKeyword())
                .isEqualTo("3만원 이상 2만원 이하 조용한 곳");
        assertThat(result.warnings()).containsExactly(new InterpretationWarning(
                WarningCode.CONFLICTING_PRICE,
                WarningField.PRICE));
    }

    @Test
    @DisplayName("만원대와 서로 다른 인원은 추측하지 않는다")
    void preservesAmbiguousPriceAndConflictingPartySize() {
        // when
        InterpretationResult result = interpret("2만원대 2명 4명 예약");

        // then
        assertThat(result.condition().priceRange()).isNull();
        assertThat(result.condition().partySize()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo("2만원대 2명 4명 예약");
        assertThat(result.warnings()).containsExactly(
                new InterpretationWarning(WarningCode.AMBIGUOUS_PRICE, WarningField.PRICE),
                new InterpretationWarning(
                        WarningCode.CONFLICTING_PARTY_SIZE,
                        WarningField.PARTY_SIZE));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("exactAndRangePrices")
    @DisplayName("명시적인 정확 가격과 범위를 포함 경계로 해석한다")
    void interpretsExactAndRangePrices(String input, PriceRange expected) {
        // when
        InterpretationResult result = interpret(input);

        // then
        assertThat(result.condition().priceRange()).isEqualTo(expected);
        assertThat(result.condition().remainingKeyword()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    private static Stream<Arguments> exactAndRangePrices() {
        return Stream.of(
                Arguments.of("15000원", new PriceRange(15_000L, 15_000L)),
                Arguments.of("1~2만원", new PriceRange(10_000L, 20_000L)),
                Arguments.of("1만원 초과 2만원 미만", new PriceRange(10_001L, 19_999L)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPartySizes")
    @DisplayName("양수가 아닌 인원은 조건으로 소비하지 않는다")
    void preservesInvalidPartySize(String input) {
        // when
        InterpretationResult result = interpret(input);

        // then
        assertThat(result.condition().partySize()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo(input);
        assertThat(result.warnings()).containsExactly(new InterpretationWarning(
                WarningCode.INVALID_PARTY_SIZE,
                WarningField.PARTY_SIZE));
    }

    private static Stream<String> invalidPartySizes() {
        return Stream.of("0명 예약", "-2명 예약");
    }

    @Test
    @DisplayName("long 범위를 넘는 가격 숫자는 조건으로 소비하지 않는다")
    void preservesOutOfRangePriceNumber() {
        // given
        String input = "999999999999999999999만원 이상 맛집";

        // when
        InterpretationResult result = interpret(input);

        // then
        assertThat(result.condition().priceRange()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo(input);
        assertThat(result.warnings()).containsExactly(new InterpretationWarning(
                WarningCode.OUT_OF_RANGE_NUMBER,
                WarningField.PRICE));
    }

    @Test
    @DisplayName("int 범위를 넘는 인원 숫자는 조건으로 소비하지 않는다")
    void preservesOutOfRangePartyNumber() {
        // given
        String input = "999999999999999999999명 예약";

        // when
        InterpretationResult result = interpret(input);

        // then
        assertThat(result.condition().partySize()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo(input);
        assertThat(result.warnings()).containsExactly(new InterpretationWarning(
                WarningCode.OUT_OF_RANGE_NUMBER,
                WarningField.PARTY_SIZE));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "0원 미만 맛집",
        "9223372036854775807원 초과 맛집"
    })
    @DisplayName("원화 범위를 벗어나는 배타 경계는 조건으로 소비하지 않는다")
    void preservesExclusivePriceBoundaryOutsideWonRange(String input) {
        // when
        InterpretationResult result = interpret(input);

        // then
        assertThat(result.condition().priceRange()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo(input);
        assertThat(result.warnings()).containsExactly(new InterpretationWarning(
                WarningCode.OUT_OF_RANGE_NUMBER,
                WarningField.PRICE));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"1,,2명 예약", "1.5명 예약"})
    @DisplayName("형식이 잘못된 인원 숫자는 일부만 해석하지 않는다")
    void preservesMalformedPartyNumber(String input) {
        // when
        InterpretationResult result = interpret(input);

        // then
        assertThat(result.condition().partySize()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo(input);
        assertThat(result.warnings()).containsExactly(new InterpretationWarning(
                WarningCode.INVALID_PARTY_SIZE,
                WarningField.PARTY_SIZE));
    }

    @Test
    @DisplayName("음수 가격은 양수 부분만 해석하지 않는다")
    void preservesNegativePriceNumber() {
        // given
        String input = "-1만원 맛집";

        // when
        InterpretationResult result = interpret(input);

        // then
        assertThat(result.condition().priceRange()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo(input);
        assertThat(result.warnings()).containsExactly(new InterpretationWarning(
                WarningCode.OUT_OF_RANGE_NUMBER,
                WarningField.PRICE));
    }

    @Test
    @DisplayName("음수 시작 가격 범위는 뒤쪽 가격만 재해석하지 않는다")
    void preservesNegativePriceRangeAsWhole() {
        // given
        String input = "-1~2만원 맛집";

        // when
        InterpretationResult result = interpret(input);

        // then
        assertThat(result.condition().priceRange()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo(input);
        assertThat(result.warnings()).containsExactly(new InterpretationWarning(
                WarningCode.OUT_OF_RANGE_NUMBER,
                WarningField.PRICE));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "1천~2만원 맛집",
        "1만원~2천원 맛집",
        "1만원~2원 맛집",
        "1천원~2원 맛집",
        "1천~2천원 맛집",
        "1~2천원 맛집"
    })
    @DisplayName("서로 다른 단위의 가격 범위는 승인 문법으로 소비하지 않는다")
    void preservesMixedUnitPriceRange(String input) {
        // when
        InterpretationResult result = interpret(input);

        // then
        assertThat(result.condition().priceRange()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo(input);
        assertThat(result.warnings()).containsExactly(new InterpretationWarning(
                WarningCode.AMBIGUOUS_PRICE,
                WarningField.PRICE));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "1만원~abc",
        "1만원 ~ 2",
        "1만원~",
        "1만원 ~ ",
        "1만원~abc123",
        "1만원~?"
    })
    @DisplayName("불완전 가격 범위를 왼쪽 정확 가격으로 부분 해석하지 않는다")
    void preservesIncompletePriceRangeAsWhole(String input) {
        InterpretationResult result = interpret(input);

        assertThat(result.condition().priceRange()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo(input.strip());
        assertThat(result.warnings()).containsExactly(new InterpretationWarning(
                WarningCode.AMBIGUOUS_PRICE,
                WarningField.PRICE));
    }

    @Test
    @DisplayName("연속 범위 구분자에서 첫 완전 범위 prefix만 소비하지 않는다")
    void preservesChainedPriceRangeAsWhole() {
        String input = "1만원~2만원~3만원";

        InterpretationResult result = interpret(input);

        assertThat(result.condition().priceRange()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo(input);
        assertThat(result.warnings()).containsExactly(new InterpretationWarning(
                WarningCode.AMBIGUOUS_PRICE,
                WarningField.PRICE));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"검색-2명", "검색+2명"})
    @DisplayName("단어에 붙은 부호 뒤 숫자에서 인원 해석을 재시작하지 않는다")
    void doesNotRestartPartyMatchAfterAttachedSign(String input) {
        // when
        InterpretationResult result = interpret(input);

        // then
        assertThat(result.condition().partySize()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo(input);
        assertThat(result.warnings()).isEmpty();
    }

    private InterpretationResult interpret(String input) {
        SearchVocabulary vocabulary =
                new SearchVocabulary("catalog-v1", List.of(), List.of(), List.of(), List.of());
        return interpreter.interpret(new InterpretationRequest(
                input,
                vocabulary,
                ZoneId.of("Asia/Seoul")));
    }
}
