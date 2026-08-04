package com.miriyum.domain.reservation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CustomerReservationTimeResponseTest {

    @Test
    @DisplayName("고객 응답은 offset 포함 서비스 종료만 제공하고 내부 점유 종료를 노출하지 않는다")
    void exposesServiceEndWithoutOccupancyEnd() {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                11L,
                5L,
                30,
                90,
                30
        );
        policy.activate(Instant.parse("2026-08-01T00:00:00Z"), "활성 정책");
        ReservationTimeSnapshot snapshot = ReservationTimeSnapshot.calculate(
                policy,
                LocalDateTime.of(2026, 8, 3, 23, 30),
                ZoneId.of("Asia/Seoul"),
                null
        );

        CustomerReservationTimeResponse response =
                CustomerReservationTimeResponse.from(snapshot);

        assertThat(response.serviceDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(response.timeStatus())
                .isEqualTo(CustomerReservationTimeStatus.RESOLVED);
        assertThat(response.startAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-03T23:30:00+09:00"));
        assertThat(response.serviceEndAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-04T01:00:00+09:00"));
        assertThat(response.timeZoneId()).isEqualTo("Asia/Seoul");
        assertThat(Arrays.stream(CustomerReservationTimeResponse.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly(
                        "serviceDate",
                        "timeStatus",
                        "startAt",
                        "serviceEndAt",
                        "timeZoneId"
                );
    }
}
