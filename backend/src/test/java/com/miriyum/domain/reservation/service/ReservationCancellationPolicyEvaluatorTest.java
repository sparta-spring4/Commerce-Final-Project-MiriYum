package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.miriyum.domain.reservation.entity.ReservationCancellationActorType;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import java.time.Instant;
import java.util.EnumSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
        Long[] storedVersions = {null, 0L, -1L, 3L};

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

    @ParameterizedTest
    @MethodSource("consumerDispositionBoundaries")
    void evaluatesConsumerDispositionAtExactPolicyBoundaries(
            Instant confirmedAt,
            Instant startAt,
            Instant requestedAt,
            int expectedBasisPoints
    ) {
        ReservationDepositDispositionDecision result = evaluator.evaluateDepositDisposition(
                2L,
                ReservationDepositDispositionDecision.Responsibility.CONSUMER,
                confirmedAt,
                startAt,
                requestedAt
        );

        assertThat(result.policyVersion()).isEqualTo(2L);
        assertThat(result.responsibility())
                .isEqualTo(ReservationDepositDispositionDecision.Responsibility.CONSUMER);
        assertThat(result.targetRefundRateBasisPoints()).isEqualTo(expectedBasisPoints);
    }

    @Test
    void fullyRefundsStoreAndPlatformResponsibleCancellations() {
        Instant confirmedAt = START_AT.minusSeconds(86_400);

        for (ReservationDepositDispositionDecision.Responsibility responsibility :
                EnumSet.of(
                        ReservationDepositDispositionDecision.Responsibility.STORE_RESPONSIBLE,
                        ReservationDepositDispositionDecision.Responsibility.PLATFORM_RESPONSIBLE)) {
            assertThat(evaluator.evaluateDepositDisposition(
                    2L,
                    responsibility,
                    confirmedAt,
                    START_AT,
                    START_AT.plusSeconds(1)
            ).targetRefundRateBasisPoints()).isEqualTo(10_000);
        }
    }

    @Test
    void rejectsDispositionEvaluationForV1NullAndUnknownVersions() {
        for (Long storedVersion : new Long[]{1L, null, 3L}) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    evaluator.evaluateDepositDisposition(
                            storedVersion,
                            ReservationDepositDispositionDecision.Responsibility.CONSUMER,
                            START_AT.minusSeconds(60),
                            START_AT,
                            START_AT.minusSeconds(1)
                    ));
        }
    }

    private static Stream<Arguments> consumerDispositionBoundaries() {
        Instant startAt = Instant.parse("2026-08-10T12:00:00Z");
        Instant earlyConfirmedAt = Instant.parse("2026-08-01T00:00:00Z");
        Instant lateConfirmedAt = startAt.minusSeconds(20 * 60L);
        return Stream.of(
                Arguments.of(lateConfirmedAt, startAt,
                        lateConfirmedAt.plusSeconds(600), 10_000),
                Arguments.of(lateConfirmedAt, startAt,
                        lateConfirmedAt.plusSeconds(600).plusNanos(1), 0),
                Arguments.of(earlyConfirmedAt, startAt,
                        startAt.minusSeconds(48 * 3600L), 10_000),
                Arguments.of(earlyConfirmedAt, startAt,
                        startAt.minusSeconds(48 * 3600L).plusNanos(1), 5_000),
                Arguments.of(earlyConfirmedAt, startAt,
                        startAt.minusSeconds(24 * 3600L), 5_000),
                Arguments.of(earlyConfirmedAt, startAt,
                        startAt.minusSeconds(24 * 3600L).plusNanos(1), 0)
        );
    }
}
