package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.request.ConsumerCancellationRequest;
import com.miriyum.domain.reservation.dto.request.StoreCancellationRequest;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntToLongFunction;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Builds actor-scoped cancellation commands and retries only approved technical conflicts.
 *
 * <p>The delegated reservation service owns every attempt's transaction and replay boundary.</p>
 */
@Service
public class ReservationCancellationCommandFacade {

    private static final int MAX_ATTEMPTS = 3;
    private static final String COMMAND_TYPE = "RESERVATION_CANCEL";

    private final ReservationService reservationService;
    private final Clock clock;
    private final IntToLongFunction retryDelayMillis;
    private final RetrySleeper retrySleeper;

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Autowired
    public ReservationCancellationCommandFacade(
            ReservationService reservationService,
            Clock clock
    ) {
        this(
                reservationService,
                clock,
                ReservationCancellationCommandFacade::defaultDelayMillis,
                Thread::sleep
        );
    }

    ReservationCancellationCommandFacade(
            ReservationService reservationService,
            Clock clock,
            IntToLongFunction retryDelayMillis,
            RetrySleeper retrySleeper
    ) {
        this.reservationService = Objects.requireNonNull(
                reservationService, "reservationService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.retryDelayMillis = Objects.requireNonNull(
                retryDelayMillis, "retryDelayMillis must not be null");
        this.retrySleeper = Objects.requireNonNull(
                retrySleeper, "retrySleeper must not be null");
    }

    /**
     * Cancels one authenticated consumer's reservation with one stable logical command.
     *
     * @param consumerAccountId authenticated consumer account identifier
     * @param reservationId reservation identifier
     * @param key validated and normalized idempotency key
     * @param request consumer cancellation input
     * @return stored or freshly committed cancellation result
     */
    public ReservationCancellationCommandResult cancelByConsumer(
            long consumerAccountId,
            long reservationId,
            IdempotencyKey key,
            ConsumerCancellationRequest request
    ) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Instant requestedAt = clock.instant();
        String normalizedKey = key.value();
        String reason = request.reason();
        IdempotencyCommand command = new IdempotencyCommand(
                "consumer",
                consumerAccountId,
                COMMAND_TYPE,
                normalizedKey,
                RequestFingerprint.of(consumerCanonical(reservationId, reason))
        );
        String correlationId = correlation("consumer", consumerAccountId, normalizedKey);

        return executeWithRetry(() -> reservationService.cancelConsumerReservation(
                consumerAccountId,
                reservationId,
                command,
                reason,
                requestedAt,
                correlationId
        ));
    }

    /**
     * Cancels one reservation for the authenticated operator's managed store.
     *
     * @param operatorAccountId authenticated store-operator account identifier
     * @param storeId managed store identifier
     * @param reservationId reservation identifier
     * @param key validated and normalized idempotency key
     * @param request store-operator cancellation input
     * @return stored or freshly committed cancellation result
     */
    public ReservationCancellationCommandResult cancelByStoreOperator(
            long operatorAccountId,
            long storeId,
            long reservationId,
            IdempotencyKey key,
            StoreCancellationRequest request
    ) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Instant requestedAt = clock.instant();
        String normalizedKey = key.value();
        String reason = request.reason();
        IdempotencyCommand command = new IdempotencyCommand(
                "store-operator",
                operatorAccountId,
                COMMAND_TYPE,
                normalizedKey,
                RequestFingerprint.of(operatorCanonical(storeId, reservationId, reason))
        );
        String correlationId = correlation(
                "store-operator", operatorAccountId, normalizedKey);

        return executeWithRetry(() -> reservationService.cancelStoreReservation(
                operatorAccountId,
                storeId,
                reservationId,
                command,
                reason,
                requestedAt,
                correlationId
        ));
    }

    private <T> T executeWithRetry(Supplier<T> command) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return command.get();
            } catch (RuntimeException exception) {
                if (!isRetryableTechnicalFailure(exception)) {
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

    static long defaultDelayMillis(int failedAttempt) {
        return switch (failedAttempt) {
            case 1 -> ThreadLocalRandom.current().nextLong(100L, 201L);
            case 2 -> ThreadLocalRandom.current().nextLong(300L, 501L);
            default -> throw new IllegalArgumentException("unsupported retry attempt");
        };
    }

    private static boolean isRetryableTechnicalFailure(RuntimeException failure) {
        if (isExplicitlyNonRetryable(failure)) {
            return false;
        }
        if (failure instanceof ObjectOptimisticLockingFailureException) {
            return true;
        }
        return failure instanceof CannotAcquireLockException
                && containsRetryableMysqlLockFailure(failure);
    }

    private static boolean isExplicitlyNonRetryable(RuntimeException failure) {
        return failure instanceof ServiceException
                || failure instanceof IllegalArgumentException
                || failure instanceof QueryTimeoutException
                || failure instanceof DataIntegrityViolationException;
    }

    private static boolean containsRetryableMysqlLockFailure(Throwable failure) {
        Throwable current = failure;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && visited.add(current)) {
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
                CommonErrorCode.CONCURRENT_MODIFICATION);
        conflict.initCause(cause);
        return conflict;
    }

    private static String consumerCanonical(long reservationId, String reason) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(canonical, "method", "POST");
        appendCanonical(
                canonical,
                "route",
                "/api/v1/consumers/me/reservations/{reservationId}/cancellations"
        );
        appendCanonical(canonical, "reservationId", String.valueOf(reservationId));
        appendCanonical(canonical, "reason", reason);
        return canonical.toString();
    }

    private static String operatorCanonical(long storeId, long reservationId, String reason) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(canonical, "method", "POST");
        appendCanonical(
                canonical,
                "route",
                "/api/v1/store-operators/stores/{storeId}/reservations/"
                        + "{reservationId}/cancellations"
        );
        appendCanonical(canonical, "storeId", String.valueOf(storeId));
        appendCanonical(canonical, "reservationId", String.valueOf(reservationId));
        appendCanonical(canonical, "reason", reason);
        return canonical.toString();
    }

    private static void appendCanonical(StringBuilder target, String field, String value) {
        target.append(field).append('=');
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.length()).append(':').append(value);
        }
        target.append('|');
    }

    private static String correlation(String namespace, long actorId, String normalizedKey) {
        return "reservation-cancel:"
                + namespace
                + ":"
                + actorId
                + ":"
                + normalizedKey;
    }
}
