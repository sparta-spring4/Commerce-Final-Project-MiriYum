package com.miriyum.domain.auth.logindelay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

@ExtendWith(MockitoExtension.class)
class LoginDelayGuardTest {

    @Mock
    private LoginFailureDelayRepository loginFailureDelayRepository;

    @Mock
    private LoginDelayPolicy loginDelayPolicy;

    @Mock
    private LoginDelayTransactionExecutor transactionExecutor;

    @Test
    void treatsExhaustedAcquireRetriesAsBusy() {
        LoginDelayGuard guard = guard();
        given(transactionExecutor.execute(any())).willThrow(new CannotAcquireLockException("deadlock"));

        LoginAttempt attempt = guard.tryAcquireAttempt(TokenNamespace.CONSUMER, 1L);

        assertThat(attempt.status()).isEqualTo(LoginAttempt.Status.BUSY);
    }

    @Test
    void treatsExhaustedCompletionRetriesAsOwnershipLoss() {
        LoginDelayGuard guard = guard();
        given(transactionExecutor.execute(any())).willThrow(new CannotAcquireLockException("deadlock"));

        boolean completed = guard.completeAttempt(
                TokenNamespace.CONSUMER, 1L, LoginAttempt.acquired("attempt-token"), true);

        assertThat(completed).isFalse();
    }

    private LoginDelayGuard guard() {
        return new LoginDelayGuard(
                loginFailureDelayRepository, loginDelayPolicy, Clock.systemUTC(), transactionExecutor);
    }
}
