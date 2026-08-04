package com.miriyum.domain.store.search.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.store.search.interpreter.InterpretedSearchCondition;
import com.miriyum.domain.store.search.interpreter.PriceRange;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntegratedStoreSearchQueryTest {

    @Test
    void defaultsToTwentyAndRejectsSizesOutsideOneToFifty() {
        IntegratedStoreSearchQuery defaultQuery = query(condition(), null, null, null);

        assertThat(defaultQuery.size()).isEqualTo(20);
        assertThat(defaultQuery.sort()).isEqualTo(IntegratedStoreSearchSort.NAME_ASC);
        assertThat(query(condition(), null, null, 1).size()).isEqualTo(1);
        assertThat(query(condition(), null, null, 50).size()).isEqualTo(50);
        assertValidationFailed(() -> query(condition(), null, null, 0));
        assertValidationFailed(() -> query(condition(), null, null, 51));
    }

    @Test
    void canonicalizesSameTypeValuesForStableFingerprint() {
        InterpretedSearchCondition first = condition(
                List.of("SEOUL", "BUSAN", "SEOUL"),
                List.of("KOREAN", "CAFE_BAKERY"),
                List.of("BEVERAGE", "KOREAN"),
                List.of("QUIET", "DATE"),
                new PriceRange(10_000L, 20_000L),
                "파스타");
        InterpretedSearchCondition reordered = condition(
                List.of("BUSAN", "SEOUL"),
                List.of("CAFE_BAKERY", "KOREAN"),
                List.of("KOREAN", "BEVERAGE"),
                List.of("DATE", "QUIET"),
                new PriceRange(10_000L, 20_000L),
                "파스타");

        IntegratedStoreSearchQuery firstQuery = query(first, "name,asc", null, 20);
        IntegratedStoreSearchQuery reorderedQuery = query(reordered, "name,asc", null, 20);

        assertThat(firstQuery.regionCodes()).containsExactly("BUSAN", "SEOUL");
        assertThat(firstQuery.storeCategoryCodes())
                .containsExactly("CAFE_BAKERY", "KOREAN");
        assertThat(firstQuery.fingerprint()).isEqualTo(reorderedQuery.fingerprint());
    }

    @Test
    void fingerprintIncludesEveryConditionSortAndPageSize() {
        IntegratedStoreSearchQuery baseline = query(condition(), "name,asc", null, 20);

        assertThat(query(condition("다른 키워드"), "name,asc", null, 20).fingerprint())
                .isNotEqualTo(baseline.fingerprint());
        assertThat(query(condition(), "createdAt,desc", null, 20).fingerprint())
                .isNotEqualTo(baseline.fingerprint());
        assertThat(query(condition(), "name,asc", null, 21).fingerprint())
                .isNotEqualTo(baseline.fingerprint());
        assertThat(query(conditionWithReservation(LocalDate.of(2026, 8, 6)),
                "name,asc", null, 20).fingerprint())
                .isNotEqualTo(baseline.fingerprint());
    }

    @Test
    void cursorIsAcceptedOnlyForTheSameFingerprintAndSort() {
        IntegratedStoreSearchQuery firstPage = query(condition(), "name,asc", null, 20);
        String cursor = IntegratedSearchCursorCodec.encode(
                firstPage, "가게.이름|한글", 42L);

        IntegratedStoreSearchQuery nextPage = query(condition(), "name,asc", cursor, 20);

        assertThat(nextPage.cursor()).get().satisfies(decoded -> {
            assertThat(decoded.sortValue()).isEqualTo("가게.이름|한글");
            assertThat(decoded.storeId()).isEqualTo(42L);
        });
        assertValidationFailed(() -> query(condition("다른 키워드"),
                "name,asc", cursor, 20));
        assertValidationFailed(() -> query(condition(), "name,desc", cursor, 20));
        assertValidationFailed(() -> query(condition(), "name,asc", cursor + "x", 20));
    }

    @Test
    void createdAtCursorRejectsAValueThatIsNotAnIsoLocalDateTime() {
        IntegratedStoreSearchQuery firstPage = query(
                condition(), "createdAt,desc", null, 20);
        String cursor = IntegratedSearchCursorCodec.encode(firstPage, "not-a-date", 42L);

        assertValidationFailed(() -> query(
                condition(), "createdAt,desc", cursor, 20));
    }

    @Test
    void rejectsBlankCodesAndUnknownSorts() {
        InterpretedSearchCondition blankCode = condition(
                List.of("SEOUL", " "), List.of(), List.of(), List.of(), null, "");

        assertValidationFailed(() -> query(blankCode, null, null, 20));
        assertValidationFailed(() -> query(condition(), "storeId,desc", null, 20));
    }

    private static IntegratedStoreSearchQuery query(
            InterpretedSearchCondition condition,
            String sort,
            String cursor,
            Integer size
    ) {
        return IntegratedStoreSearchQuery.from(condition, sort, cursor, size);
    }

    private static InterpretedSearchCondition condition() {
        return condition("");
    }

    private static InterpretedSearchCondition condition(String keyword) {
        return condition(List.of(), List.of(), List.of(), List.of(), null, keyword);
    }

    private static InterpretedSearchCondition conditionWithReservation(LocalDate date) {
        return new InterpretedSearchCondition(
                List.of(), List.of(), List.of(), List.of(), null,
                2, date, LocalTime.of(18, 30), "");
    }

    private static InterpretedSearchCondition condition(
            List<String> regions,
            List<String> storeCategories,
            List<String> menuCategories,
            List<String> tags,
            PriceRange priceRange,
            String keyword
    ) {
        return new InterpretedSearchCondition(
                regions, storeCategories, menuCategories, tags, priceRange,
                null, null, null, keyword);
    }

    private static void assertValidationFailed(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call
    ) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }
}
