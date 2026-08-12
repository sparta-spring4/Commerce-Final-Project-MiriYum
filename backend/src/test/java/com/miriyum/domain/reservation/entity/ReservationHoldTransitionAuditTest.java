package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReservationHoldTransitionAuditTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-12T01:00:00Z");
    private static final Instant OCCURRED_AT = REQUESTED_AT.plusSeconds(1);

    @Test
    void recordsAppendOnlyCreationTransitionWithPolicyVersions() {
        ReservationHoldTransitionAudit audit = ReservationHoldTransitionAudit.record(
                11L,
                "CONSUMER",
                22L,
                REQUESTED_AT,
                OCCURRED_AT,
                null,
                ReservationHoldStatus.ACTIVE,
                5L,
                3L,
                "reservation-hold:create:550e8400-e29b-41d4-a716-446655440000"
        );

        assertThat(audit.getReservationHoldId()).isEqualTo(11L);
        assertThat(audit.getActorType()).isEqualTo("CONSUMER");
        assertThat(audit.getActorId()).isEqualTo(22L);
        assertThat(audit.getRequestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(audit.getOccurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(audit.getBeforeStatus()).isNull();
        assertThat(audit.getAfterStatus()).isEqualTo(ReservationHoldStatus.ACTIVE);
        assertThat(audit.getReservationTimePolicyVersion()).isEqualTo(5L);
        assertThat(audit.getCapacityPolicyVersion()).isEqualTo(3L);
    }

    @Test
    void allowsSystemTransitionWithoutActorAccountId() {
        ReservationHoldTransitionAudit audit = ReservationHoldTransitionAudit.record(
                11L,
                "SYSTEM",
                null,
                REQUESTED_AT,
                OCCURRED_AT,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.EXPIRED,
                5L,
                3L,
                "reservation-hold:expire:550e8400-e29b-41d4-a716-446655440000"
        );

        assertThat(audit.getActorId()).isNull();
        assertThat(audit.getBeforeStatus()).isEqualTo(ReservationHoldStatus.ACTIVE);
        assertThat(audit.getAfterStatus()).isEqualTo(ReservationHoldStatus.EXPIRED);
    }

    @Test
    void rejectsInvalidCreationTimeStatusAndCommand() {
        assertThatIllegalArgumentException().isThrownBy(() -> audit(
                REQUESTED_AT.plusSeconds(1), REQUESTED_AT,
                null, ReservationHoldStatus.ACTIVE, "command"));
        assertThatIllegalArgumentException().isThrownBy(() -> audit(
                REQUESTED_AT, OCCURRED_AT,
                null, ReservationHoldStatus.EXPIRED, "command"));
        assertThatIllegalArgumentException().isThrownBy(() -> audit(
                REQUESTED_AT, OCCURRED_AT,
                ReservationHoldStatus.ACTIVE, ReservationHoldStatus.ACTIVE, "command"));
        assertThatIllegalArgumentException().isThrownBy(() -> audit(
                REQUESTED_AT, OCCURRED_AT,
                null, ReservationHoldStatus.ACTIVE, " "));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationHoldTransitionAudit.record(
                        11L, " ", 22L, REQUESTED_AT, OCCURRED_AT,
                        null, ReservationHoldStatus.ACTIVE, 5L, 3L, "command"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationHoldTransitionAudit.record(
                        11L, "CONSUMER", 0L, REQUESTED_AT, OCCURRED_AT,
                        null, ReservationHoldStatus.ACTIVE, 5L, 3L, "command"));
    }

    private static ReservationHoldTransitionAudit audit(
            Instant requestedAt,
            Instant occurredAt,
            ReservationHoldStatus beforeStatus,
            ReservationHoldStatus afterStatus,
            String commandId
    ) {
        return ReservationHoldTransitionAudit.record(
                11L,
                "CONSUMER",
                22L,
                requestedAt,
                occurredAt,
                beforeStatus,
                afterStatus,
                5L,
                3L,
                commandId
        );
    }
}
