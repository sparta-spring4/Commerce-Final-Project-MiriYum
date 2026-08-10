package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReservationFulfillmentAuditTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-10T01:00:00Z");
    private static final Instant OCCURRED_AT = REQUESTED_AT.plusSeconds(1);
    private static final String COMMAND_ID =
            "reservation-fulfill:store-operator:33:550e8400-e29b-41d4-a716-446655440000";

    @Test
    void recordsAndRoundTripsEverySuccessfulStoreOperatorAuditField() {
        ReservationFulfillmentAudit audit = ReservationFulfillmentAudit.recordSuccess(
                77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
                REQUESTED_AT, OCCURRED_AT,
                ReservationStatus.CONFIRMED, ReservationStatus.FULFILLED,
                9L, 12L,
                "  " + COMMAND_ID + "  "
        );

        assertThat(audit.getReservationId()).isEqualTo(77L);
        assertThat(audit.getActorType()).isEqualTo(ReservationFulfillmentActorType.STORE_OPERATOR);
        assertThat(audit.getActorId()).isEqualTo(33L);
        assertThat(audit.getRequestedAt()).isSameAs(REQUESTED_AT);
        assertThat(audit.getOccurredAt()).isSameAs(OCCURRED_AT);
        assertThat(audit.getBeforeStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(audit.getAfterStatus()).isEqualTo(ReservationStatus.FULFILLED);
        assertThat(audit.getReservationTimePolicyVersion()).isEqualTo(9L);
        assertThat(audit.getCapacityPolicyVersion()).isEqualTo(12L);
        assertThat(audit.getCommandId()).isEqualTo(COMMAND_ID);
    }

    @Test
    void rejectsNullBlankAndOversizedCommandId() {
        assertThatThrownBy(() -> auditWithCommand(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> auditWithCommand("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> auditWithCommand("a".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveReservationAndActorIds() {
        assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
                0L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
                REQUESTED_AT, OCCURRED_AT, ReservationStatus.CONFIRMED,
                ReservationStatus.FULFILLED, 9L, 12L, COMMAND_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
                77L, ReservationFulfillmentActorType.STORE_OPERATOR, 0L,
                REQUESTED_AT, OCCURRED_AT, ReservationStatus.CONFIRMED,
                ReservationStatus.FULFILLED, 9L, 12L, COMMAND_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositivePolicyVersions() {
        assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
                77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
                REQUESTED_AT, OCCURRED_AT, ReservationStatus.CONFIRMED,
                ReservationStatus.FULFILLED, 0L, 12L, COMMAND_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
                77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
                REQUESTED_AT, OCCURRED_AT, ReservationStatus.CONFIRMED,
                ReservationStatus.FULFILLED, 9L, 0L, COMMAND_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullActorRequestedAndOccurredTimes() {
        assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
                77L, null, 33L, REQUESTED_AT, OCCURRED_AT,
                ReservationStatus.CONFIRMED, ReservationStatus.FULFILLED,
                9L, 12L, COMMAND_ID)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
                77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
                null, OCCURRED_AT, ReservationStatus.CONFIRMED,
                ReservationStatus.FULFILLED, 9L, 12L, COMMAND_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
                77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
                REQUESTED_AT, null, ReservationStatus.CONFIRMED,
                ReservationStatus.FULFILLED, 9L, 12L, COMMAND_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullStatusesAndEveryNonFulfillmentTransition() {
        assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
                77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
                REQUESTED_AT, OCCURRED_AT, null,
                ReservationStatus.FULFILLED, 9L, 12L, COMMAND_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
                77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
                REQUESTED_AT, OCCURRED_AT, ReservationStatus.CONFIRMED,
                null, 9L, 12L, COMMAND_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
                77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
                REQUESTED_AT, OCCURRED_AT, ReservationStatus.CANCELLED,
                ReservationStatus.FULFILLED, 9L, 12L, COMMAND_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
                77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
                REQUESTED_AT, OCCURRED_AT, ReservationStatus.CONFIRMED,
                ReservationStatus.CONFIRMED, 9L, 12L, COMMAND_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOccurredBeforeRequested() {
        assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
                77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
                OCCURRED_AT, REQUESTED_AT, ReservationStatus.CONFIRMED,
                ReservationStatus.FULFILLED, 9L, 12L, COMMAND_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ReservationFulfillmentAudit auditWithCommand(String commandId) {
        return ReservationFulfillmentAudit.recordSuccess(
                77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
                REQUESTED_AT, OCCURRED_AT,
                ReservationStatus.CONFIRMED, ReservationStatus.FULFILLED,
                9L, 12L, commandId
        );
    }
}
