package com.miriyum.domain.reservation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationCapacitiesRequestTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("날짜별 전체 게시에는 버킷을 1개 이상 96개 이하로 요구한다")
    void validatesBucketCount() {
        // given
        List<CapacityBucketRequest> tooMany = new ArrayList<>();
        for (int index = 0; index < 97; index++) {
            tooMany.add(validBucket());
        }

        // when & then
        assertThat(VALIDATOR.validate(new ReservationCapacitiesRequest(List.of())))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("buckets");
        assertThat(VALIDATOR.validate(new ReservationCapacitiesRequest(tooMany)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("buckets");
    }

    @Test
    @DisplayName("게시 버킷의 필수 시각과 수치 범위를 검증한다")
    void validatesBucketFields() {
        // given
        CapacityBucketRequest invalid = new CapacityBucketRequest(
                null,
                null,
                -1,
                10_001,
                0,
                101,
                true
        );

        // when
        List<String> paths = VALIDATOR.validate(
                        new ReservationCapacitiesRequest(List.of(invalid))
                ).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();

        // then
        assertThat(paths).contains(
                "buckets[0].startTime",
                "buckets[0].endTime",
                "buckets[0].maxPeople",
                "buckets[0].maxTeams",
                "buckets[0].minPartySize",
                "buckets[0].maxPartySize"
        );
    }

    @Test
    @DisplayName("최대 인원과 최대 팀 수는 누락할 수 없다")
    void validatesRequiredCapacityFields() {
        // given
        CapacityBucketRequest missingCapacities = new CapacityBucketRequest(
                LocalTime.of(18, 0),
                LocalTime.of(18, 30),
                null,
                null,
                1,
                6,
                true
        );

        // when
        List<String> paths = VALIDATOR.validate(
                        new ReservationCapacitiesRequest(List.of(missingCapacities))
                ).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();

        // then
        assertThat(paths).contains(
                "buckets[0].maxPeople",
                "buckets[0].maxTeams"
        );
    }

    @Test
    @DisplayName("최대 인원 0은 거부하고 최대 팀 수 0은 허용한다")
    void rejectsZeroMaxPeopleButAllowsZeroMaxTeams() {
        // given
        CapacityBucketRequest zeroCapacities = new CapacityBucketRequest(
                LocalTime.of(18, 0),
                LocalTime.of(18, 30),
                0,
                0,
                1,
                1,
                true
        );

        // when
        List<String> paths = VALIDATOR.validate(
                        new ReservationCapacitiesRequest(List.of(zeroCapacities))
                ).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();

        // then
        assertThat(paths)
                .contains("buckets[0].maxPeople")
                .doesNotContain("buckets[0].maxTeams");
    }

    @Test
    @DisplayName("게시 버킷의 시작과 종료 시각은 분 단위여야 한다")
    void rejectsSecondPrecisionBucketTimes() {
        // when & then
        assertThatThrownBy(() -> new CapacityBucketRequest(
                LocalTime.of(18, 0, 1),
                LocalTime.of(18, 30),
                10,
                4,
                1,
                6,
                true
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("capacity bucket times must use minute precision");
        assertThatThrownBy(() -> new CapacityBucketRequest(
                LocalTime.of(18, 0),
                LocalTime.of(18, 30, 1),
                10,
                4,
                1,
                6,
                true
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("capacity bucket times must use minute precision");
    }

    private static CapacityBucketRequest validBucket() {
        return new CapacityBucketRequest(
                LocalTime.of(18, 0),
                LocalTime.of(18, 30),
                10,
                4,
                1,
                6,
                true
        );
    }
}
