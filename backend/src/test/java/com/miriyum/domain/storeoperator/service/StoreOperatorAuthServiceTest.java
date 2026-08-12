package com.miriyum.domain.storeoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.contact.PhoneNumberPolicy;
import com.miriyum.domain.auth.dto.response.AccountCreatedResponse;
import com.miriyum.domain.auth.dto.response.AccountType;
import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.jwt.TokenPair;
import com.miriyum.domain.auth.logindelay.LoginDelayGuard;
import com.miriyum.domain.auth.logindelay.LoginAttempt;
import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.storeoperator.dto.auth.StoreOperatorSignUpRequest;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StoreOperatorAuthServiceTest {

    private static final Long ACCOUNT_ID = 1L;

    @Mock
    private StoreOperatorAccountRepository storeOperatorAccountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private LoginDelayGuard loginDelayGuard;

    private final PasswordPolicy passwordPolicy = new PasswordPolicy();

    private StoreOperatorAuthService storeOperatorAuthService;

    @BeforeEach
    void setUp() {
        storeOperatorAuthService = new StoreOperatorAuthService(
                storeOperatorAccountRepository, passwordEncoder, jwtTokenProvider, passwordPolicy,
                loginDelayGuard, new PhoneNumberPolicy());
    }

    @Test
    @DisplayName("이미 가입된 이메일로 가입하면 ACCOUNT_001을 던진다")
    void rejectsSignUpWithDuplicateEmail() {
        // given
        StoreOperatorSignUpRequest request = new StoreOperatorSignUpRequest(
                "owner@example.com", "password123", "password123",
                "010-1234-5678", "미리윰식당");
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
                "010-1234-5678", "미리윰식당");

        // when & then
        assertThatThrownBy(() -> storeOperatorAuthService.signUp(request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("가입에 성공하면 표시 이름을 저장하고 가입 응답을 반환한다")
    void signUpSucceeds() {
        // given
        StoreOperatorSignUpRequest request = new StoreOperatorSignUpRequest(
                "owner@example.com", "Password123!", "Password123!",
                "010-1234-5678", "미리윰식당");
        given(passwordEncoder.encode("Password123!")).willReturn("hashed");
        given(storeOperatorAccountRepository.saveAndFlush(any(StoreOperatorAccount.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // when
        AccountCreatedResponse response = storeOperatorAuthService.signUp(request);

        // then
        ArgumentCaptor<StoreOperatorAccount> captor = ArgumentCaptor.forClass(StoreOperatorAccount.class);
        verify(storeOperatorAccountRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getDisplayName()).isEqualTo("미리윰식당");
        assertThat(captor.getValue().getPhone()).isEqualTo("01012345678");
        assertThat(response.accountType()).isEqualTo(AccountType.STORE_OPERATOR);
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("영문 소문자와 숫자 2종만 포함한 비밀번호로 가입하면 검증 오류를 던진다")
    void rejectsSignUpWithWeakPassword() {
        // given
        StoreOperatorSignUpRequest request = new StoreOperatorSignUpRequest(
                "owner@example.com", "password123", "password123",
                "010-1234-5678", "미리윰식당");

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
        StoreOperatorAccount account = persistedAccount();
        LoginRequest request = new LoginRequest("owner@example.com", "wrong-password");
        given(storeOperatorAccountRepository.findByEmail("owner@example.com")).willReturn(Optional.of(account));
        delegatePasswordCheckToEncoder();
        given(passwordEncoder.matches("wrong-password", "hashed")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> storeOperatorAuthService.login(request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("잘못된 비밀번호 해시 형식으로 운영자 로그인을 거부한다")
    void rejectsLoginWithMalformedPasswordHash() {
        StoreOperatorAccount account = persistedAccount();
        LoginRequest request = new LoginRequest("owner@example.com", "password123");
        given(storeOperatorAccountRepository.findByEmail("owner@example.com")).willReturn(Optional.of(account));
        delegatePasswordCheckToEncoder();
        given(passwordEncoder.matches("password123", "hashed"))
                .willThrow(new IllegalArgumentException("No PasswordEncoder mapped for id null"));

        assertThatThrownBy(() -> storeOperatorAuthService.login(request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
        verify(loginDelayGuard).completeAttempt(
                eq(TokenNamespace.STORE_OPERATOR), eq(ACCOUNT_ID), any(), eq(false));
    }

    @Test
    void rejectsBusyLoginWithoutPasswordComparison() {
        StoreOperatorAccount account = persistedAccount();
        LoginRequest request = new LoginRequest("owner@example.com", "password123");
        given(storeOperatorAccountRepository.findByEmail("owner@example.com")).willReturn(Optional.of(account));
        given(loginDelayGuard.tryAcquireAttempt(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID))
                .willReturn(LoginAttempt.busy());

        assertThatThrownBy(() -> storeOperatorAuthService.login(request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);

        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void rejectsLoginWhenAttemptOwnershipIsLost() {
        StoreOperatorAccount account = persistedAccount();
        LoginRequest request = new LoginRequest("owner@example.com", "password123");
        given(storeOperatorAccountRepository.findByEmail("owner@example.com")).willReturn(Optional.of(account));
        given(loginDelayGuard.tryAcquireAttempt(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID))
                .willReturn(LoginAttempt.acquired("attempt-token"));
        given(passwordEncoder.matches("password123", "hashed")).willReturn(true);
        given(loginDelayGuard.completeAttempt(any(), anyLong(), any(), anyBoolean())).willReturn(false);

        assertThatThrownBy(() -> storeOperatorAuthService.login(request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);

        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    @DisplayName("이메일과 비밀번호가 맞으면 로그인에 성공해 Access/Refresh 토큰을 발급한다")
    void loginIssuesTokenPairOnSuccess() {
        // given
        StoreOperatorAccount account = persistedAccount();
        LoginRequest request = new LoginRequest("owner@example.com", "password123");
        given(storeOperatorAccountRepository.findByEmail("owner@example.com")).willReturn(Optional.of(account));
        delegatePasswordCheckToEncoder();
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
        verify(loginDelayGuard).tryAcquireAttempt(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID);
    }

    @Test
    @DisplayName("NFD로 입력한 비밀번호도 NFC로 정규화한 뒤 비교한다")
    void loginNormalizesPasswordToNfcBeforeMatching() {
        // given: 가입 시 NFC로 저장된 비밀번호를, 로그인 시 NFD(자음+모음 분리)로 입력한 상황
        String nfcPassword = "password123가";
        String nfdPassword = java.text.Normalizer.normalize(nfcPassword, java.text.Normalizer.Form.NFD);
        StoreOperatorAccount account = persistedAccount();
        LoginRequest request = new LoginRequest("owner@example.com", nfdPassword);
        given(storeOperatorAccountRepository.findByEmail("owner@example.com")).willReturn(Optional.of(account));
        delegatePasswordCheckToEncoder();
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

    /**
     * 실제 {@code LoginDelayGuard}는 계정 행을 잠근 뒤 지연 여부를 판정한다. 이 단위 테스트는
     * 지연이 아닌 비밀번호 비교 규칙을 확인하므로, 대역이 항상 시도를 허용하게 해 지연이 걸리지
     * 않은 상태를 재현한다.
     */
    private void delegatePasswordCheckToEncoder() {
        given(loginDelayGuard.tryAcquireAttempt(any(), anyLong()))
                .willReturn(LoginAttempt.acquired("attempt-token"));
        given(loginDelayGuard.completeAttempt(any(), anyLong(), any(), anyBoolean())).willReturn(true);
    }

    /**
     * 로그인은 조회한 계정의 PK로 지연 상태를 확인하므로, 실제 경로처럼 ID가 채워진 계정을 쓴다.
     * {@code create()}만 호출한 엔티티는 아직 영속되지 않아 ID가 {@code null}이다.
     */
    private StoreOperatorAccount persistedAccount() {
        StoreOperatorAccount account = StoreOperatorAccount.create("owner@example.com", "hashed", "미리윰식당");
        ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);
        return account;
    }
}
