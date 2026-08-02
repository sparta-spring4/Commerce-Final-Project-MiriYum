package com.miriyum.domain.store.search.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
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
    void rejectsNormalizedKeywordLongerThanOneHundredCharacters() {
        // given
        String keyword = "가".repeat(101);

        // when & then
        assertValidationFailed(() -> queryWithKeyword(keyword));
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

    private static void assertValidationFailed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }
}
