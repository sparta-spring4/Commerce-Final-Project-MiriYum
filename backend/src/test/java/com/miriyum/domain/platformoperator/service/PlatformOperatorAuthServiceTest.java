package com.miriyum.domain.platformoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.logindelay.LoginAttempt;
import com.miriyum.domain.auth.logindelay.LoginDelayGuard;
import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.platformoperator.config.PlatformOperatorAuthProperties;
import com.miriyum.domain.platformoperator.dto.auth.PlatformOperatorTokenResult;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventType;
import com.miriyum.domain.platformoperator.session.PlatformOperatorSessionManager;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class PlatformOperatorAuthServiceTest {
    private final Instant now = Instant.parse("2026-08-13T00:00:00Z");
    private final PlatformOperatorAccountRepository accounts = mock(PlatformOperatorAccountRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final LoginDelayGuard delay = mock(LoginDelayGuard.class);
    private final PlatformOperatorSessionManager sessions = mock(PlatformOperatorSessionManager.class);
    private final PlatformOperatorPasswordChangeTransaction change = mock(PlatformOperatorPasswordChangeTransaction.class);
    private final PlatformOperatorAuthEventRecorder events = mock(PlatformOperatorAuthEventRecorder.class);
    private final PlatformOperatorAuthProperties properties = properties();
    private final PlatformOperatorAuthService service = new PlatformOperatorAuthService(
            accounts, encoder, new PasswordPolicy(), delay, sessions, change, events, properties,
            Clock.fixed(now, ZoneOffset.UTC));
    private PlatformOperatorAccount account;

    @BeforeEach
    void setUp() {
        account = PlatformOperatorAccount.createTemporary(
                "operator@example.com", "hash", "operator", now.plusSeconds(600));
        ReflectionTestUtils.setField(account, "id", 7L);
        when(accounts.findByEmail("operator@example.com")).thenReturn(Optional.of(account));
        when(accounts.findById(7L)).thenReturn(Optional.of(account));
        when(delay.tryAcquireAttempt(any(), any(Long.class))).thenReturn(LoginAttempt.acquired("attempt"));
        when(delay.completeAttempt(any(), any(Long.class), any(), any(Boolean.class))).thenReturn(true);
        when(encoder.matches("Password1!", "hash")).thenReturn(true);
        when(sessions.issue(7L, 1L, 1L, true)).thenReturn(new PlatformOperatorTokenResult(
                "access", "refresh", "Bearer", 900L, true, now.plusSeconds(1800), now.plusSeconds(28800)));
    }

    @Test
    void loginIssuesOnlyAnInitialPasswordLimitedSession() {
        PlatformOperatorTokenResult result = service.login(new LoginRequest("operator@example.com", "Password1!"));
        assertThat(result.passwordChangeRequired()).isTrue();
        verify(sessions).issue(7L, 1L, 1L, true);
        verify(events).record(account, PlatformOperatorAuthEventType.LOGIN,
                PlatformOperatorAuthEventOutcome.SUCCESS);
    }


    @Test
    void failedTemporaryCredentialUsesAtomicCounterAndSanitizedEvent() {
        when(encoder.matches("Wrong1!", "hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("operator@example.com", "Wrong1!")))
                .isInstanceOf(ServiceException.class);

        verify(accounts).incrementTemporaryPasswordFailure(7L);
        verify(accounts, never()).save(account);
        verify(events).record(account, PlatformOperatorAuthEventType.LOGIN,
                PlatformOperatorAuthEventOutcome.FAILURE);
    }

    @Test
    void suspendedAccountRevokesCentralSessionAfterValidCredential() {
        account.suspend();
        assertThatThrownBy(() -> service.login(new LoginRequest("operator@example.com", "Password1!")))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED);
        verify(sessions).revokeAll(7L);
    }

    private static PlatformOperatorAuthProperties properties() {
        PlatformOperatorAuthProperties properties = new PlatformOperatorAuthProperties();
        properties.setEnabled(true);
        properties.getTemporaryPassword().setValidity(Duration.ofMinutes(10));
        properties.getTemporaryPassword().setMaxFailures(3);
        return properties;
    }
}
