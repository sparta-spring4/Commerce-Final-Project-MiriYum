package com.miriyum.domain.store.search.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class StoreSearchQueryTest {

    @Test
    void rejectsPartialReservationCondition() {
        // given
        LocalDate serviceDate = LocalDate.of(2026, 8, 3);

        // when & then
        assertThatThrownBy(() -> StoreSearchQuery.from(
                null, null, null,
                serviceDate, null, 2,
                false, "name,asc", 0, 20))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }

    @Test
    void rejectsEveryPartialReservationConditionCombination() {
        // given
        LocalDate date = LocalDate.of(2026, 8, 3);
        LocalTime time = LocalTime.of(18, 30);

        // when & then
        assertValidationFailed(() -> reservationQuery(date, null, null));
        assertValidationFailed(() -> reservationQuery(null, time, null));
        assertValidationFailed(() -> reservationQuery(null, null, 2));
        assertValidationFailed(() -> reservationQuery(date, time, null));
        assertValidationFailed(() -> reservationQuery(date, null, 2));
        assertValidationFailed(() -> reservationQuery(null, time, 2));
    }

    @Test
    void rejectsAvailableOnlyWithoutReservationCondition() {
        // when & then
        assertThatThrownBy(() -> StoreSearchQuery.from(
                null, null, null,
                null, null, null,
                true, "name,asc", 0, 20))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }

    @Test
    void normalizesWhitespaceAndEscapesLikeMetaCharacters() {
        // when
        StoreSearchQuery query = StoreSearchQuery.from(
                "  성수\t100%_카페!  ", Region.SEOUL, "CAFE_BAKERY",
                null, null, null,
                false, "name,asc", 0, 20);

        // then
        assertThat(query.normalizedKeyword()).isEqualTo("성수 100%_카페!");
        assertThat(query.likePattern()).isEqualTo("%성수 100!%!_카페!!%");
    }

    @Test
    void acceptsCompleteReservationConditionAtPartySizeBoundaries() {
        // given
        LocalDate serviceDate = LocalDate.of(2026, 8, 3);
        LocalTime startTime = LocalTime.of(18, 30);

        // when
        StoreSearchQuery minimum = StoreSearchQuery.from(
                null, null, null, serviceDate, startTime, 1,
                false, "name,asc", 0, 20);
        StoreSearchQuery maximum = StoreSearchQuery.from(
                null, null, null, serviceDate, startTime, 100,
                true, "name,asc", 0, 20);

        // then
        assertThat(minimum.reservationCondition())
                .isEqualTo(new ReservationSearchCondition(serviceDate, startTime, 1));
        assertThat(maximum.reservationCondition())
                .isEqualTo(new ReservationSearchCondition(serviceDate, startTime, 100));
    }

    @Test
    void rejectsPartySizeOutsidePublicContract() {
        // given
        LocalDate serviceDate = LocalDate.of(2026, 8, 3);
        LocalTime startTime = LocalTime.of(18, 30);

        // when & then
        assertValidationFailed(() -> StoreSearchQuery.from(
                null, null, null, serviceDate, startTime, 0,
                false, "name,asc", 0, 20));
        assertValidationFailed(() -> StoreSearchQuery.from(
                null, null, null, serviceDate, startTime, 101,
                false, "name,asc", 0, 20));
    }

    @Test
    void rejectsPageAndSizeOutsidePublicContract() {
        // when & then
        assertValidationFailed(() -> StoreSearchQuery.from(
                null, null, null, null, null, null,
                false, "name,asc", -1, 20));
        assertValidationFailed(() -> StoreSearchQuery.from(
                null, null, null, null, null, null,
                false, "name,asc", 0, 0));
        assertValidationFailed(() -> StoreSearchQuery.from(
                null, null, null, null, null, null,
                false, "name,asc", 0, 101));
    }

    @Test
    void acceptsPageSizeBoundaries() {
        // when
        StoreSearchQuery minimum = StoreSearchQuery.from(
                null, null, null, null, null, null,
                false, "name,asc", 0, 1);
        StoreSearchQuery maximum = StoreSearchQuery.from(
                null, null, null, null, null, null,
                false, "name,asc", 0, 100);

        // then
        assertThat(minimum.size()).isEqualTo(1);
        assertThat(maximum.size()).isEqualTo(100);
    }

    @Test
    void acceptsOnlyDocumentedSortValuesAndDefaultsToNameAscending() {
        // when
        StoreSearchQuery defaultSort = queryWithSort(null);
        StoreSearchQuery nameDescending = queryWithSort("name,desc");
        StoreSearchQuery createdDescending = queryWithSort("createdAt,desc");
        StoreSearchQuery createdAscending = queryWithSort("createdAt,asc");

        // then
        assertThat(defaultSort.sort()).isEqualTo(StoreSearchSort.NAME_ASC);
        assertThat(nameDescending.sort()).isEqualTo(StoreSearchSort.NAME_DESC);
        assertThat(createdDescending.sort()).isEqualTo(StoreSearchSort.CREATED_AT_DESC);
        assertThat(createdAscending.sort()).isEqualTo(StoreSearchSort.CREATED_AT_ASC);
        assertValidationFailed(() -> queryWithSort("storeId,desc"));
        assertValidationFailed(() -> queryWithSort(""));
        assertValidationFailed(() -> queryWithSort("   "));
    }

    @Test
    void exposesOnlyFixedOrderClausesWithStoreIdTieBreaker() {
        // when & then
        assertThat(StoreSearchSort.NAME_ASC.orderByClause())
                .isEqualTo("s.name ASC, s.store_id ASC");
        assertThat(StoreSearchSort.NAME_DESC.orderByClause())
                .isEqualTo("s.name DESC, s.store_id ASC");
        assertThat(StoreSearchSort.CREATED_AT_DESC.orderByClause())
                .isEqualTo("s.created_at DESC, s.store_id ASC");
        assertThat(StoreSearchSort.CREATED_AT_ASC.orderByClause())
                .isEqualTo("s.created_at ASC, s.store_id ASC");
    }

    @Test
    void convertsBlankKeywordToNoPatternAndKeepsBackslashLiteral() {
        // when
        StoreSearchQuery blank = queryWithKeyword(" \t ");
        StoreSearchQuery backslash = queryWithKeyword("A\\B");

        // then
        assertThat(blank.normalizedKeyword()).isNull();
        assertThat(blank.likePattern()).isNull();
        assertThat(backslash.normalizedKeyword()).isEqualTo("a\\b");
        assertThat(backslash.likePattern()).isEqualTo("%a\\b%");
    }

    @Test
    void collapsesUnicodeSpaceCharactersIntoOneAsciiSpace() {
        // given
        String keyword = "\u00A0서울\u2007\u202F카페\u00A0";

        // when
        StoreSearchQuery query = queryWithKeyword(keyword);

        // then
        assertThat(query.normalizedKeyword()).isEqualTo("서울 카페");
    }

    @Test
    void rejectsNormalizedKeywordLongerThanOneHundredCharacters() {
        // given
        String keyword = "가".repeat(101);

        // when & then
        assertValidationFailed(() -> queryWithKeyword(keyword));
    }

    @Test
    void measuresKeywordLengthByUnicodeCodePoint() {
        // given
        String oneHundredEmoji = "😀".repeat(100);
        String oneHundredOneEmoji = "😀".repeat(101);

        // when
        StoreSearchQuery accepted = queryWithKeyword(oneHundredEmoji);

        // then
        assertThat(accepted.normalizedKeyword()).isEqualTo(oneHundredEmoji);
        assertValidationFailed(() -> queryWithKeyword(oneHundredOneEmoji));
    }

    @Test
    void lowercasesKeywordIndependentlyFromDefaultLocale() {
        // given
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            // when
            StoreSearchQuery query = queryWithKeyword("I CAFE");

            // then
            assertThat(query.normalizedKeyword()).isEqualTo("i cafe");
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static StoreSearchQuery queryWithSort(String sort) {
        return StoreSearchQuery.from(
                null, null, null, null, null, null,
                false, sort, 0, 20);
    }

    private static StoreSearchQuery queryWithKeyword(String keyword) {
        return StoreSearchQuery.from(
                keyword, null, null, null, null, null,
                false, "name,asc", 0, 20);
    }

    private static StoreSearchQuery reservationQuery(
            LocalDate date,
            LocalTime time,
            Integer partySize
    ) {
        return StoreSearchQuery.from(
                null, null, null, date, time, partySize,
                false, "name,asc", 0, 20);
    }

    private static void assertValidationFailed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }
}
