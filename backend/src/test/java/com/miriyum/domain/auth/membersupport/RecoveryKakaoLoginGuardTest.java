package com.miriyum.domain.auth.membersupport;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.contact.PhoneNumberPolicy;
import com.miriyum.domain.auth.contact.ReservationContactReferenceGenerator;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenManager;
import com.miriyum.domain.auth.social.client.KakaoOAuthClient;
import com.miriyum.domain.auth.social.dto.KakaoAuthenticationRequest;
import com.miriyum.domain.auth.social.dto.KakaoOAuthState;
import com.miriyum.domain.auth.social.dto.KakaoOAuthUser;
import com.miriyum.domain.auth.social.enums.KakaoOAuthPurpose;
import com.miriyum.domain.auth.social.service.KakaoIdentityFingerprintGenerator;
import com.miriyum.domain.auth.social.service.KakaoOAuthStateService;
import com.miriyum.domain.auth.social.service.KakaoSignUpTicketService;
import com.miriyum.domain.auth.social.service.KakaoSocialLoginLinkService;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.consumer.service.ConsumerKakaoAuthService;
import com.miriyum.domain.consumer.service.ConsumerKakaoLinkTransactionService;
import com.miriyum.domain.consumer.service.NicknamePolicy;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.domain.storeoperator.service.StoreOperatorKakaoAuthService;
import com.miriyum.domain.storeoperator.service.StoreOperatorKakaoLinkTransactionService;
import com.miriyum.global.exception.ServiceException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RecoveryKakaoLoginGuardTest {
    private static final KakaoAuthenticationRequest REQUEST =
            new KakaoAuthenticationRequest("code", "state", "https://app.example/callback");

    @Test
    void consumerRecoveryBlocksLinkedKakaoLogin() {
        ConsumerAccountRepository accounts = mock(ConsumerAccountRepository.class);
        KakaoOAuthClient client = mock(KakaoOAuthClient.class);
        KakaoOAuthStateService states = mock(KakaoOAuthStateService.class);
        KakaoSocialLoginLinkService links = mock(KakaoSocialLoginLinkService.class);
        ConsumerAccount account = ConsumerAccount.create("old@example.com", "hash", "consumer");
        ReflectionTestUtils.setField(account, "id", 41L);
        account.approveRecovery("new@example.com");
        stubs(states, client, links, TokenNamespace.CONSUMER, 41L);
        when(accounts.findById(41L)).thenReturn(Optional.of(account));
        ConsumerKakaoAuthService service = new ConsumerKakaoAuthService(
                accounts, mock(ConsumerKakaoLinkTransactionService.class), client, states, links,
                mock(KakaoIdentityFingerprintGenerator.class), mock(KakaoSignUpTicketService.class),
                mock(RefreshTokenManager.class), new NicknamePolicy(), new PhoneNumberPolicy(),
                new ReservationContactReferenceGenerator());

        assertRestricted(() -> service.authenticate(REQUEST));
    }

    @Test
    void storeOperatorRecoveryBlocksLinkedKakaoLogin() {
        StoreOperatorAccountRepository accounts = mock(StoreOperatorAccountRepository.class);
        KakaoOAuthClient client = mock(KakaoOAuthClient.class);
        KakaoOAuthStateService states = mock(KakaoOAuthStateService.class);
        KakaoSocialLoginLinkService links = mock(KakaoSocialLoginLinkService.class);
        StoreOperatorAccount account = StoreOperatorAccount.create("old@example.com", "hash", "owner");
        ReflectionTestUtils.setField(account, "id", 71L);
        account.approveRecovery("new@example.com");
        stubs(states, client, links, TokenNamespace.STORE_OPERATOR, 71L);
        when(accounts.findById(71L)).thenReturn(Optional.of(account));
        StoreOperatorKakaoAuthService service = new StoreOperatorKakaoAuthService(
                accounts, mock(StoreOperatorKakaoLinkTransactionService.class), client, states,
                mock(KakaoIdentityFingerprintGenerator.class), mock(KakaoSignUpTicketService.class), links,
                mock(RefreshTokenManager.class), new PhoneNumberPolicy());

        assertRestricted(() -> service.authenticate(REQUEST));
    }

    private void stubs(KakaoOAuthStateService states, KakaoOAuthClient client,
                       KakaoSocialLoginLinkService links, TokenNamespace namespace, long accountId) {
        when(states.parse("state")).thenReturn(new KakaoOAuthState(namespace, KakaoOAuthPurpose.LOGIN, null));
        when(client.authenticate("code", "https://app.example/callback"))
                .thenReturn(new KakaoOAuthUser("subject"));
        when(links.findLinkedAccountId(namespace, "subject")).thenReturn(accountId);
    }

    private void assertRestricted(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(ServiceException.class,
                exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                        .isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED));
    }
}
