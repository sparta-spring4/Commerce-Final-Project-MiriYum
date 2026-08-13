package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationCreationCapacityValidatorTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 3);

    private final ReservationCreationCapacityValidator validator =
            new ReservationCreationCapacityValidator();

    @Test
    @DisplayName("clipped 연속 coverage는 원본 버킷을 변경하지 않고 정책 버전을 반환한다")
    void returnsPolicyVersionForUnmodifiedClippedContinuousCoverage() {
        ReservationCapacityBucket leading = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, LocalTime.of(17, 30), LocalTime.of(18, 30),
                10, 3, 1, 1, 1, 6, true, 7L);
        ReservationCapacityBucket trailing = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, LocalTime.of(18, 30), LocalTime.of(19, 30),
                10, 3, 2, 1, 1, 6, true, 7L);

        long result = validator.validate(
                List.of(trailing, leading),
                22L,
                SERVICE_DATE,
                LocalTime.of(18, 0),
                LocalTime.of(19, 0),
                3,
                false);

        assertThat(result).isEqualTo(7L);
        assertThat(leading.getOccupiedPeople()).isEqualTo(1);
        assertThat(leading.getOccupiedTeams()).isEqualTo(1);
        assertThat(trailing.getOccupiedPeople()).isEqualTo(2);
        assertThat(trailing.getOccupiedTeams()).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 버킷 집합은 RESERVATION_003으로 거절한다")
    void rejectsEmptyBucketSetAsInsufficientCapacity() {
        assertError(
                ReservationErrorCode.INSUFFICIENT_CAPACITY,
                () -> validator.validate(
                        List.of(),
                        22L,
                        SERVICE_DATE,
                        LocalTime.of(18, 0),
                        LocalTime.of(19, 0),
                        3,
                        false));
    }

    @Test
    @DisplayName("요청 coverage의 gap은 RESERVATION_003으로 거절한다")
    void rejectsGapInRequestedCoverageAsInsufficientCapacity() {
        ReservationCapacityBucket first = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, LocalTime.of(18, 0), LocalTime.of(18, 30),
                10, 3, 0, 0, 1, 6, true, 7L);
        ReservationCapacityBucket afterGap = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, LocalTime.of(18, 45), LocalTime.of(19, 0),
                10, 3, 0, 0, 1, 6, true, 7L);

        assertError(
                ReservationErrorCode.INSUFFICIENT_CAPACITY,
                () -> validator.validate(
                        List.of(first, afterGap),
                        22L,
                        SERVICE_DATE,
                        LocalTime.of(18, 0),
                        LocalTime.of(19, 0),
                        3,
                        false));
    }

    @Test
    @DisplayName("요청 coverage의 overlap은 RESERVATION_003으로 거절한다")
    void rejectsOverlapInRequestedCoverageAsInsufficientCapacity() {
        ReservationCapacityBucket first = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, LocalTime.of(18, 0), LocalTime.of(18, 45),
                10, 3, 0, 0, 1, 6, true, 7L);
        ReservationCapacityBucket overlapping = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, LocalTime.of(18, 30), LocalTime.of(19, 0),
                10, 3, 0, 0, 1, 6, true, 7L);

        assertError(
                ReservationErrorCode.INSUFFICIENT_CAPACITY,
                () -> validator.validate(
                        List.of(first, overlapping),
                        22L,
                        SERVICE_DATE,
                        LocalTime.of(18, 0),
                        LocalTime.of(19, 0),
                        3,
                        false));
    }

    @Test
    @DisplayName("버킷 매장이 다르면 RESERVATION_007로 거절한다")
    void rejectsStoreMismatchAsCapacityPolicyChanged() {
        ReservationCapacityBucket wrongStore = ReservationCapacityBucket.create(
                23L, SERVICE_DATE, LocalTime.of(18, 0), LocalTime.of(19, 0),
                10, 3, 0, 0, 1, 6, true, 7L);

        assertPolicyChanged(wrongStore);
    }

    @Test
    @DisplayName("버킷 업무 날짜가 다르면 RESERVATION_007로 거절한다")
    void rejectsServiceDateMismatchAsCapacityPolicyChanged() {
        ReservationCapacityBucket wrongDate = ReservationCapacityBucket.create(
                22L, LocalDate.of(2026, 8, 4),
                LocalTime.of(18, 0), LocalTime.of(19, 0),
                10, 3, 0, 0, 1, 6, true, 7L);

        assertPolicyChanged(wrongDate);
    }

    @Test
    @DisplayName("버킷 정책 버전이 섞이면 RESERVATION_007로 거절한다")
    void rejectsMixedPolicyVersionsAsCapacityPolicyChanged() {
        ReservationCapacityBucket first = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, LocalTime.of(18, 0), LocalTime.of(18, 30),
                10, 3, 0, 0, 1, 6, true, 7L);
        ReservationCapacityBucket differentVersion = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, LocalTime.of(18, 30), LocalTime.of(19, 0),
                10, 3, 0, 0, 1, 6, true, 8L);

        assertError(
                ReservationErrorCode.CAPACITY_POLICY_CHANGED,
                () -> validator.validate(
                        List.of(first, differentVersion),
                        22L,
                        SERVICE_DATE,
                        LocalTime.of(18, 0),
                        LocalTime.of(19, 0),
                        3,
                        false));
    }

    @Test
    @DisplayName("최소 일행보다 작으면 RESERVATION_009로 거절한다")
    void rejectsPartyBelowMinimumAsPartySizeOutOfRange() {
        ReservationCapacityBucket restricted = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, LocalTime.of(18, 0), LocalTime.of(19, 0),
                10, 3, 0, 0, 3, 6, true, 7L);

        assertPartyRejected(restricted, 2, false);
    }

    @Test
    @DisplayName("최대 일행보다 크면 RESERVATION_009로 거절한다")
    void rejectsPartyAboveMaximumAsPartySizeOutOfRange() {
        ReservationCapacityBucket restricted = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, LocalTime.of(18, 0), LocalTime.of(19, 0),
                10, 3, 0, 0, 1, 4, true, 7L);

        assertPartyRejected(restricted, 5, false);
    }

    @Test
    @DisplayName("영유아 금지 버킷의 영유아 포함 일행은 RESERVATION_009로 거절한다")
    void rejectsInfantsWhenBucketPolicyForbidsThem() {
        ReservationCapacityBucket restricted = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, LocalTime.of(18, 0), LocalTime.of(19, 0),
                10, 3, 0, 0, 1, 6, false, 7L);

        assertPartyRejected(restricted, 3, true);
    }

    private void assertPolicyChanged(ReservationCapacityBucket bucket) {
        assertError(
                ReservationErrorCode.CAPACITY_POLICY_CHANGED,
                () -> validator.validate(
                        List.of(bucket),
                        22L,
                        SERVICE_DATE,
                        LocalTime.of(18, 0),
                        LocalTime.of(19, 0),
                        3,
                        false));
    }

    private void assertPartyRejected(
            ReservationCapacityBucket bucket,
            int partySize,
            boolean includesInfants
    ) {
        assertError(
                ReservationErrorCode.PARTY_SIZE_OUT_OF_RANGE,
                () -> validator.validate(
                        List.of(bucket),
                        22L,
                        SERVICE_DATE,
                        LocalTime.of(18, 0),
                        LocalTime.of(19, 0),
                        partySize,
                        includesInfants));
    }

    private static void assertError(ReservationErrorCode errorCode, Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
