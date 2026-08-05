package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationTimeSnapshotTest {

    @Test
    @DisplayName("서비스 종료와 전환 포함 점유 종료를 Instant로 분리해 자정 넘김을 보존한다")
    void calculatesServiceAndOccupancyEndsAcrossMidnight() {
        // given
        ReservationTimePolicyVersion policy = activePolicy(30, 90, 30);

        // when
        ReservationTimeSnapshot snapshot = ReservationTimeSnapshot.calculate(
                policy,
                LocalDateTime.of(2026, 8, 3, 23, 30),
                ZoneId.of("Asia/Seoul"),
                null
        );

        // then
        assertThat(snapshot.getServiceDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(snapshot.getStartAt()).isEqualTo(Instant.parse("2026-08-03T14:30:00Z"));
        assertThat(snapshot.getServiceEndAt())
                .isEqualTo(Instant.parse("2026-08-03T16:00:00Z"));
        assertThat(snapshot.getOccupancyEndAt())
                .isEqualTo(Instant.parse("2026-08-03T16:30:00Z"));
        assertThat(snapshot.getTimeZoneId()).isEqualTo("Asia/Seoul");
        assertThat(snapshot.getStartOffsetSeconds()).isEqualTo(9 * 3600);
        assertThat(snapshot.getServiceEndOffsetSeconds()).isEqualTo(9 * 3600);
        assertThat(snapshot.getOccupancyEndOffsetSeconds()).isEqualTo(9 * 3600);
        assertThat(snapshot.getSlotIntervalMinutes()).isEqualTo(30);
        assertThat(snapshot.getServiceDurationMinutes()).isEqualTo(90);
        assertThat(snapshot.getTurnoverDurationMinutes()).isEqualTo(30);
        assertThat(snapshot.getReservationTimePolicyStoreId()).isEqualTo(11L);
        assertThat(snapshot.getReservationTimePolicyVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("DST에서 존재하지 않는 매장 현지 시작 시각을 거부한다")
    void rejectsNonexistentDstLocalTime() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationTimeSnapshot.calculate(
                        activePolicy(30, 60, 0),
                        LocalDateTime.of(2026, 3, 29, 2, 30),
                        ZoneId.of("Europe/Paris"),
                        null
                ));
    }

    @Test
    @DisplayName("DST 중복 현지 시각은 명시적 offset이 없으면 거부한다")
    void rejectsAmbiguousDstLocalTimeWithoutOffset() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationTimeSnapshot.calculate(
                        activePolicy(30, 60, 0),
                        LocalDateTime.of(2026, 10, 25, 2, 30),
                        ZoneId.of("Europe/Paris"),
                        null
                ));
    }

    @Test
    @DisplayName("DST 중복 현지 시각은 명시한 유효 offset으로 서로 다른 Instant를 식별한다")
    void resolvesAmbiguousDstLocalTimeWithExplicitOffset() {
        ReservationTimePolicyVersion policy = activePolicy(30, 60, 0);
        LocalDateTime localStart = LocalDateTime.of(2026, 10, 25, 2, 30);
        ZoneId zoneId = ZoneId.of("Europe/Paris");

        ReservationTimeSnapshot summer = ReservationTimeSnapshot.calculate(
                policy,
                localStart,
                zoneId,
                ZoneOffset.ofHours(2)
        );
        ReservationTimeSnapshot winter = ReservationTimeSnapshot.calculate(
                policy,
                localStart,
                zoneId,
                ZoneOffset.ofHours(1)
        );

        assertThat(summer.getStartAt()).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
        assertThat(winter.getStartAt()).isEqualTo(Instant.parse("2026-10-25T01:30:00Z"));
        assertThat(summer.getStartOffsetSeconds()).isEqualTo(7200);
        assertThat(winter.getStartOffsetSeconds()).isEqualTo(3600);
    }

    @Test
    @DisplayName("매장 시간대와 맞지 않는 명시적 offset을 거부한다")
    void rejectsOffsetMismatch() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationTimeSnapshot.calculate(
                        activePolicy(30, 60, 0),
                        LocalDateTime.of(2026, 8, 3, 18, 0),
                        ZoneId.of("Asia/Seoul"),
                        ZoneOffset.UTC
                ));
    }

    @Test
    @DisplayName("예약 시작 스냅샷은 초와 나노초가 없는 분 단위 시각만 허용한다")
    void rejectsStartOutsideMinutePrecision() {
        ReservationTimePolicyVersion policy = activePolicy(30, 60, 0);
        ZoneId zoneId = ZoneId.of("Asia/Seoul");

        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationTimeSnapshot.calculate(
                        policy,
                        LocalDateTime.of(2026, 8, 3, 18, 0, 1),
                        zoneId,
                        null
                ));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationTimeSnapshot.calculate(
                        policy,
                        LocalDateTime.of(2026, 8, 3, 18, 0, 0, 1),
                        zoneId,
                        null
                ));
    }

    @Test
    @DisplayName("원본 정책이 퇴역해도 이미 계산한 예약 시간 스냅샷은 바뀌지 않는다")
    void preservesCalculatedSnapshotAfterPolicyRetirement() {
        ReservationTimePolicyVersion policy = activePolicy(30, 90, 15);
        ReservationTimeSnapshot snapshot = ReservationTimeSnapshot.calculate(
                policy,
                LocalDateTime.of(2026, 8, 3, 18, 0),
                ZoneId.of("Asia/Seoul"),
                null
        );

        policy.retire();

        assertThat(snapshot.getStartAt())
                .isEqualTo(Instant.parse("2026-08-03T09:00:00Z"));
        assertThat(snapshot.getServiceEndAt())
                .isEqualTo(Instant.parse("2026-08-03T10:30:00Z"));
        assertThat(snapshot.getOccupancyEndAt())
                .isEqualTo(Instant.parse("2026-08-03T10:45:00Z"));
        assertThat(snapshot.getServiceDurationMinutes()).isEqualTo(90);
        assertThat(snapshot.getTurnoverDurationMinutes()).isEqualTo(15);
        assertThat(snapshot.getReservationTimePolicyVersion()).isEqualTo(1L);
    }

    private static ReservationTimePolicyVersion activePolicy(
            int slotIntervalMinutes,
            int serviceDurationMinutes,
            int turnoverDurationMinutes
    ) {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                11L,
                1L,
                slotIntervalMinutes,
                serviceDurationMinutes,
                turnoverDurationMinutes
        );
        policy.activate(Instant.parse("2026-08-01T00:00:00Z"), "활성 정책");
        return policy;
    }
}
