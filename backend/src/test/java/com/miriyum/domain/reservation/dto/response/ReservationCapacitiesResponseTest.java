package com.miriyum.domain.reservation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReservationCapacitiesResponseTest {

    @Test
    void exposesConfiguredCapacityAndClampsNegativeAvailabilityToZero() {
        // given
        LocalDate serviceDate = LocalDate.of(2026, 8, 10);
        ReservationCapacityBucket bucket = ReservationCapacityBucket.create(
                7L,
                serviceDate,
                LocalTime.of(18, 0),
                LocalTime.of(18, 30),
                3,
                1,
                5,
                2,
                1,
                3,
                true,
                2L
        );
        ReflectionTestUtils.setField(bucket, "id", 41L);

        // when
        ReservationCapacitiesResponse response =
                ReservationCapacitiesResponse.from(serviceDate, 2L, List.of(bucket));

        // then
        assertThat(response.serviceDate()).isEqualTo(serviceDate);
        assertThat(response.policyVersion()).isEqualTo(2L);
        assertThat(response.buckets()).singleElement().satisfies(result -> {
            assertThat(result.capacityBucketId()).isEqualTo("41");
            assertThat(result.startTime()).isEqualTo(LocalTime.of(18, 0));
            assertThat(result.endTime()).isEqualTo(LocalTime.of(18, 30));
            assertThat(result.maxPeople()).isEqualTo(3);
            assertThat(result.maxTeams()).isEqualTo(1);
            assertThat(result.occupiedPeople()).isEqualTo(5);
            assertThat(result.occupiedTeams()).isEqualTo(2);
            assertThat(result.availablePeople()).isZero();
            assertThat(result.availableTeams()).isZero();
        });
    }
}
