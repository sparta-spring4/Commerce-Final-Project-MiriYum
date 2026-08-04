package com.miriyum.domain.store.search.interpreter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RuleInterpreterTemporalTest {

    @Test
    @DisplayName("요청 시간대에서 상대 날짜와 오후 시각을 해석한다")
    void interpretsRelativeDateInExplicitZone() {
        // given
        RuleInterpreter interpreter = new RuleInterpreter(
                Clock.fixed(Instant.parse("2026-08-04T15:30:00Z"), ZoneOffset.UTC));
        InterpretationRequest request = request(
                "오늘 오후 7시 30분 예약",
                ZoneId.of("Asia/Seoul"));

        // when
        InterpretationResult result = interpreter.interpret(request);

        // then
        assertThat(result.condition().reservationDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(result.condition().reservationTime()).isEqualTo(LocalTime.of(19, 30));
        assertThat(result.condition().remainingKeyword()).isEqualTo("예약");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("서로 다른 날짜와 모호 시각은 추측하지 않는다")
    void preservesConflictingDateAndAmbiguousTime() {
        // given
        RuleInterpreter interpreter = new RuleInterpreter(
                Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));

        // when
        InterpretationResult result = interpreter.interpret(request(
                "오늘 내일 저녁쯤 파스타",
                ZoneId.of("Asia/Seoul")));

        // then
        assertThat(result.condition().reservationDate()).isNull();
        assertThat(result.condition().reservationTime()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo("오늘 내일 저녁쯤 파스타");
        assertThat(result.warnings()).containsExactly(
                new InterpretationWarning(WarningCode.CONFLICTING_DATE, WarningField.DATE),
                new InterpretationWarning(WarningCode.AMBIGUOUS_TIME, WarningField.TIME));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("absoluteDateAndTimeCases")
    @DisplayName("명시적인 절대 날짜와 시각을 해석한다")
    void interpretsAbsoluteDateAndTime(
            String input,
            LocalDate expectedDate,
            LocalTime expectedTime,
            String expectedKeyword) {
        // given
        RuleInterpreter interpreter = new RuleInterpreter(
                Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));

        // when
        InterpretationResult result = interpreter.interpret(request(
                input,
                ZoneId.of("Asia/Seoul")));

        // then
        assertThat(result.condition().reservationDate()).isEqualTo(expectedDate);
        assertThat(result.condition().reservationTime()).isEqualTo(expectedTime);
        assertThat(result.condition().remainingKeyword()).isEqualTo(expectedKeyword);
        assertThat(result.warnings()).isEmpty();
    }

    private static Stream<Arguments> absoluteDateAndTimeCases() {
        return Stream.of(
                Arguments.of(
                        "2026-08-10 19:30 예약",
                        LocalDate.of(2026, 8, 10),
                        LocalTime.of(19, 30),
                        "예약"),
                Arguments.of(
                        "2026년 8월 10일 오전 12시",
                        LocalDate.of(2026, 8, 10),
                        LocalTime.MIDNIGHT,
                        ""),
                Arguments.of(
                        "2026-08-10 오후 12시",
                        LocalDate.of(2026, 8, 10),
                        LocalTime.NOON,
                        ""));
    }

    @Test
    @DisplayName("연도 없는 날짜는 추측하지 않고 키워드로 보존한다")
    void preservesYearlessDate() {
        // given
        RuleInterpreter interpreter = new RuleInterpreter(
                Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));
        String input = "8월 10일 파스타";

        // when
        InterpretationResult result = interpreter.interpret(request(
                input,
                ZoneId.of("Asia/Seoul")));

        // then
        assertThat(result.condition().reservationDate()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo(input);
        assertThat(result.warnings()).containsExactly(new InterpretationWarning(
                WarningCode.AMBIGUOUS_DATE,
                WarningField.DATE));
    }

    @Test
    @DisplayName("유효하지 않은 달력 날짜와 시각은 키워드로 보존한다")
    void preservesInvalidDateAndTime() {
        // given
        RuleInterpreter interpreter = new RuleInterpreter(
                Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));
        String input = "2026-02-30 25:70 예약";

        // when
        InterpretationResult result = interpreter.interpret(request(
                input,
                ZoneId.of("Asia/Seoul")));

        // then
        assertThat(result.condition().reservationDate()).isNull();
        assertThat(result.condition().reservationTime()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo(input);
        assertThat(result.warnings()).containsExactly(
                new InterpretationWarning(WarningCode.AMBIGUOUS_DATE, WarningField.DATE),
                new InterpretationWarning(WarningCode.AMBIGUOUS_TIME, WarningField.TIME));
    }

    @Test
    @DisplayName("서로 다른 시각은 어느 하나도 선택하지 않는다")
    void preservesConflictingTimes() {
        // given
        RuleInterpreter interpreter = new RuleInterpreter(
                Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));
        String input = "19:00 오후 8시 예약";

        // when
        InterpretationResult result = interpreter.interpret(request(
                input,
                ZoneId.of("Asia/Seoul")));

        // then
        assertThat(result.condition().reservationTime()).isNull();
        assertThat(result.condition().remainingKeyword()).isEqualTo(input);
        assertThat(result.warnings()).containsExactly(new InterpretationWarning(
                WarningCode.CONFLICTING_TIME,
                WarningField.TIME));
    }

    private InterpretationRequest request(String input, ZoneId zoneId) {
        return new InterpretationRequest(
                input,
                new SearchVocabulary("catalog-v1", List.of(), List.of(), List.of(), List.of()),
                zoneId);
    }
}
