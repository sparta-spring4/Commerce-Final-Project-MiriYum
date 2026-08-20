package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.entity.ReservationCancellationActorType;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import java.time.Duration;
import java.time.Instant;

/**
 * 저장된 정책 버전과 예약 스냅샷으로 취소 가능 여부를 판정한다.
 */
public class ReservationCancellationPolicyEvaluator {

    private static final long DISPOSITION_POLICY_VERSION = 2L;
    private static final Duration GRACE_PERIOD = Duration.ofMinutes(10);
    private static final Duration FULL_REFUND_WINDOW = Duration.ofHours(48);
    private static final Duration HALF_REFUND_WINDOW = Duration.ofHours(24);

    private final ReservationCancellationPolicyRegistry registry;

    public ReservationCancellationPolicyEvaluator(
            ReservationCancellationPolicyRegistry registry) {
        this.registry = requireNonNull(registry, "registry");
    }

    public ReservationCancellationDecision evaluate(
            Long storedVersion,
            ReservationCancellationActorType actor,
            ReservationStatus status,
            Instant startAt,
            Instant requestedAt
    ) {
        ReservationStatus validatedStatus = requireNonNull(status, "status");
        if (validatedStatus != ReservationStatus.CONFIRMED) {
            return ReservationCancellationDecision.REJECTED_INVALID_STATE;
        }

        requireNonNull(actor, "actor");
        requireNonNull(startAt, "startAt");
        requireNonNull(requestedAt, "requestedAt");
        if (registry.findByStoredVersion(storedVersion).isEmpty()) {
            return ReservationCancellationDecision.REJECTED_BY_POLICY;
        }
        return ReservationCancellationDecision.ALLOWED;
    }

    public ReservationDepositDispositionDecision evaluateDepositDisposition(
            Long storedVersion,
            ReservationDepositDispositionDecision.Responsibility responsibility,
            Instant confirmedAt,
            Instant startAt,
            Instant requestedAt
    ) {
        ReservationCancellationPolicyVersionCheck.requireDispositionVersion(
                registry, storedVersion);
        ReservationDepositDispositionDecision.Responsibility validatedResponsibility =
                requireNonNull(responsibility, "responsibility");
        Instant validatedConfirmedAt = requireNonNull(confirmedAt, "confirmedAt");
        Instant validatedStartAt = requireNonNull(startAt, "startAt");
        Instant validatedRequestedAt = requireNonNull(requestedAt, "requestedAt");

        int targetRate = switch (validatedResponsibility) {
            case STORE_RESPONSIBLE, PLATFORM_RESPONSIBLE -> 10_000;
            case CONSUMER -> consumerTargetRate(
                    validatedConfirmedAt, validatedStartAt, validatedRequestedAt);
        };
        return new ReservationDepositDispositionDecision(
                DISPOSITION_POLICY_VERSION, validatedResponsibility, targetRate);
    }

    private static int consumerTargetRate(
            Instant confirmedAt,
            Instant startAt,
            Instant requestedAt
    ) {
        boolean withinGrace = !requestedAt.isAfter(confirmedAt.plus(GRACE_PERIOD))
                && requestedAt.isBefore(startAt);
        if (withinGrace || !requestedAt.isAfter(startAt.minus(FULL_REFUND_WINDOW))) {
            return 10_000;
        }
        if (!requestedAt.isAfter(startAt.minus(HALF_REFUND_WINDOW))) {
            return 5_000;
        }
        return 0;
    }

    private static final class ReservationCancellationPolicyVersionCheck {

        private ReservationCancellationPolicyVersionCheck() {
        }

        private static void requireDispositionVersion(
                ReservationCancellationPolicyRegistry registry,
                Long storedVersion
        ) {
            if (storedVersion == null
                    || storedVersion != DISPOSITION_POLICY_VERSION
                    || registry.findByStoredVersion(storedVersion).isEmpty()) {
                throw new IllegalArgumentException(
                        "storedVersion must be registered disposition policy version 2");
            }
        }
    }

    private static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }
}
