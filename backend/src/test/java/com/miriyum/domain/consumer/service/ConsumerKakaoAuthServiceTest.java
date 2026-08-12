package com.miriyum.domain.consumer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
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
import com.miriyum.domain.auth.contact.PhoneNumberPolicy;
import com.miriyum.domain.auth.contact.ReservationContactReferenceGenerator;
import com.miriyum.domain.consumer.dto.auth.ConsumerKakaoSignUpRequest;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
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
class ConsumerKakaoAuthServiceTest {

    @Mock
    private ConsumerAccountRepository consumerAccountRepository;

    @Mock
    private ConsumerKakaoLinkTransactionService consumerKakaoLinkTransactionService;

    @Mock
    private KakaoOAuthClient kakaoOAuthClient;

    @Mock
    private KakaoOAuthStateService kakaoOAuthStateService;

    @Mock
    private KakaoSocialLoginLinkService kakaoSocialLoginLinkService;

    @Mock
    private KakaoIdentityFingerprintGenerator fingerprintGenerator;

    @Mock
    private KakaoSignUpTicketService kakaoSignUpTicketService;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private NicknamePolicy nicknamePolicy;

    @Mock
    private PhoneNumberPolicy phoneNumberPolicy;

    @Mock
    private ReservationContactReferenceGenerator contactReferenceGenerator;

    @InjectMocks
    private ConsumerKakaoAuthService consumerKakaoAuthService;

    @Test
    @DisplayName("처음 카카오 로그인한 일반 사용자는 계정을 만들기 위한 짧은 가입 티켓을 받는다")
    void requiresSignUpForUnlinkedKakaoUser() {
        KakaoAuthenticationRequest request = new KakaoAuthenticationRequest("code", "state", "https://app.example/callback");
        given(kakaoOAuthStateService.parse("state"))
                .willReturn(new KakaoOAuthState(TokenNamespace.CONSUMER, KakaoOAuthPurpose.LOGIN, null));
        given(kakaoOAuthClient.authenticate("code", "https://app.example/callback"))
                .willReturn(new KakaoOAuthUser("kakao-subject"));
        given(kakaoSocialLoginLinkService.findLinkedAccountId(TokenNamespace.CONSUMER, "kakao-subject"))
                .willReturn(null);
        given(fingerprintGenerator.generateActive("kakao-subject"))
                .willReturn(new KakaoIdentityFingerprint("v1", "fingerprint"));
        given(kakaoSignUpTicketService.create(TokenNamespace.CONSUMER, "v1", "fingerprint"))
                .willReturn("sign-up-ticket");

        KakaoLoginResult result = consumerKakaoAuthService.authenticate(request);

        assertThat(result.status()).isEqualTo(KakaoLoginStatus.SIGN_UP_REQUIRED);
        assertThat(result.signUpTicket()).isEqualTo("sign-up-ticket");
        assertThat(result.tokenPair()).isNull();
    }

    @Test
    @DisplayName("이미 연결된 카카오 일반 사용자는 새 계정을 만들지 않고 기존 계정으로 로그인한다")
    void signsInWithLinkedConsumerAccount() {
        KakaoAuthenticationRequest request = new KakaoAuthenticationRequest("code", "state", "https://app.example/callback");
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "{sha256-bcrypt}hash", "닉네임");
        ReflectionTestUtils.setField(account, "id", 10L);
        given(kakaoOAuthStateService.parse("state"))
                .willReturn(new KakaoOAuthState(TokenNamespace.CONSUMER, KakaoOAuthPurpose.LOGIN, null));
        given(kakaoOAuthClient.authenticate("code", "https://app.example/callback"))
                .willReturn(new KakaoOAuthUser("kakao-subject"));
        given(kakaoSocialLoginLinkService.findLinkedAccountId(TokenNamespace.CONSUMER, "kakao-subject"))
                .willReturn(10L);
        given(consumerAccountRepository.findById(10L)).willReturn(Optional.of(account));
        given(jwtTokenProvider.generateAccessToken(TokenNamespace.CONSUMER, 10L)).willReturn("access-token");
        given(jwtTokenProvider.generateRefreshToken(TokenNamespace.CONSUMER, 10L)).willReturn("refresh-token");

        KakaoLoginResult result = consumerKakaoAuthService.authenticate(request);

