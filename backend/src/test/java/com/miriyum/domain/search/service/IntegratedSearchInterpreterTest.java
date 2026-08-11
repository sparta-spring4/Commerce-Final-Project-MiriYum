package com.miriyum.domain.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.search.interpreter.SearchVocabulary;
import com.miriyum.domain.search.interpreter.VocabularyEntry;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntegratedSearchInterpreterTest {

    @Mock SearchVocabularyProvider vocabularyProvider;

    @Test
    void interpretsWithSeoulBusinessDateAndApprovedVocabulary() {
        given(vocabularyProvider.current()).willReturn(vocabulary());
        Clock clock = Clock.fixed(Instant.parse("2026-08-06T16:00:00Z"), ZoneOffset.UTC);
        IntegratedSearchInterpreter interpreter =
                new IntegratedSearchInterpreter(vocabularyProvider, clock);

        var result = interpreter.interpret("서울 내일 오후 6시 2명 라멘");

        assertThat(result.condition().regionCodes()).containsExactly("SEOUL");
        assertThat(result.condition().reservationDate()).hasToString("2026-08-08");
        assertThat(result.condition().partySize()).isEqualTo(2);
        assertThat(result.condition().reservationTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(result.condition().remainingKeyword()).isEqualTo("라멘");
    }

    @Test
    void rejectsBlankAndOverOneHundredCharacterInputs() {
        IntegratedSearchInterpreter interpreter = new IntegratedSearchInterpreter(
                vocabularyProvider,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        assertValidationFailure(() -> interpreter.interpret("   "));
        assertValidationFailure(() -> interpreter.interpret("가".repeat(101)));
    }

    @Test
    void demotesIncompleteReservationConditionToWarningAndGeneralKeyword() {
        given(vocabularyProvider.current()).willReturn(vocabulary());
        IntegratedSearchInterpreter interpreter = new IntegratedSearchInterpreter(
                vocabularyProvider,
                Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC));

        var result = interpreter.interpret("서울 내일 2명 라멘");

        assertThat(result.condition().regionCodes()).containsExactly("SEOUL");
        assertThat(result.condition().reservationDate()).isNull();
        assertThat(result.condition().reservationTime()).isNull();
        assertThat(result.condition().partySize()).isNull();
        assertThat(result.condition().remainingKeyword()).contains("내일", "2명", "라멘");
        assertThat(result.warnings()).isNotEmpty();
    }

    private static void assertValidationFailure(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }

    private static SearchVocabulary vocabulary() {
        return new SearchVocabulary(
                "catalog-v1",
                List.of(new VocabularyEntry("SEOUL", List.of("SEOUL", "서울"))),
                List.of(),
                List.of(),
                List.of());
    }
}
