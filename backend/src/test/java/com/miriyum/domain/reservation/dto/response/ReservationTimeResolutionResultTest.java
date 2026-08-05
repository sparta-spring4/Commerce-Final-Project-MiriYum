package com.miriyum.domain.reservation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ReservationTimeResolutionResultTest {

    @Test
    void publicServiceResultContainsOnlyPureScalarDtoTypes() {
        assertThat(Arrays.stream(
                        ReservationTimeResolutionResult.class.getRecordComponents())
                .map(RecordComponent::getType))
                .noneMatch(type -> type.isAnnotationPresent(Entity.class))
                .noneMatch(type -> type.isAnnotationPresent(Embeddable.class))
                .doesNotContain(ReservationTimeSnapshot.class);

        assertThat(ResolvedReservationTime.class.isAnnotationPresent(Entity.class))
                .isFalse();
        assertThat(ResolvedReservationTime.class.isAnnotationPresent(Embeddable.class))
                .isFalse();
    }

    @Test
    void resolvedResultCopiesTheCompleteTimeCalculationAsScalars() {
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

        ReservationTimeResolutionResult result =
                ReservationTimeResolutionResult.resolved(
                        11L,
                        ResolvedReservationTime.from(snapshot)
                );

        assertThat(result.status()).isEqualTo(ReservationTimeResolutionStatus.RESOLVED);
        assertThat(result.time()).satisfies(time -> {
            assertThat(time.policyStoreId()).isEqualTo(11L);
            assertThat(time.policyVersion()).isEqualTo(5L);
            assertThat(time.startAt())
                    .isEqualTo(Instant.parse("2026-08-03T14:30:00Z"));
            assertThat(time.serviceEndAt())
                    .isEqualTo(Instant.parse("2026-08-03T16:00:00Z"));
            assertThat(time.occupancyEndAt())
                    .isEqualTo(Instant.parse("2026-08-03T16:30:00Z"));
            assertThat(time.timeZoneId()).isEqualTo("Asia/Seoul");
            assertThat(time.slotIntervalMinutes()).isEqualTo(30);
            assertThat(time.serviceDurationMinutes()).isEqualTo(90);
            assertThat(time.turnoverDurationMinutes()).isEqualTo(30);
        });
    }
}
