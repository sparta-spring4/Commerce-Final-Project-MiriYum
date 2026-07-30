package com.miriyum.domain.storeoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.dto.response.AccountCreatedResponse;
import com.miriyum.domain.auth.dto.response.AccountType;
import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.jwt.TokenPair;
import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.storeoperator.dto.request.StoreOperatorSignUpRequest;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class StoreOperatorAuthServiceTest {

    @Mock
    private StoreOperatorAccountRepository storeOperatorAccountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private final PasswordPolicy passwordPolicy = new PasswordPolicy();

    private StoreOperatorAuthService storeOperatorAuthService;

    @BeforeEach
    void setUp() {
        storeOperatorAuthService = new StoreOperatorAuthService(
                storeOperatorAccountRepository, passwordEncoder, jwtTokenProvider, passwordPolicy, true);
    }

    @Test
    @DisplayName("이미 가입된 이메일로 가입하면 ACCOUNT_001을 던진다")
    void rejectsSignUpWithDuplicateEmail() {
        // given
        StoreOperatorSignUpRequest request = new StoreOperatorSignUpRequest(
                "owner@example.com", "password123", "password123",
                "email-ref", "identity-ref", "미리윰식당");
        given(storeOperatorAccountRepository.existsByEmail("owner@example.com")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> storeOperatorAuthService.signUp(request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AccountErrorCode.EMAIL_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("비밀번호와 비밀번호 확인이 다르면 검증 오류를 던진다")
    void rejectsSignUpWithMismatchedPasswordConfirm() {
        // given
        StoreOperatorSignUpRequest request = new StoreOperatorSignUpRequest(
                "owner@example.com", "password123", "different456",
                "email-ref", "identity-ref", "미리윰식당");

        // when & then
        assertThatThrownBy(() -> storeOperatorAuthService.signUp(request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("본인확인 스텁이 꺼져 있으면 가입을 차단한다")
    void rejectsSignUpWhenIdentityVerificationStubDisabled() {
        // given
        StoreOperatorAuthService serviceWithStubDisabled = new StoreOperatorAuthService(
                storeOperatorAccountRepository, passwordEncoder, jwtTokenProvider, passwordPolicy, false);
        StoreOperatorSignUpRequest request = new StoreOperatorSignUpRequest(
                "owner@example.com", "password123", "password123",
                "email-ref", "identity-ref", "미리윰식당");

        // when & then
        assertThatThrownBy(() -> serviceWithStubDisabled.signUp(request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("가입에 성공하면 표시 이름을 저장하고 가입 응답을 반환한다")
    void signUpSucceeds() {
        // given
        StoreOperatorSignUpRequest request = new StoreOperatorSignUpRequest(
                "owner@example.com", "Password123!", "Password123!",
                "email-ref", "identity-ref", "미리윰식당");
        given(passwordEncoder.encode("Password123!")).willReturn("hashed");
        given(storeOperatorAccountRepository.saveAndFlush(any(StoreOperatorAccount.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // when
        AccountCreatedResponse response = storeOperatorAuthService.signUp(request);

        // then
        ArgumentCaptor<StoreOperatorAccount> captor = ArgumentCaptor.forClass(StoreOperatorAccount.class);
        verify(storeOperatorAccountRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getDisplayName()).isEqualTo("미리윰식당");
        assertThat(response.accountType()).isEqualTo(AccountType.STORE_OPERATOR);
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("영문 소문자와 숫자 2종만 포함한 비밀번호로 가입하면 검증 오류를 던진다")
    void rejectsSignUpWithWeakPassword() {
        // given
        StoreOperatorSignUpRequest request = new StoreOperatorSignUpRequest(
                "owner@example.com", "password123", "password123",
                "email-ref", "identity-ref", "미리윰식당");

        // when & then
        assertThatThrownBy(() -> storeOperatorAuthService.signUp(request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("존재하지 않는 이메일로 로그인하면 AUTH_005를 던진다")
    void rejectsLoginWithUnknownEmail() {
        // given
        LoginRequest request = new LoginRequest("unknown@example.com", "password123");
        given(storeOperatorAccountRepository.findByEmail("unknown@example.com")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> storeOperatorAuthService.login(request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("비밀번호가 일치하지 않으면 AUTH_005를 던진다")
    void rejectsLoginWithWrongPassword() {
        // given
        StoreOperatorAccount account = StoreOperatorAccount.create("owner@example.com", "hashed", "미리윰식당");
        LoginRequest request = new LoginRequest("owner@example.com", "wrong-password");
        given(storeOperatorAccountRepository.findByEmail("owner@example.com")).willReturn(Optional.of(account));
        given(passwordEncoder.matches("wrong-password", "hashed")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> storeOperatorAuthService.login(request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("이메일과 비밀번호가 맞으면 로그인에 성공해 Access/Refresh 토큰을 발급한다")
    void loginIssuesTokenPairOnSuccess() {
        // given
        StoreOperatorAccount account = StoreOperatorAccount.create("owner@example.com", "hashed", "미리윰식당");
        LoginRequest request = new LoginRequest("owner@example.com", "password123");
        given(storeOperatorAccountRepository.findByEmail("owner@example.com")).willReturn(Optional.of(account));
        given(passwordEncoder.matches("password123", "hashed")).willReturn(true);
        given(jwtTokenProvider.generateAccessToken(eq(TokenNamespace.STORE_OPERATOR), any()))
                .willReturn("access-token-value");
        given(jwtTokenProvider.generateRefreshToken(eq(TokenNamespace.STORE_OPERATOR), any()))
                .willReturn("refresh-token-value");

        // when
        TokenPair tokenPair = storeOperatorAuthService.login(request);

        // then
        assertThat(tokenPair.accessToken()).isEqualTo("access-token-value");
        assertThat(tokenPair.refreshToken()).isEqualTo("refresh-token-value");
    }

    @Test
    @DisplayName("NFD로 입력한 비밀번호도 NFC로 정규화한 뒤 비교한다")
    void loginNormalizesPasswordToNfcBeforeMatching() {
        // given: 가입 시 NFC로 저장된 비밀번호를, 로그인 시 NFD(자음+모음 분리)로 입력한 상황
        String nfcPassword = "password123가";
        String nfdPassword = java.text.Normalizer.normalize(nfcPassword, java.text.Normalizer.Form.NFD);
        StoreOperatorAccount account = StoreOperatorAccount.create("owner@example.com", "hashed", "미리윰식당");
        LoginRequest request = new LoginRequest("owner@example.com", nfdPassword);
        given(storeOperatorAccountRepository.findByEmail("owner@example.com")).willReturn(Optional.of(account));
        given(passwordEncoder.matches(nfcPassword, "hashed")).willReturn(true);
        given(jwtTokenProvider.generateAccessToken(eq(TokenNamespace.STORE_OPERATOR), any()))
                .willReturn("access-token-value");
        given(jwtTokenProvider.generateRefreshToken(eq(TokenNamespace.STORE_OPERATOR), any()))
                .willReturn("refresh-token-value");

        // when
        TokenPair tokenPair = storeOperatorAuthService.login(request);

        // then: NFD 원문이 아니라 NFC로 정규화된 값으로 matches()를 호출했기 때문에 성공한다
        assertThat(tokenPair.accessToken()).isEqualTo("access-token-value");
    }

    @Test
    @DisplayName("빈 Refresh Token으로 재발급하면 AUTH_007을 던진다")
    void rejectsRefreshWithBlankToken() {
        // when & then
        assertThatThrownBy(() -> storeOperatorAuthService.refresh(" "))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_REQUIRED);
    }
}
