package com.miriyum.domain.reservation.service;

import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.RequestFingerprint;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntToLongFunction;
import java.util.function.Supplier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** Builds route-scoped deposit commands outside the process transaction. */
@Service
public class ReservationDepositProcessCommandFacade {

    private static final int MAX_ATTEMPTS = 3;

    private static final String FINALIZATION_ROUTE =
            "/api/v1/consumers/me/reservation-requests/"
                    + "{reservationRequestId}/finalizations";
    private static final String ABANDONMENT_ROUTE =
            "/api/v1/consumers/me/reservation-requests/"
                    + "{reservationRequestId}/abandonments";

    private final ReservationDepositProcessService processService;
    private final IntToLongFunction retryDelayMillis;
    private final RetrySleeper retrySleeper;

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public ReservationDepositProcessCommandFacade(
            ReservationDepositProcessService processService
    ) {
        this(
                processService,
                ReservationDepositProcessCommandFacade::defaultDelayMillis,
                Thread::sleep);
    }

    ReservationDepositProcessCommandFacade(
            ReservationDepositProcessService processService,
            IntToLongFunction retryDelayMillis,
            RetrySleeper retrySleeper
    ) {
        this.processService = processService;
        this.retryDelayMillis = retryDelayMillis;
        this.retrySleeper = retrySleeper;
    }

    public ReservationDepositCommandResult finalizeRequest(
            long consumerAccountId,
            long processId,
            IdempotencyKey key
    ) {
        return executeWithRetry(() -> processService.finalizeOwnedIdempotent(
                processId,
                consumerAccountId,
                command(
                        consumerAccountId,
                        processId,
                        key,
                        "RESERVATION_DEPOSIT_FINALIZE",
                        FINALIZATION_ROUTE)));
    }

    public ReservationDepositCommandResult abandonRequest(
            long consumerAccountId,
            long processId,
            IdempotencyKey key
    ) {
        return executeWithRetry(() -> processService.abandonOwnedIdempotent(
                processId,
                consumerAccountId,
                command(
                        consumerAccountId,
                        processId,
                        key,
                        "RESERVATION_DEPOSIT_ABANDON",
                        ABANDONMENT_ROUTE)));
    }

    private <T> T executeWithRetry(Supplier<T> work) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return work.get();
            } catch (RuntimeException exception) {
                if (!isRetryable(exception)) {
                    throw exception;
                }
                if (attempt == MAX_ATTEMPTS) {
                    ServiceException conflict = new ServiceException(
                            CommonErrorCode.CONCURRENT_MODIFICATION);
                    conflict.initCause(exception);
                    throw conflict;
                }
                try {
                    retrySleeper.sleep(retryDelayMillis.applyAsLong(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    ServiceException conflict = new ServiceException(
                            CommonErrorCode.CONCURRENT_MODIFICATION);
                    conflict.initCause(interrupted);
                    throw conflict;
                }
            }
        }
        throw new IllegalStateException("unreachable retry state");
    }

    private static boolean isRetryable(RuntimeException failure) {
        if (failure instanceof ServiceException
                || failure instanceof IllegalArgumentException
                || failure instanceof QueryTimeoutException
                || failure instanceof DataIntegrityViolationException) {
            return false;
        }
        if (failure instanceof ObjectOptimisticLockingFailureException) {
            return true;
        }
        if (!(failure instanceof CannotAcquireLockException)) {
            return false;
        }
        Throwable current = failure;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (current instanceof SQLException sql
                    && (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static long defaultDelayMillis(int failedAttempt) {
        return switch (failedAttempt) {
            case 1 -> ThreadLocalRandom.current().nextLong(100L, 201L);
            case 2 -> ThreadLocalRandom.current().nextLong(300L, 501L);
            default -> throw new IllegalArgumentException("unsupported retry attempt");
        };
    }

    private static IdempotencyCommand command(
            long consumerAccountId,
            long processId,
            IdempotencyKey key,
            String commandType,
            String route
    ) {
        if (consumerAccountId <= 0 || processId <= 0 || key == null) {
            throw new IllegalArgumentException("deposit command arguments are required");
        }
        String canonical = field("method", "POST")
                + field("route", route)
                + field("reservationRequestId", String.valueOf(processId));
        return new IdempotencyCommand(
                "consumer",
                consumerAccountId,
                commandType,
                key.value(),
                RequestFingerprint.of(canonical));
    }

    private static String field(String name, String value) {
        return name + "=" + value.length() + ":" + value + "|";
    }
}
