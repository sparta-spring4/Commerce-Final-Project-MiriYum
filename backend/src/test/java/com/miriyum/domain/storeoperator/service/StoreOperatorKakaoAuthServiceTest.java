package com.miriyum.domain.storeoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import com.miriyum.domain.auth.contact.PhoneNumberPolicy;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.jwt.TokenPair;
import com.miriyum.domain.auth.social.client.KakaoOAuthClient;
import com.miriyum.domain.auth.social.dto.KakaoAuthenticationRequest;
import com.miriyum.domain.auth.social.dto.KakaoIdentityFingerprint;
import com.miriyum.domain.auth.social.dto.KakaoLoginResult;
import com.miriyum.domain.auth.social.dto.KakaoOAuthState;
import com.miriyum.domain.auth.social.dto.KakaoOAuthUser;
import com.miriyum.domain.auth.social.dto.KakaoSignUpTicket;
import com.miriyum.domain.auth.social.enums.KakaoLinkResult;
import com.miriyum.domain.auth.social.enums.KakaoLoginStatus;
import com.miriyum.domain.auth.social.enums.KakaoOAuthPurpose;
import com.miriyum.domain.auth.social.service.KakaoIdentityFingerprintGenerator;
import com.miriyum.domain.auth.social.service.KakaoOAuthStateService;
import com.miriyum.domain.auth.social.service.KakaoSignUpTicketService;
import com.miriyum.domain.auth.social.service.KakaoSocialLoginLinkService;
import com.miriyum.domain.storeoperator.dto.auth.StoreOperatorKakaoSignUpRequest;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.enums.StoreOperatorAccountStatus;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StoreOperatorKakaoAuthServiceTest {

    @Mock
    private StoreOperatorAccountRepository storeOperatorAccountRepository;

    @Mock
    private StoreOperatorKakaoLinkTransactionService storeOperatorKakaoLinkTransactionService;

    @Mock
    private KakaoOAuthClient kakaoOAuthClient;

    @Mock
    private KakaoOAuthStateService kakaoOAuthStateService;

    @Mock
    private KakaoIdentityFingerprintGenerator fingerprintGenerator;

    @Mock
    private KakaoSignUpTicketService kakaoSignUpTicketService;

    @Mock
    private KakaoSocialLoginLinkService kakaoSocialLoginLinkService;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private PhoneNumberPolicy phoneNumberPolicy;

    @InjectMocks
    private StoreOperatorKakaoAuthService storeOperatorKakaoAuthService;

    @Test
    @DisplayName("카카오 가입 필수 정보를 제출하면 비밀번호 없이 매장 운영자 계정과 카카오 연결을 함께 만든다")
    void signsUpStoreOperatorWithKakao() {
        StoreOperatorKakaoSignUpRequest request = new StoreOperatorKakaoSignUpRequest(
                "sign-up-ticket", "operator@example.com", "010-1234-5678", "운영자 이름");
        given(kakaoSignUpTicketService.parse("sign-up-ticket"))
                .willReturn(new KakaoSignUpTicket(TokenNamespace.STORE_OPERATOR, "fingerprint", "v2"));
        given(fingerprintGenerator.isActiveKeyVersion("v2")).willReturn(true);
        given(phoneNumberPolicy.normalize("010-1234-5678")).willReturn("01012345678");
        given(storeOperatorAccountRepository.saveAndFlush(any(StoreOperatorAccount.class)))
                .willAnswer(invocation -> {
                    StoreOperatorAccount account = invocation.getArgument(0);
                    ReflectionTestUtils.setField(account, "id", 20L);
                    return account;
                });
        given(kakaoSocialLoginLinkService.linkFingerprint(
                TokenNamespace.STORE_OPERATOR, 20L, new KakaoIdentityFingerprint("v2", "fingerprint")))
                .willReturn(KakaoLinkResult.CREATED);
        given(jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, 20L)).willReturn("access-token");
        given(jwtTokenProvider.generateRefreshToken(TokenNamespace.STORE_OPERATOR, 20L)).willReturn("refresh-token");

        KakaoLoginResult result = storeOperatorKakaoAuthService.signUp(request);

        assertThat(result.status()).isEqualTo(KakaoLoginStatus.AUTHENTICATED);
        assertThat(result.tokenPair()).isEqualTo(new TokenPair("access-token", "refresh-token"));
        then(storeOperatorAccountRepository).should().saveAndFlush(any(StoreOperatorAccount.class));
    }

    @Test
    @DisplayName("전환 기간에도 이전 fingerprint 키의 카카오 가입 티켓은 거절한다")
    void rejectsSignUpTicketWithPreviousFingerprintKeyVersion() {
        StoreOperatorKakaoSignUpRequest request = new StoreOperatorKakaoSignUpRequest(
                "sign-up-ticket", "operator@example.com", "010-1234-5678", "운영자 이름");
        given(kakaoSignUpTicketService.parse("sign-up-ticket"))
                .willReturn(new KakaoSignUpTicket(TokenNamespace.STORE_OPERATOR, "fingerprint", "v1"));
        given(fingerprintGenerator.isActiveKeyVersion("v1")).willReturn(false);

        assertThatThrownBy(() -> storeOperatorKakaoAuthService.signUp(request))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.KAKAO_OAUTH_INVALID));
        then(storeOperatorAccountRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("처음 카카오로 로그인한 매장 운영자는 계정 생성을 위한 가입 티켓을 받는다")
    void requiresSignUpForUnlinkedKakaoOperator() {
        KakaoAuthenticationRequest request = new KakaoAuthenticationRequest("code", "state", "https://app.example/callback");
        given(kakaoOAuthStateService.parse("state"))
                .willReturn(new KakaoOAuthState(TokenNamespace.STORE_OPERATOR, KakaoOAuthPurpose.LOGIN, null));
        given(kakaoOAuthClient.authenticate("code", "https://app.example/callback"))
                .willReturn(new KakaoOAuthUser("kakao-subject"));
        given(kakaoSocialLoginLinkService.findLinkedAccountId(TokenNamespace.STORE_OPERATOR, "kakao-subject"))
                .willReturn(null);
        given(fingerprintGenerator.generateActive("kakao-subject"))
                .willReturn(new KakaoIdentityFingerprint("v1", "fingerprint"));
        given(kakaoSignUpTicketService.create(TokenNamespace.STORE_OPERATOR, "v1", "fingerprint"))
                .willReturn("sign-up-ticket");

        KakaoLoginResult result = storeOperatorKakaoAuthService.authenticate(request);

        assertThat(result.status()).isEqualTo(KakaoLoginStatus.SIGN_UP_REQUIRED);
        assertThat(result.signUpTicket()).isEqualTo("sign-up-ticket");
    }

    @Test
    @DisplayName("이미 연결된 카카오로 로그인한 매장 운영자는 기존 계정으로 로그인한다")
    void signsInWithLinkedStoreOperatorAccount() {
        KakaoAuthenticationRequest request = new KakaoAuthenticationRequest("code", "state", "https://app.example/callback");
        StoreOperatorAccount account = StoreOperatorAccount.create("operator@example.com", "{sha256-bcrypt}hash", "운영자 이름");
        ReflectionTestUtils.setField(account, "id", 20L);
        given(kakaoOAuthStateService.parse("state"))
                .willReturn(new KakaoOAuthState(TokenNamespace.STORE_OPERATOR, KakaoOAuthPurpose.LOGIN, null));
        given(kakaoOAuthClient.authenticate("code", "https://app.example/callback"))
                .willReturn(new KakaoOAuthUser("kakao-subject"));
        given(kakaoSocialLoginLinkService.findLinkedAccountId(TokenNamespace.STORE_OPERATOR, "kakao-subject"))
                .willReturn(20L);
        given(storeOperatorAccountRepository.findById(20L)).willReturn(Optional.of(account));
        given(jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, 20L)).willReturn("access-token");
        given(jwtTokenProvider.generateRefreshToken(TokenNamespace.STORE_OPERATOR, 20L)).willReturn("refresh-token");

        KakaoLoginResult result = storeOperatorKakaoAuthService.authenticate(request);

        assertThat(account.getStatus()).isEqualTo(StoreOperatorAccountStatus.ACTIVE);
        assertThat(result.status()).isEqualTo(KakaoLoginStatus.AUTHENTICATED);
        assertThat(result.tokenPair()).isEqualTo(new TokenPair("access-token", "refresh-token"));
    }

    @Test
    @DisplayName("로그인한 매장 운영자는 자기 계정에만 카카오 계정을 연결할 수 있다")
    void linksKakaoToAuthenticatedStoreOperator() {
        given(kakaoOAuthStateService.parse("link-state"))
                .willReturn(new KakaoOAuthState(TokenNamespace.STORE_OPERATOR, KakaoOAuthPurpose.LINK, 20L));
        given(kakaoOAuthClient.authenticate("code", "https://app.example/callback"))
                .willReturn(new KakaoOAuthUser("kakao-subject"));
        given(storeOperatorKakaoLinkTransactionService.linkActiveAccount(20L, "kakao-subject"))
                .willReturn(KakaoLinkResult.CREATED);

        KakaoLinkResult result = storeOperatorKakaoAuthService.linkKakao(
                20L, new KakaoAuthenticationRequest("code", "link-state", "https://app.example/callback"));

        assertThat(result).isEqualTo(KakaoLinkResult.CREATED);
        then(storeOperatorAccountRepository).shouldHaveNoInteractions();
        InOrder inOrder = inOrder(kakaoOAuthClient, storeOperatorKakaoLinkTransactionService);
        inOrder.verify(kakaoOAuthClient).authenticate("code", "https://app.example/callback");
        inOrder.verify(storeOperatorKakaoLinkTransactionService).linkActiveAccount(20L, "kakao-subject");
    }
}
