package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamTransitionRequest;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntToLongFunction;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;

/** 소비자 취소의 계정 상태, 멱등 지문, 중앙 시각과 bounded retry를 구성한다. */
@Service
public class WaitingConsumerCommandFacade {

    private static final int MAX_ATTEMPTS = 3;
    private static final String ROUTE =
            "/api/v1/consumers/me/waiting-teams/{waitingTeamId}/cancellations";

    private final ConsumerAccountService accountService;
    private final WaitingCreationService creationService;
    private final WaitingLedgerService ledgerService;
    private final Clock clock;
    private final boolean locationProofConnected;
    private final IntToLongFunction retryDelayMillis;
    private final RetrySleeper retrySleeper;

    @FunctionalInterface
    interface RetrySleeper { void sleep(long millis) throws InterruptedException; }

    @Autowired
    public WaitingConsumerCommandFacade(
            ConsumerAccountService accountService,
            WaitingCreationService creationService,
            WaitingLedgerService ledgerService,
            Clock clock,
            @Value("${miriyum.waiting.consumer-registration.location-proof-connected:false}")
                    boolean locationProofConnected
    ) {
        this(accountService, creationService, ledgerService, clock, locationProofConnected,
                WaitingConsumerCommandFacade::defaultDelayMillis, Thread::sleep);
    }

    WaitingConsumerCommandFacade(
            ConsumerAccountService accountService,
            WaitingCreationService creationService,
            WaitingLedgerService ledgerService,
            Clock clock,
            boolean locationProofConnected,
            IntToLongFunction retryDelayMillis,
            RetrySleeper retrySleeper
    ) {
        this.accountService = Objects.requireNonNull(accountService);
        this.creationService = Objects.requireNonNull(creationService);
        this.ledgerService = Objects.requireNonNull(ledgerService);
        this.clock = Objects.requireNonNull(clock);
        this.locationProofConnected = locationProofConnected;
        this.retryDelayMillis = Objects.requireNonNull(retryDelayMillis);
        this.retrySleeper = Objects.requireNonNull(retrySleeper);
    }

    public WaitingConsumerCommandResult create(
            long storeId,
            long consumerAccountId,
            LocalDate businessDate,
            int partySize,
            IdempotencyKey key
    ) {
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(key, "key must not be null");
        accountService.requireActiveAccount(consumerAccountId);
        return creationService.createForConsumer(
                storeId,
                consumerAccountId,
                businessDate,
                partySize,
                WaitingSource.REMOTE,
                key,
                locationProofConnected);
    }

    public WaitingConsumerCommandResult cancel(
            long consumerAccountId,
            long waitingTeamId,
            IdempotencyKey key,
            WaitingTeamTransitionRequest request
    ) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(request, "request must not be null");
        accountService.requireActiveAccount(consumerAccountId);
        IdempotencyCommand command = new IdempotencyCommand(
                "consumer", consumerAccountId, "WAITING_TEAM_CANCEL", key.value(),
                RequestFingerprint.of(canonical(waitingTeamId, request.expectedVersion())));
        Instant occurredAt = clock.instant();
        return executeWithRetry(() -> ledgerService.cancelByConsumer(
                consumerAccountId, waitingTeamId, request.expectedVersion(), command, occurredAt));
    }

    private <T> T executeWithRetry(Supplier<T> command) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return command.get();
            } catch (RuntimeException failure) {
                if (!isRetryable(failure)) throw failure;
                if (attempt == MAX_ATTEMPTS) throw concurrentModification(failure);
                sleepBeforeRetry(attempt, failure);
            }
        }
        throw new IllegalStateException("unreachable retry state");
    }

    private void sleepBeforeRetry(int attempt, RuntimeException failure) {
        try {
            retrySleeper.sleep(retryDelayMillis.applyAsLong(attempt));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            interrupted.addSuppressed(failure);
            throw concurrentModification(interrupted);
        }
    }

    private static long defaultDelayMillis(int attempt) {
        return switch (attempt) {
            case 1 -> ThreadLocalRandom.current().nextLong(100L, 201L);
            case 2 -> ThreadLocalRandom.current().nextLong(300L, 501L);
            default -> throw new IllegalArgumentException("unsupported retry attempt");
        };
    }

    private static boolean isRetryable(RuntimeException failure) {
        if (failure instanceof ServiceException
                || failure instanceof IllegalArgumentException
                || failure instanceof DataIntegrityViolationException) return false;
        if (failure instanceof QueryTimeoutException
                || failure instanceof TransactionTimedOutException) return true;
        return failure instanceof CannotAcquireLockException
                && containsRetryableMysqlLockFailure(failure);
    }

    private static boolean containsRetryableMysqlLockFailure(Throwable failure) {
        Throwable current = failure;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (current instanceof SQLException sql
                    && (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205)) return true;
            current = current.getCause();
        }
        return false;
    }

    private static ServiceException concurrentModification(Throwable cause) {
        ServiceException failure = new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
        failure.initCause(cause);
        return failure;
    }

    private static String canonical(long waitingTeamId, long expectedVersion) {
        StringBuilder value = new StringBuilder();
        append(value, "method", "POST");
        append(value, "route", ROUTE);
        append(value, "waitingTeamId", Long.toString(waitingTeamId));
        append(value, "expectedVersion", Long.toString(expectedVersion));
        return value.toString();
    }

    private static void append(StringBuilder target, String field, String value) {
        target.append(field).append('=').append(value.length()).append(':')
                .append(value).append('|');
    }
}
