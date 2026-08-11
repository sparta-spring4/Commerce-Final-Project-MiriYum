package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.request.ReservationFulfillmentRequest;
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

/** 방문 완료 명령을 구성하고 승인된 기술 충돌만 제한 재시도한다. */
@Service
public class ReservationFulfillmentCommandFacade {

    private static final int MAX_ATTEMPTS = 3;

    private final ReservationService reservationService;
    private final Clock clock;
    private final IntToLongFunction retryDelayMillis;
    private final RetrySleeper retrySleeper;

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Autowired
    public ReservationFulfillmentCommandFacade(
            ReservationService reservationService,
            Clock clock
    ) {
        this(
                reservationService,
                clock,
                ReservationFulfillmentCommandFacade::defaultDelayMillis,
                Thread::sleep
        );
    }

    ReservationFulfillmentCommandFacade(
            ReservationService reservationService,
            Clock clock,
            IntToLongFunction retryDelayMillis,
            RetrySleeper retrySleeper
    ) {
        this.reservationService = Objects.requireNonNull(reservationService);
        this.clock = Objects.requireNonNull(clock);
        this.retryDelayMillis = Objects.requireNonNull(retryDelayMillis);
        this.retrySleeper = Objects.requireNonNull(retrySleeper);
    }

    public ReservationFulfillmentCommandResult fulfill(
            long operatorAccountId,
            long storeId,
            long reservationId,
            IdempotencyKey key,
            ReservationFulfillmentRequest request
    ) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Instant requestedAt = clock.instant();
        String normalizedKey = key.value();
        IdempotencyCommand command = new IdempotencyCommand(
                "store-operator",
                operatorAccountId,
                "RESERVATION_FULFILL",
                normalizedKey,
                RequestFingerprint.of(fulfillmentCanonical(storeId, reservationId))
        );
        String correlationId = "reservation-fulfill:store-operator:"
                + operatorAccountId + ":" + normalizedKey;
        return executeWithRetry(() -> reservationService.fulfillStoreReservation(
                operatorAccountId,
                storeId,
                reservationId,
                command,
                requestedAt,
                correlationId
        ));
    }

    private static String fulfillmentCanonical(long storeId, long reservationId) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(canonical, "method", "POST");
        appendCanonical(
                canonical,
                "route",
                "/api/v1/store-operator/stores/{storeId}/reservations/"
                        + "{reservationId}/fulfillments"
        );
        appendCanonical(canonical, "storeId", String.valueOf(storeId));
        appendCanonical(canonical, "reservationId", String.valueOf(reservationId));
        return canonical.toString();
    }

    private static void appendCanonical(StringBuilder target, String field, String value) {
        target.append(field)
                .append('=')
                .append(value.length())
                .append(':')
                .append(value)
                .append('|');
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
        if (failure instanceof ServiceException
                || failure instanceof IllegalArgumentException
                || failure instanceof QueryTimeoutException
                || failure instanceof DataIntegrityViolationException) {
            return false;
        }
        if (failure instanceof ObjectOptimisticLockingFailureException) {
            return true;
        }
        return failure instanceof CannotAcquireLockException
                && containsRetryableMysqlLockFailure(failure);
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
                CommonErrorCode.CONCURRENT_MODIFICATION
        );
        conflict.initCause(cause);
        return conflict;
    }
}
