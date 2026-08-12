package com.miriyum.domain.storeoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;

import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.contact.PhoneNumberPolicy;
import com.miriyum.domain.auth.dto.response.AccountCreatedResponse;
import com.miriyum.domain.auth.dto.response.AccountType;
import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.jwt.TokenPair;
import com.miriyum.domain.auth.logindelay.LoginDelayGuard;
import com.miriyum.domain.auth.logindelay.LoginAttempt;
import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenManager;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenRotationAttempt;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenRotationResult;
import com.miriyum.domain.storeoperator.dto.auth.StoreOperatorSignUpRequest;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.enums.StoreOperatorAccountStatus;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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

    @Mock
    private RefreshTokenManager refreshTokenManager;

    private final PasswordPolicy passwordPolicy = new PasswordPolicy();

    private StoreOperatorAuthService storeOperatorAuthService;

    @BeforeEach
    void setUp() {
        storeOperatorAuthService = new StoreOperatorAuthService(
                storeOperatorAccountRepository, passwordEncoder, jwtTokenProvider, passwordPolicy,
                loginDelayGuard, new PhoneNumberPolicy(), refreshTokenManager);
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
    @DisplayName("로그인 시작 시점의 세션 세대를 토큰 발급까지 유지한다")
    void loginIssuesTokenPairWithCapturedSessionEpoch() {
        StoreOperatorAccount account = persistedAccount();
        LoginRequest request = new LoginRequest("owner@example.com", "password123");
        given(storeOperatorAccountRepository.findByEmail("owner@example.com")).willReturn(Optional.of(account));
        delegatePasswordCheckToEncoder();
        given(refreshTokenManager.captureSessionEpoch(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID)).willReturn(4L);
        given(passwordEncoder.matches("password123", "hashed")).willReturn(true);
        given(refreshTokenManager.issue(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID, 4L))
                .willReturn(new TokenPair("access-token-value", "refresh-token-value"));

        storeOperatorAuthService.login(request);

        InOrder order = inOrder(refreshTokenManager, passwordEncoder);
        order.verify(passwordEncoder).matches("password123", "hashed");
        order.verify(refreshTokenManager).captureSessionEpoch(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID);
        order.verify(refreshTokenManager).issue(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID, 4L);
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
        given(refreshTokenManager.captureSessionEpoch(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID)).willReturn(0L);
        given(refreshTokenManager.issue(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID, 0L))
                .willReturn(new TokenPair("access-token-value", "refresh-token-value"));

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
        given(refreshTokenManager.captureSessionEpoch(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID)).willReturn(0L);
        given(refreshTokenManager.issue(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID, 0L))
                .willReturn(new TokenPair("access-token-value", "refresh-token-value"));

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

    @Test
    @DisplayName("정지된 매장 운영자 계정의 재발급 요청은 모든 Refresh Token family를 폐기한다")
    void revokesAllRefreshTokenFamiliesWhenSuspendedAccountRefreshes() {
        StoreOperatorAccount account = persistedAccount();
        ReflectionTestUtils.setField(account, "status", StoreOperatorAccountStatus.SUSPENDED);
        ParsedToken parsedToken = new ParsedToken(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID, "family-id", "token-id");
        given(jwtTokenProvider.parseRefreshToken("refresh-token")).willReturn(parsedToken);
        given(refreshTokenManager.attemptRotate(TokenNamespace.STORE_OPERATOR, parsedToken, "refresh-token"))
                .willReturn(new RefreshTokenRotationAttempt(
                        RefreshTokenRotationResult.Status.ROTATED,
                        new TokenPair("access-token", "refresh-token-next")));
        lenient().when(storeOperatorAccountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> storeOperatorAuthService.refresh("refresh-token"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED);

        InOrder order = inOrder(refreshTokenManager);
        order.verify(refreshTokenManager).attemptRotate(TokenNamespace.STORE_OPERATOR, parsedToken, "refresh-token");
        order.verify(refreshTokenManager).revokeAll(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID);
    }

    @Test
    @DisplayName("정지 매장 운영자도 재사용 Refresh Token은 먼저 탐지한다")
    void detectsRefreshTokenReuseBeforeRejectingSuspendedAccount() {
        StoreOperatorAccount account = persistedAccount();
        ReflectionTestUtils.setField(account, "status", StoreOperatorAccountStatus.SUSPENDED);
        ParsedToken parsedToken = new ParsedToken(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID, "family-id", "token-id");
        given(jwtTokenProvider.parseRefreshToken("reused-refresh-token")).willReturn(parsedToken);
        given(refreshTokenManager.attemptRotate(TokenNamespace.STORE_OPERATOR, parsedToken, "reused-refresh-token"))
                .willReturn(new RefreshTokenRotationAttempt(RefreshTokenRotationResult.Status.REUSED, null));

        assertThatThrownBy(() -> storeOperatorAuthService.refresh("reused-refresh-token"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);

        InOrder order = inOrder(refreshTokenManager);
        order.verify(refreshTokenManager).attemptRotate(TokenNamespace.STORE_OPERATOR, parsedToken, "reused-refresh-token");
        order.verify(refreshTokenManager).revokeAll(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID);
    }

    @Test
    @DisplayName("활성 매장 운영자의 재사용 Refresh Token은 해당 family만 폐기한다")
    void doesNotRevokeAllFamiliesWhenActiveAccountReusesRefreshToken() {
        StoreOperatorAccount account = persistedAccount();
        ParsedToken parsedToken = new ParsedToken(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID, "family-id", "token-id");
        given(jwtTokenProvider.parseRefreshToken("reused-refresh-token")).willReturn(parsedToken);
        given(refreshTokenManager.attemptRotate(TokenNamespace.STORE_OPERATOR, parsedToken, "reused-refresh-token"))
                .willReturn(new RefreshTokenRotationAttempt(RefreshTokenRotationResult.Status.REUSED, null));
        lenient().when(storeOperatorAccountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> storeOperatorAuthService.refresh("reused-refresh-token"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);

        verify(refreshTokenManager).attemptRotate(TokenNamespace.STORE_OPERATOR, parsedToken, "reused-refresh-token");
        verifyNoMoreInteractions(refreshTokenManager);
    }

    @Test
    @DisplayName("만료된 Refresh Token으로 로그아웃하면 같은 성공 결과로 수렴한다")
    void logoutWithExpiredRefreshTokenIsIdempotent() {
        given(jwtTokenProvider.parseRefreshTokenForLogout("expired-refresh-token")).willReturn(null);

        assertThatCode(() -> storeOperatorAuthService.logout("expired-refresh-token"))
                .doesNotThrowAnyException();

        verifyNoInteractions(refreshTokenManager);
    }

    @Test
    @DisplayName("Refresh Token 쿠키가 없어도 로그아웃하면 같은 성공 결과로 수렴한다")
    void logoutWithoutRefreshTokenIsIdempotent() {
        assertThatCode(() -> storeOperatorAuthService.logout(null))
                .doesNotThrowAnyException();
        assertThatCode(() -> storeOperatorAuthService.logout(" "))
                .doesNotThrowAnyException();

        verifyNoInteractions(jwtTokenProvider, refreshTokenManager);
    }

    /**
     * 실제 {@code LoginDelayGuard}는 계정 행을 잠근 뒤 지연 여부를 판정한다. 이 단위 테스트는
     * 지연이 아닌 비밀번호 비교 규칙을 확인하므로, 대역이 항상 시도를 허용하게 해 지연이 걸리지
     * 않은 상태를 재현한다.
     */
    @Test
    @DisplayName("로그인 중 StoreOperatorAccount 계정이 정지되면 Refresh Token을 발급하지 않는다")
    void rejectsLoginWhenAccountIsSuspendedAfterInitialLookup() {
        StoreOperatorAccount initialAccount = persistedAccount();
        StoreOperatorAccount currentAccount = persistedAccount();
        ReflectionTestUtils.setField(currentAccount, "status", StoreOperatorAccountStatus.SUSPENDED);
        LoginRequest request = new LoginRequest("owner@example.com", "password123");
        given(storeOperatorAccountRepository.findByEmail("owner@example.com")).willReturn(Optional.of(initialAccount));
        delegatePasswordCheckToEncoder();
        given(passwordEncoder.matches("password123", "hashed")).willReturn(true);
        given(refreshTokenManager.captureSessionEpoch(TokenNamespace.STORE_OPERATOR, ACCOUNT_ID)).willReturn(1L);

        assertThatThrownBy(() -> storeOperatorAuthService.login(request))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED));
    }
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
        lenient().when(storeOperatorAccountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        return account;
    }
}
