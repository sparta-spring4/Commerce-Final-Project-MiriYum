package com.miriyum.domain.pickup.service;

import com.miriyum.domain.pickup.dto.request.PickupCancellationRequest;
import com.miriyum.domain.pickup.dto.request.PickupReservationCreateRequest;
import com.miriyum.domain.pickup.dto.request.StorePickupCancellationRequest;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
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

/** Pickup 쓰기 명령을 새 트랜잭션 시도로 제한 재실행하는 비트랜잭션 경계다. */
@Service
public class PickupCommandFacade {

    private static final int MAX_ATTEMPTS = 3;

    private final PickupReservationService reservationService;
    private final PickupStoreManagementService managementService;
    private final IntToLongFunction retryDelayMillis;
    private final RetrySleeper retrySleeper;

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Autowired
    public PickupCommandFacade(
            PickupReservationService reservationService,
            PickupStoreManagementService managementService
    ) {
        this(reservationService, managementService,
                PickupCommandFacade::defaultDelayMillis, Thread::sleep);
    }

    PickupCommandFacade(
            PickupReservationService reservationService,
            PickupStoreManagementService managementService,
            IntToLongFunction retryDelayMillis,
            RetrySleeper retrySleeper
    ) {
        this.reservationService = reservationService;
        this.managementService = managementService;
        this.retryDelayMillis = retryDelayMillis;
        this.retrySleeper = retrySleeper;
    }

    public PickupCommandResult create(
            long consumerAccountId,
            IdempotencyKey key,
            PickupReservationCreateRequest request
    ) {
        return executeWithRetry(() -> reservationService.create(
                consumerAccountId, key, request));
    }

    public PickupCommandResult cancelByConsumer(
            long consumerAccountId,
            long pickupReservationId,
            IdempotencyKey key,
            PickupCancellationRequest request
    ) {
        return executeWithRetry(() -> reservationService.cancelByConsumer(
                consumerAccountId, pickupReservationId, key, request));
    }

    public PickupCommandResult cancelByOperator(
            long operatorAccountId,
            long storeId,
            long pickupReservationId,
            IdempotencyKey key,
            StorePickupCancellationRequest request
    ) {
        return executeWithRetry(() -> managementService.cancel(
                operatorAccountId, storeId, pickupReservationId, key, request));
    }

    public PickupCommandResult fulfill(
            long operatorAccountId,
            long storeId,
            long pickupReservationId,
            IdempotencyKey key
    ) {
        return executeWithRetry(() -> managementService.fulfill(
                operatorAccountId, storeId, pickupReservationId, key));
    }

    private <T> T executeWithRetry(Supplier<T> command) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return command.get();
            } catch (RuntimeException failure) {
                if (!isRetryableTechnicalConflict(failure)) {
                    throw failure;
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw concurrentModification(failure);
                }
                sleepBeforeRetry(attempt, failure);
            }
        }
        throw new IllegalStateException("unreachable retry state");
    }

    private void sleepBeforeRetry(int failedAttempt, RuntimeException failure) {
        try {
            retrySleeper.sleep(retryDelayMillis.applyAsLong(failedAttempt));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            interrupted.addSuppressed(failure);
            throw concurrentModification(interrupted);
        }
    }

    static long defaultDelayMillis(int failedAttempt) {
        return switch (failedAttempt) {
            case 1 -> ThreadLocalRandom.current().nextLong(100L, 201L);
            case 2 -> ThreadLocalRandom.current().nextLong(300L, 501L);
            default -> throw new IllegalArgumentException("unsupported retry attempt");
        };
    }

    private static boolean isRetryableTechnicalConflict(RuntimeException failure) {
        if (failure instanceof ObjectOptimisticLockingFailureException) {
            return true;
        }
        if (isExplicitlyNonRetryable(failure)
                || !(failure instanceof CannotAcquireLockException)) {
            return false;
        }
        return containsRetryableMysqlLockFailure(failure);
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
}
