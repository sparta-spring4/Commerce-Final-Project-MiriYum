package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyDraftRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyPublicationCancellationRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyPublicationRequest;
import com.miriyum.domain.reservation.dto.response.ReservationTimePolicyResponse;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntToLongFunction;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class ReservationTimePolicyCommandFacade {

    private static final int MAX_ATTEMPTS = 3;
    private static final List<String> POLICY_CONSTRAINT_MARKERS = List.of(
            "uk_reservation_time_policy_store_version",
            "uk_reservation_time_policy_active_store",
            "uk_reservation_time_policy_scheduled_store",
            "ck_reservation_time_policy_identity",
            "ck_reservation_time_policy_durations",
            "ck_reservation_time_policy_status",
            "ck_reservation_time_policy_lifecycle"
    );

    private final ReservationService reservationService;
    private final IntToLongFunction retryDelayMillis;
    private final RetrySleeper retrySleeper;

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Autowired
    public ReservationTimePolicyCommandFacade(ReservationService reservationService) {
        this(
                reservationService,
                ReservationTimePolicyCommandFacade::defaultDelayMillis,
                Thread::sleep
        );
    }

    ReservationTimePolicyCommandFacade(
            ReservationService reservationService,
            IntToLongFunction retryDelayMillis,
            RetrySleeper retrySleeper
    ) {
        this.reservationService = reservationService;
        this.retryDelayMillis = retryDelayMillis;
        this.retrySleeper = retrySleeper;
    }

    public ReservationTimePolicyCommandResult<ReservationTimePolicyResponse> createDraft(
            long operatorId,
            long storeId,
            IdempotencyKey key,
            ReservationTimePolicyDraftRequest request
    ) {
        return executeWithRetry(() -> reservationService.createTimePolicyDraft(
                operatorId,
                storeId,
                key,
                request
        ));
    }

    public ReservationTimePolicyCommandResult<ReservationTimePolicyResponse> publish(
            long operatorId,
            long storeId,
            long version,
            IdempotencyKey key,
            ReservationTimePolicyPublicationRequest request
    ) {
        return executeWithRetry(() -> reservationService.publishTimePolicy(
                operatorId,
                storeId,
                version,
                key,
                request
        ));
    }

    public ReservationTimePolicyCommandResult<ReservationTimePolicyResponse> cancelPublication(
            long operatorId,
            long storeId,
            long version,
            IdempotencyKey key,
            ReservationTimePolicyPublicationCancellationRequest request
    ) {
        return executeWithRetry(() -> reservationService.cancelTimePolicyPublication(
                operatorId,
                storeId,
                version,
                key,
                request
        ));
    }

    private <T> T executeWithRetry(Supplier<T> command) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return command.get();
            } catch (DataIntegrityViolationException exception) {
                if (containsPolicyConstraint(exception)) {
                    throw policyConflict(exception);
                }
                throw exception;
            } catch (ConcurrencyFailureException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    throw concurrentModification(exception);
                }
                sleepBeforeRetry(attempt, exception);
            }
        }
        throw new IllegalStateException("unreachable retry state");
    }

    private void sleepBeforeRetry(int failedAttempt, RuntimeException failure) {
        try {
            retrySleeper.sleep(retryDelayMillis.applyAsLong(failedAttempt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            exception.addSuppressed(failure);
            throw concurrentModification(exception);
        }
    }

    private static long defaultDelayMillis(int failedAttempt) {
        return switch (failedAttempt) {
            case 1 -> ThreadLocalRandom.current().nextLong(100L, 201L);
            case 2 -> ThreadLocalRandom.current().nextLong(300L, 501L);
            default -> throw new IllegalArgumentException("unsupported retry attempt");
        };
    }

    private static boolean containsPolicyConstraint(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && POLICY_CONSTRAINT_MARKERS.stream()
                    .anyMatch(message::contains)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static ServiceException policyConflict(RuntimeException cause) {
        ServiceException conflict =
                new ServiceException(ReservationErrorCode.TIME_POLICY_CONFLICT);
        conflict.initCause(cause);
        return conflict;
    }

    private static ServiceException concurrentModification(Throwable cause) {
        ServiceException conflict =
                new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
        conflict.initCause(cause);
        return conflict;
    }
}
