package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.request.ReservationCreateRequest;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntToLongFunction;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Retries only the technical database lock conflicts from reservation creation.
 *
 * <p>The delegated service owns each attempt's transaction boundary.</p>
 */
@Service
public class ReservationCreationCommandFacade {

    private static final int MAX_ATTEMPTS = 3;

    private final ReservationService reservationService;
    private final IntToLongFunction retryDelayMillis;
    private final RetrySleeper retrySleeper;

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public ReservationCreationCommandFacade(ReservationService reservationService) {
        this(
                reservationService,
                ReservationCreationCommandFacade::defaultDelayMillis,
                Thread::sleep
        );
    }

    ReservationCreationCommandFacade(
            ReservationService reservationService,
            IntToLongFunction retryDelayMillis,
            RetrySleeper retrySleeper
    ) {
        this.reservationService = reservationService;
        this.retryDelayMillis = retryDelayMillis;
        this.retrySleeper = retrySleeper;
    }

    /**
     * Creates one consumer reservation command.
     *
     * @param consumerAccountId authenticated consumer account identifier
     * @param key command idempotency key
     * @param request reservation creation input
     * @return reservation creation HTTP result
     */
    public ReservationCreationCommandResult create(
            long consumerAccountId,
            IdempotencyKey key,
            ReservationCreateRequest request
    ) {
        return executeWithRetry(() -> reservationService.createReservation(
                consumerAccountId, key, request));
    }

    private <T> T executeWithRetry(Supplier<T> command) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return command.get();
            } catch (RuntimeException exception) {
                if (!containsRetryableMysqlLockFailure(exception)) {
                    throw exception;
                }
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

    private static boolean containsRetryableMysqlLockFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && (sqlException.getErrorCode() == 1213
                    || sqlException.getErrorCode() == 1205)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static ServiceException concurrentModification(Throwable cause) {
        ServiceException conflict = new ServiceException(
                CommonErrorCode.CONCURRENT_MODIFICATION
        );
        conflict.initCause(cause);
        return conflict;
    }

}
