package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.miriyum.domain.reservation.entity.ReservationCancellationActorType;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import java.time.Instant;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ReservationCancellationPolicyEvaluatorTest {

    private static final Instant START_AT = Instant.parse("2026-08-06T12:00:00Z");

    private final ReservationCancellationPolicyEvaluator evaluator =
            new ReservationCancellationPolicyEvaluator(new ReservationCancellationPolicyRegistry());

    @Test
    void allowsBothActorsBeforeAtAndAfterStartForConfirmedVersionOneReservations() {
        Instant[] requestedTimes = {
                START_AT.minusNanos(1),
                START_AT,
                START_AT.plusNanos(1)
        };

        for (ReservationCancellationActorType actor : ReservationCancellationActorType.values()) {
            for (Instant requestedAt : requestedTimes) {
                assertThat(evaluator.evaluate(
                        1L,
                        actor,
                        ReservationStatus.CONFIRMED,
                        START_AT,
                        requestedAt
                )).isEqualTo(ReservationCancellationDecision.ALLOWED);
            }
        }
    }

    @Test
    void rejectsNonConfirmedStatusBeforeInspectingAnUnknownPolicyVersion() {
        for (ReservationStatus status : EnumSet.complementOf(
                EnumSet.of(ReservationStatus.CONFIRMED))) {
            assertThat(evaluator.evaluate(
                    2L,
                    ReservationCancellationActorType.CONSUMER,
                    status,
                    START_AT,
                    START_AT
            )).isEqualTo(ReservationCancellationDecision.REJECTED_INVALID_STATE);
        }
    }

    @Test
    void rejectsNonConfirmedStatusBeforeValidatingActor() {
        assertThat(evaluator.evaluate(
                1L,
                null,
                ReservationStatus.CANCELLED,
                START_AT,
                START_AT
        )).isEqualTo(ReservationCancellationDecision.REJECTED_INVALID_STATE);
    }

    @Test
    void rejectsNullAndUnknownVersionsByPolicyForConfirmedReservations() {
        Long[] storedVersions = {null, 0L, -1L, 2L};

        for (Long storedVersion : storedVersions) {
            assertThat(evaluator.evaluate(
                    storedVersion,
                    ReservationCancellationActorType.STORE_OPERATOR,
                    ReservationStatus.CONFIRMED,
                    START_AT,
                    START_AT
            )).isEqualTo(ReservationCancellationDecision.REJECTED_BY_POLICY);
        }
    }

    @Test
    void rejectsMissingRequiredEvaluationInputsAsProgrammerErrors() {
        assertThatIllegalArgumentException().isThrownBy(() -> evaluator.evaluate(
                1L, null, ReservationStatus.CONFIRMED, START_AT, START_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> evaluator.evaluate(
                1L, ReservationCancellationActorType.CONSUMER, null, START_AT, START_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> evaluator.evaluate(
                1L, ReservationCancellationActorType.CONSUMER,
                ReservationStatus.CONFIRMED, null, START_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> evaluator.evaluate(
                1L, ReservationCancellationActorType.CONSUMER,
                ReservationStatus.CONFIRMED, START_AT, null));
    }

    @Test
    void returnsTheSameDecisionForTheSameInput() {
        ReservationCancellationDecision first = evaluator.evaluate(
                1L,
                ReservationCancellationActorType.CONSUMER,
                ReservationStatus.CONFIRMED,
                START_AT,
                START_AT.plusSeconds(3600)
        );

        ReservationCancellationDecision second = evaluator.evaluate(
                1L,
                ReservationCancellationActorType.CONSUMER,
                ReservationStatus.CONFIRMED,
                START_AT,
                START_AT.plusSeconds(3600)
        );

        assertThat(second).isEqualTo(first);
    }
}
