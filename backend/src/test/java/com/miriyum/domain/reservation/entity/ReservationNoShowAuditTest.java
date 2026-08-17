package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationNoShowAuditTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-16T01:00:00Z");
    private static final Instant OCCURRED_AT = REQUESTED_AT.plusSeconds(1);

    @Test
    @DisplayName("운영자 노쇼 감사가 필수 후보 사유와 CONFIRMED에서 NO_SHOW 전이를 기록한다")
    void recordsNoShow() {
        ReservationNoShowAudit audit = ReservationNoShowAudit.record(
                77L, 22L, 33L, ReservationNoShowReason.UNCLEAR,
                REQUESTED_AT, OCCURRED_AT, 5L, 8L,
                "reservation-no-show:33:key"
        );

        assertThat(audit.getActorType()).isEqualTo(ReservationVisitActorType.STORE_OPERATOR);
        assertThat(audit.getReason()).isEqualTo(ReservationNoShowReason.UNCLEAR);
        assertThat(audit.getBeforeStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(audit.getAfterStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(audit.getReservationTimePolicyVersion()).isEqualTo(5L);
        assertThat(audit.getCapacityPolicyVersion()).isEqualTo(8L);
    }

    @Test
    @DisplayName("노쇼 감사 사유·식별자·시각은 필수다")
    void rejectsInvalidNoShowAudit() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationNoShowAudit.record(
                        77L, 22L, 33L, null, REQUESTED_AT, OCCURRED_AT,
                        5L, 8L, "command")
        );
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationNoShowAudit.record(
                        77L, 22L, 0L, ReservationNoShowReason.UNCLEAR,
                        REQUESTED_AT, OCCURRED_AT, 5L, 8L, "command")
        );
    }
}
