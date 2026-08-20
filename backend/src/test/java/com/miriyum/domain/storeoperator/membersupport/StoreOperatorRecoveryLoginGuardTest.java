package com.miriyum.domain.storeoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.contact.PhoneNumberPolicy;
import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.logindelay.LoginAttempt;
import com.miriyum.domain.auth.logindelay.LoginDelayGuard;
import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenManager;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.domain.storeoperator.service.StoreOperatorAuthService;
import com.miriyum.global.exception.ServiceException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class StoreOperatorRecoveryLoginGuardTest {

    @Test
    void approvedRecoveryBlocksPasswordLoginUntilPasswordIsReplaced() {
        StoreOperatorAccountRepository accounts = mock(StoreOperatorAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        LoginDelayGuard delay = mock(LoginDelayGuard.class);
        RefreshTokenManager refreshTokens = mock(RefreshTokenManager.class);
        StoreOperatorAuthService service = new StoreOperatorAuthService(
                accounts, encoder, mock(JwtTokenProvider.class), new PasswordPolicy(),
                delay, new PhoneNumberPolicy(), refreshTokens);
        StoreOperatorAccount account = StoreOperatorAccount.create(
                "old-owner@example.com", "old-hash", "owner");
        ReflectionTestUtils.setField(account, "id", 71L);
        account.approveRecovery("new-owner@example.com");

        when(accounts.findByEmail("new-owner@example.com")).thenReturn(Optional.of(account));
        when(accounts.findById(71L)).thenReturn(Optional.of(account));
        when(delay.tryAcquireAttempt(TokenNamespace.STORE_OPERATOR, 71L))
                .thenReturn(LoginAttempt.acquired("attempt"));
        when(delay.completeAttempt(any(), anyLong(), any(), anyBoolean())).thenReturn(true);
        when(encoder.matches("OldPassword1!", "old-hash")).thenReturn(true);
        when(refreshTokens.captureSessionEpoch(TokenNamespace.STORE_OPERATOR, 71L)).thenReturn(1L);

        assertThatThrownBy(() -> service.login(new LoginRequest("new-owner@example.com", "OldPassword1!")))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED));
    }
}
