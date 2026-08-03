package com.miriyum.domain.auth.logindelay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
class LoginDelayTransactionExecutorTest {

    @Mock
    private TransactionOperations transactionOperations;

    @Test
    void retriesLockAcquisitionFailureInANewTransaction() {
        LoginDelayTransactionExecutor executor = new LoginDelayTransactionExecutor(
                transactionOperations, Duration.ZERO);
        CannotAcquireLockException lockFailure = new CannotAcquireLockException("deadlock");
        given(transactionOperations.execute(any()))
                .willThrow(lockFailure)
                .willAnswer(invocation -> invokeCallback(invocation.getArgument(0)));

        String result = executor.execute(() -> "completed");

        assertThat(result).isEqualTo("completed");
        verify(transactionOperations, times(2)).execute(any());
    }

    @Test
    void propagatesLockAcquisitionFailureAfterRetryLimit() {
        LoginDelayTransactionExecutor executor = new LoginDelayTransactionExecutor(
                transactionOperations, Duration.ZERO);
        CannotAcquireLockException lockFailure = new CannotAcquireLockException("deadlock");
        given(transactionOperations.execute(any())).willThrow(lockFailure);

        assertThatThrownBy(() -> executor.execute(() -> "completed"))
                .isSameAs(lockFailure);

        verify(transactionOperations, times(3)).execute(any());
    }

    @Test
    void preservesInterruptStatusWhenRetryBackoffIsInterrupted() {
        LoginDelayTransactionExecutor executor = new LoginDelayTransactionExecutor(
                transactionOperations, Duration.ofMillis(10));
        CannotAcquireLockException lockFailure = new CannotAcquireLockException("deadlock");
        given(transactionOperations.execute(any())).willThrow(lockFailure);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> executor.execute(() -> "completed"))
                    .isInstanceOf(PessimisticLockingFailureException.class)
                    .hasCause(lockFailure);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static <T> T invokeCallback(TransactionCallback<T> callback) {
        return callback.doInTransaction(mock(TransactionStatus.class));
    }
}