        assertThat(result.status()).isEqualTo(KakaoLoginStatus.AUTHENTICATED);
        assertThat(result.tokenPair()).isEqualTo(new TokenPair("access-token", "refresh-token"));
        assertThat(result.signUpTicket()).isNull();
    }

    @Test
    @DisplayName("카카오 가입 필수 정보를 제출하면 비밀번호 없이 일반 사용자 계정과 카카오 연결을 함께 만든다")
    void signsUpConsumerWithKakao() {
        ConsumerKakaoSignUpRequest request = new ConsumerKakaoSignUpRequest(
                "sign-up-ticket", "user@example.com", "010-1234-5678", true, "닉네임");
        given(kakaoSignUpTicketService.parse("sign-up-ticket"))
                .willReturn(new KakaoSignUpTicket(TokenNamespace.CONSUMER, "fingerprint", "v1"));
        given(nicknamePolicy.normalize("닉네임")).willReturn("닉네임");
        given(phoneNumberPolicy.normalize("010-1234-5678")).willReturn("01012345678");
        given(contactReferenceGenerator.generate()).willReturn("contact-reference");
        given(consumerAccountRepository.saveAndFlush(any(ConsumerAccount.class)))
                .willAnswer(invocation -> {
                    ConsumerAccount account = invocation.getArgument(0);
                    ReflectionTestUtils.setField(account, "id", 10L);
                    return account;
                });
        given(kakaoSocialLoginLinkService.linkFingerprint(
                TokenNamespace.CONSUMER, 10L, new KakaoIdentityFingerprint("v1", "fingerprint")))
                .willReturn(KakaoLinkResult.CREATED);
        given(jwtTokenProvider.generateAccessToken(TokenNamespace.CONSUMER, 10L)).willReturn("access-token");
        given(jwtTokenProvider.generateRefreshToken(TokenNamespace.CONSUMER, 10L)).willReturn("refresh-token");

        KakaoLoginResult result = consumerKakaoAuthService.signUp(request);

        assertThat(result.status()).isEqualTo(KakaoLoginStatus.AUTHENTICATED);
        assertThat(result.tokenPair()).isEqualTo(new TokenPair("access-token", "refresh-token"));
        then(consumerAccountRepository).should().saveAndFlush(any(ConsumerAccount.class));
    }

    @Test
    @DisplayName("로그인한 일반 사용자는 본인 계정에 연결할 카카오 인가 주소를 발급받는다")
    void startsKakaoLinkForAuthenticatedConsumer() {
        given(kakaoOAuthStateService.createLinkState(TokenNamespace.CONSUMER, 10L)).willReturn("link-state");
        given(kakaoOAuthClient.createAuthorizationUrl("link-state", "https://app.example/callback"))
                .willReturn("https://kauth.kakao.com/oauth/authorize?state=link-state");

        String authorizationUrl = consumerKakaoAuthService.createLinkAuthorizationUrl(
                10L, "https://app.example/callback");

        assertThat(authorizationUrl).contains("state=link-state");
    }

    @Test
    @DisplayName("로그인한 일반 사용자는 자기 계정에만 카카오 계정을 연결할 수 있다")
    void linksKakaoToAuthenticatedConsumer() {
        given(kakaoOAuthStateService.parse("link-state"))
                .willReturn(new KakaoOAuthState(TokenNamespace.CONSUMER, KakaoOAuthPurpose.LINK, 10L));
        given(kakaoOAuthClient.authenticate("code", "https://app.example/callback"))
                .willReturn(new KakaoOAuthUser("kakao-subject"));
        given(consumerKakaoLinkTransactionService.linkActiveAccount(10L, "kakao-subject"))
                .willReturn(KakaoLinkResult.CREATED);

        KakaoLinkResult result = consumerKakaoAuthService.linkKakao(
                10L, new KakaoAuthenticationRequest("code", "link-state", "https://app.example/callback"));

        assertThat(result).isEqualTo(KakaoLinkResult.CREATED);
        then(consumerAccountRepository).shouldHaveNoInteractions();
        InOrder inOrder = inOrder(kakaoOAuthClient, consumerKakaoLinkTransactionService);
        inOrder.verify(kakaoOAuthClient).authenticate("code", "https://app.example/callback");
        inOrder.verify(consumerKakaoLinkTransactionService).linkActiveAccount(10L, "kakao-subject");
    }
}
