package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationCheckInAuditTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-16T01:00:00Z");
    private static final Instant OCCURRED_AT = REQUESTED_AT.plusSeconds(1);

    @Test
    @DisplayName("QR 발급 감사에는 token version만 남기고 CONFIRMED 상태를 유지한다")
    void recordsGrantIssuanceWithoutCredentialMaterial() {
        ReservationCheckInAudit audit = ReservationCheckInAudit.recordGrantIssued(
                77L, 22L, 11L, 3L, REQUESTED_AT, OCCURRED_AT,
                "reservation-qr-grant:77:3"
        );

        assertThat(audit.getEventType()).isEqualTo(ReservationCheckInEventType.QR_GRANT_ISSUED);
        assertThat(audit.getActorType()).isEqualTo(ReservationVisitActorType.CONSUMER);
        assertThat(audit.getBeforeStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(audit.getAfterStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(audit.getTokenVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("QR 성공 감사는 STORE_OPERATOR의 CONFIRMED에서 FULFILLED 전이를 기록한다")
    void recordsQrFulfillment() {
        ReservationCheckInAudit audit = ReservationCheckInAudit.recordQrFulfilled(
                77L, 22L, 33L, 4L, REQUESTED_AT, OCCURRED_AT,
                "reservation-qr-check-in:33:key"
        );

        assertThat(audit.getEventType())
                .isEqualTo(ReservationCheckInEventType.QR_CHECK_IN_FULFILLED);
        assertThat(audit.getActorType()).isEqualTo(ReservationVisitActorType.STORE_OPERATOR);
        assertThat(audit.getBeforeStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(audit.getAfterStatus()).isEqualTo(ReservationStatus.FULFILLED);
    }

    @Test
    @DisplayName("감사 식별자·version·시각이 유효하지 않으면 거부한다")
    void rejectsInvalidAuditFields() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCheckInAudit.recordGrantIssued(
                        0L, 22L, 11L, 1L, REQUESTED_AT, OCCURRED_AT, "command")
        );
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCheckInAudit.recordQrFulfilled(
                        77L, 22L, 33L, 0L, REQUESTED_AT, OCCURRED_AT, "command")
        );
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCheckInAudit.recordQrFulfilled(
                        77L, 22L, 33L, 1L, OCCURRED_AT, REQUESTED_AT, "command")
        );
    }
}
