package com.miriyum.domain.auth.logindelay;

import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs short login-delay state changes in independent transactions with bounded deadlock retries.
 */
@Component
public class LoginDelayTransactionExecutor {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration INITIAL_RETRY_BACKOFF = Duration.ofMillis(10);

    private final TransactionOperations transactionOperations;
    private final Duration initialRetryBackoff;

    @Autowired
    public LoginDelayTransactionExecutor(PlatformTransactionManager transactionManager) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionOperations = transactionTemplate;
        this.initialRetryBackoff = INITIAL_RETRY_BACKOFF;
    }

    LoginDelayTransactionExecutor(TransactionOperations transactionOperations, Duration initialRetryBackoff) {
        this.transactionOperations = transactionOperations;
        this.initialRetryBackoff = initialRetryBackoff;
    }

    public <T> T execute(Supplier<T> operation) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transactionOperations.execute(status -> operation.get());
            } catch (PessimisticLockingFailureException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                backoff(attempt, exception);
            }
        }
        throw new IllegalStateException("Login-delay transaction retry loop terminated unexpectedly");
    }

    private void backoff(int attempt, PessimisticLockingFailureException cause) {
        try {
            Duration delay = initialRetryBackoff.multipliedBy(attempt);
            Thread.sleep(delay.toMillis(), delay.toNanosPart() % 1_000_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PessimisticLockingFailureException(
                    "Interrupted while retrying login-delay transaction", cause);
        }
    }
}
