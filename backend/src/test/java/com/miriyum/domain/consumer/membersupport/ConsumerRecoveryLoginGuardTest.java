package com.miriyum.domain.consumer.membersupport;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.contact.PhoneNumberPolicy;
import com.miriyum.domain.auth.contact.ReservationContactReferenceGenerator;
import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.logindelay.LoginAttempt;
import com.miriyum.domain.auth.logindelay.LoginDelayGuard;
import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenManager;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.consumer.service.ConsumerAuthService;
import com.miriyum.domain.consumer.service.NicknamePolicy;
import com.miriyum.global.exception.ServiceException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class ConsumerRecoveryLoginGuardTest {

    @Test
    void approvedRecoveryBlocksPasswordLoginUntilPasswordIsReplaced() {
        ConsumerAccountRepository accounts = mock(ConsumerAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        LoginDelayGuard delay = mock(LoginDelayGuard.class);
        RefreshTokenManager refreshTokens = mock(RefreshTokenManager.class);
        ConsumerAuthService service = new ConsumerAuthService(
                accounts, encoder, mock(JwtTokenProvider.class), new NicknamePolicy(), new PasswordPolicy(),
                delay, new PhoneNumberPolicy(), new ReservationContactReferenceGenerator(), refreshTokens);
        ConsumerAccount account = ConsumerAccount.create("old@example.com", "old-hash", "consumer");
        ReflectionTestUtils.setField(account, "id", 41L);
        account.approveRecovery("new@example.com");

        when(accounts.findByEmail("new@example.com")).thenReturn(Optional.of(account));
        when(accounts.findById(41L)).thenReturn(Optional.of(account));
        when(delay.tryAcquireAttempt(TokenNamespace.CONSUMER, 41L))
                .thenReturn(LoginAttempt.acquired("attempt"));
        when(delay.completeAttempt(any(), anyLong(), any(), anyBoolean())).thenReturn(true);
        when(encoder.matches("OldPassword1!", "old-hash")).thenReturn(true);
        when(refreshTokens.captureSessionEpoch(TokenNamespace.CONSUMER, 41L)).thenReturn(1L);

        assertThatThrownBy(() -> service.login(new LoginRequest("new@example.com", "OldPassword1!")))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED));
    }
}
