package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.waiting.dto.WaitingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamTransitionRequest;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;

/** 운영자 웨이팅 전이의 명령 유형, canonical fingerprint와 중앙 시각을 구성한다. */
@Service
public class WaitingCommandFacade {

    private static final int MAX_ATTEMPTS = 3;
    private static final String PRINCIPAL_NAMESPACE = "store-operator";
    private static final String ROUTE_PREFIX =
            "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/";

    private final WaitingLedgerService ledgerService;
    private final Clock clock;
    private final IntToLongFunction retryDelayMillis;
    private final RetrySleeper retrySleeper;

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Autowired
    public WaitingCommandFacade(WaitingLedgerService ledgerService, Clock clock) {
        this(ledgerService, clock, WaitingCommandFacade::defaultDelayMillis, Thread::sleep);
    }

    WaitingCommandFacade(
            WaitingLedgerService ledgerService,
            Clock clock,
            IntToLongFunction retryDelayMillis,
            RetrySleeper retrySleeper
    ) {
        this.ledgerService = Objects.requireNonNull(ledgerService);
        this.clock = Objects.requireNonNull(clock);
        this.retryDelayMillis = Objects.requireNonNull(retryDelayMillis);
        this.retrySleeper = Objects.requireNonNull(retrySleeper);
    }

    public WaitingCommandResult call(
            long operatorAccountId,
            long storeId,
            long waitingTeamId,
            IdempotencyKey key,
            WaitingTeamTransitionRequest request
    ) {
        return execute(
                operatorAccountId, storeId, waitingTeamId, key, request,
                "WAITING_TEAM_CALL", "calls", ledgerService::call
        );
    }

    public WaitingCommandResult arrive(
            long operatorAccountId,
            long storeId,
            long waitingTeamId,
            IdempotencyKey key,
            WaitingTeamTransitionRequest request
    ) {
        return execute(
                operatorAccountId, storeId, waitingTeamId, key, request,
                "WAITING_TEAM_ARRIVE", "arrivals", ledgerService::arrive
        );
    }

    public WaitingCommandResult checkIn(
            long operatorAccountId,
            long storeId,
            long waitingTeamId,
            IdempotencyKey key,
            WaitingTeamTransitionRequest request
    ) {
        return execute(
                operatorAccountId, storeId, waitingTeamId, key, request,
                "WAITING_TEAM_CHECK_IN", "check-ins", ledgerService::checkIn
        );
    }

    public WaitingCommandResult cancel(
            long operatorAccountId,
            long storeId,
            long waitingTeamId,
            IdempotencyKey key,
            WaitingTeamTransitionRequest request
    ) {
        return execute(
                operatorAccountId, storeId, waitingTeamId, key, request,
                "WAITING_TEAM_CANCEL", "cancellations", ledgerService::cancel
        );
    }

    private WaitingCommandResult execute(
            long operatorAccountId,
            long storeId,
            long waitingTeamId,
            IdempotencyKey key,
            WaitingTeamTransitionRequest request,
            String commandType,
            String routeAction,
            LedgerCommand command
    ) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(request, "request must not be null");
        IdempotencyCommand idempotencyCommand = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorAccountId,
                commandType,
                key.value(),
                RequestFingerprint.of(canonical(
                        storeId, waitingTeamId, request.expectedVersion(), routeAction))
        );
        Instant occurredAt = clock.instant();
        return executeWithRetry(() -> command.execute(
                operatorAccountId,
                storeId,
                waitingTeamId,
                request.expectedVersion(),
                idempotencyCommand,
                occurredAt
        ));
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
        if (failure instanceof ServiceException
                || failure instanceof IllegalArgumentException
                || failure instanceof DataIntegrityViolationException) {
            return false;
        }
        if (failure instanceof QueryTimeoutException
                || failure instanceof TransactionTimedOutException) {
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
                CommonErrorCode.CONCURRENT_MODIFICATION);
        conflict.initCause(cause);
        return conflict;
    }

    private static String canonical(
            long storeId,
            long waitingTeamId,
            long expectedVersion,
            String routeAction
    ) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "method", "POST");
        append(canonical, "route", ROUTE_PREFIX + routeAction);
        append(canonical, "storeId", Long.toString(storeId));
        append(canonical, "waitingTeamId", Long.toString(waitingTeamId));
        append(canonical, "expectedVersion", Long.toString(expectedVersion));
        return canonical.toString();
    }

    private static void append(StringBuilder target, String field, String value) {
        target.append(field)
                .append('=')
                .append(value.length())
                .append(':')
                .append(value)
                .append('|');
    }

    @FunctionalInterface
    private interface LedgerCommand {
        WaitingCommandResult execute(
                long operatorAccountId,
                long storeId,
                long waitingTeamId,
                long expectedVersion,
                IdempotencyCommand command,
                Instant occurredAt
        );
    }
}
