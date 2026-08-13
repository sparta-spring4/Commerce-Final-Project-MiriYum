package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ReservationHoldTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-12T01:02:03.123456Z");
    private static final String CREATION_COMMAND_ID =
            "reservation-hold:create:550e8400-e29b-41d4-a716-446655440000";

    @Test
    void createsSeparateActiveHoldWithExactlyTenMinuteExpiration() {
        ReservationHold hold = hold(
                11L,
                22L,
                "Miri Yum Restaurant",
                3L,
                new ReservationCancellationPolicyVersion(1L),
                CREATION_COMMAND_ID,
                CREATED_AT
        );

        assertThat(hold.getConsumerAccountId()).isEqualTo(11L);
        assertThat(hold.getStoreId()).isEqualTo(22L);
        assertThat(hold.getStoreNameSnapshot()).isEqualTo("Miri Yum Restaurant");
        assertThat(hold.getServiceDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(hold.getStartAt()).isEqualTo(Instant.parse("2026-08-12T09:00:00Z"));
        assertThat(hold.getServiceEndAt()).isEqualTo(Instant.parse("2026-08-12T10:30:00Z"));
        assertThat(hold.getOccupancyEndAt()).isEqualTo(Instant.parse("2026-08-12T10:45:00Z"));
        assertThat(hold.getTimeZoneId()).isEqualTo("Asia/Seoul");
        assertThat(hold.getParty().totalCount()).isEqualTo(3);
        assertThat(hold.getContactSnapshot().getNotificationTargetReference())
                .isEqualTo("consumer:11:channel:primary");
        assertThat(hold.getCapacityPolicyVersion()).isEqualTo(3L);
        assertThat(hold.getCancellationPolicyVersion()).isEqualTo(1L);
        assertThat(hold.getReservationTimePolicyVersion()).isEqualTo(5L);
        assertThat(hold.getStatus()).isEqualTo(ReservationHoldStatus.ACTIVE);
        assertThat(hold.getStatusVersion()).isZero();
        assertThat(hold.getCreationCommandId()).isEqualTo(CREATION_COMMAND_ID);
        assertThat(hold.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(hold.getExpiresAt()).isEqualTo(CREATED_AT.plus(Duration.ofMinutes(10)));
    }

    @Test
    void exposesOnlyApprovedPersistenceStates() {
        assertThat(ReservationHoldStatus.values()).containsExactly(
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                ReservationHoldStatus.CONFIRMED,
                ReservationHoldStatus.RELEASED,
                ReservationHoldStatus.EXPIRED
        );
    }

    @Test
    void rejectsInvalidIdentityPolicyAndCreationCommand() {
        assertThatIllegalArgumentException().isThrownBy(() -> hold(
                0L, 22L, "Miri Yum Restaurant", 3L,
                new ReservationCancellationPolicyVersion(1L), CREATION_COMMAND_ID, CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> hold(
                11L, 0L, "Miri Yum Restaurant", 3L,
                new ReservationCancellationPolicyVersion(1L), CREATION_COMMAND_ID, CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> hold(
                11L, 22L, " ", 3L,
                new ReservationCancellationPolicyVersion(1L), CREATION_COMMAND_ID, CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> hold(
                11L, 22L, "Miri Yum Restaurant", 0L,
                new ReservationCancellationPolicyVersion(1L), CREATION_COMMAND_ID, CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> hold(
                11L, 22L, "Miri Yum Restaurant", 3L,
                null, CREATION_COMMAND_ID, CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> hold(
                11L, 22L, "Miri Yum Restaurant", 3L,
                new ReservationCancellationPolicyVersion(1L), " ", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> hold(
                11L, 22L, "Miri Yum Restaurant", 3L,
                new ReservationCancellationPolicyVersion(1L), "x".repeat(101), CREATED_AT));
    }

    @Test
    void activeHoldTransitionsToConfirmedWithoutReleasingCapacity() {
        ReservationHold hold = hold();

        hold.confirm();

        assertThat(hold.getStatus()).isEqualTo(ReservationHoldStatus.CONFIRMED);
        assertThat(hold.getStatus().requiresCapacityRelease()).isFalse();
    }

    @Test
    void activeHoldTransitionsToReleasedAndRequiresCapacityRelease() {
        ReservationHold hold = hold();

        hold.release();

        assertThat(hold.getStatus()).isEqualTo(ReservationHoldStatus.RELEASED);
        assertThat(hold.getStatus().requiresCapacityRelease()).isTrue();
    }

    @Test
    void activeHoldTransitionsToReconciliationRequiredWithoutReleasingCapacity() {
        ReservationHold hold = hold();

        hold.requireReconciliation();

        assertThat(hold.getStatus())
                .isEqualTo(ReservationHoldStatus.RECONCILIATION_REQUIRED);
        assertThat(hold.getStatus().requiresCapacityRelease()).isFalse();
    }

    @Test
    void activeHoldCannotExpireBeforeItsExpirationBoundary() {
        ReservationHold hold = hold();

        assertInvalidTransition(() -> hold.expire(
                CREATED_AT.plus(Duration.ofMinutes(10)).minusNanos(1)
        ));
        assertThat(hold.getStatus()).isEqualTo(ReservationHoldStatus.ACTIVE);
    }

    @Test
    void activeHoldExpiresAtItsExpirationBoundaryAndRequiresCapacityRelease() {
        ReservationHold hold = hold();

        hold.expire(CREATED_AT.plus(Duration.ofMinutes(10)));

        assertThat(hold.getStatus()).isEqualTo(ReservationHoldStatus.EXPIRED);
        assertThat(hold.getStatus().requiresCapacityRelease()).isTrue();
    }

    @Test
    void activeHoldExpiresAfterItsExpirationBoundary() {
        ReservationHold hold = hold();

        hold.expire(CREATED_AT.plus(Duration.ofMinutes(10)).plusNanos(1));

        assertThat(hold.getStatus()).isEqualTo(ReservationHoldStatus.EXPIRED);
    }

    @Test
    void reconciliationRequiredHoldCanOnlyRecoverToConfirmedOrReleased() {
        ReservationHold confirmed = hold();
        confirmed.requireReconciliation();
        confirmed.confirm();

        ReservationHold released = hold();
        released.requireReconciliation();
        released.release();

        assertThat(confirmed.getStatus()).isEqualTo(ReservationHoldStatus.CONFIRMED);
        assertThat(released.getStatus()).isEqualTo(ReservationHoldStatus.RELEASED);
    }

    @Test
    void reconciliationRequiredHoldRejectsExpirationAndRepeatedReconciliation() {
        ReservationHold hold = hold();
        hold.requireReconciliation();

        assertInvalidTransition(() -> hold.expire(hold.getExpiresAt()));
        assertInvalidTransition(hold::requireReconciliation);
        assertThat(hold.getStatus())
                .isEqualTo(ReservationHoldStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    void terminalHoldRejectsEveryFurtherTransition() {
        ReservationHold hold = hold();
        hold.confirm();

        assertInvalidTransition(hold::confirm);
        assertInvalidTransition(hold::release);
        assertInvalidTransition(() -> hold.expire(hold.getExpiresAt()));
        assertInvalidTransition(hold::requireReconciliation);
        assertThat(hold.getStatus()).isEqualTo(ReservationHoldStatus.CONFIRMED);
    }

    private static void assertInvalidTransition(Runnable transition) {
        assertThatThrownBy(transition::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
    }

    private static ReservationHold hold() {
        return hold(
                11L,
                22L,
                "Miri Yum Restaurant",
                3L,
                new ReservationCancellationPolicyVersion(1L),
                CREATION_COMMAND_ID,
                CREATED_AT
        );
    }

    private static ReservationHold hold(
            Long consumerAccountId,
            Long storeId,
            String storeNameSnapshot,
            long capacityPolicyVersion,
            ReservationCancellationPolicyVersion cancellationPolicyVersion,
            String creationCommandId,
            Instant createdAt
    ) {
        return ReservationHold.active(
                consumerAccountId,
                storeId,
                storeNameSnapshot,
                timeSnapshot(),
                PartyComposition.of(2, 1, 0),
                ReservationContactSnapshot.contactable("consumer:11:channel:primary"),
                capacityPolicyVersion,
                cancellationPolicyVersion,
                creationCommandId,
                createdAt
        );
    }

    private static ReservationTimeSnapshot timeSnapshot() {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                22L,
                5L,
                30,
                90,
                15
        );
        policy.activate(Instant.parse("2026-08-11T00:00:00Z"), "활성 정책");
        return ReservationTimeSnapshot.calculate(
                policy,
                LocalDateTime.of(2026, 8, 12, 18, 0),
                ZoneId.of("Asia/Seoul"),
                null
        );
    }
}
