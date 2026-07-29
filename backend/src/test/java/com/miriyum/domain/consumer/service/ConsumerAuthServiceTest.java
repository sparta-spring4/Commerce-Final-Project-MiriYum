package com.miriyum.domain.consumer.service;

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
import com.miriyum.domain.consumer.dto.request.ConsumerSignUpRequest;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
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
class ConsumerAuthServiceTest {

    @Mock
    private ConsumerAccountRepository consumerAccountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private final NicknamePolicy nicknamePolicy = new NicknamePolicy();

    private ConsumerAuthService consumerAuthService;

    @BeforeEach
    void setUp() {
        consumerAuthService = new ConsumerAuthService(
                consumerAccountRepository, passwordEncoder, jwtTokenProvider, nicknamePolicy);
    }

    @Test
    @DisplayName("이미 가입된 이메일로 가입하면 ACCOUNT_001을 던진다")
    void rejectsSignUpWithDuplicateEmail() {
        // given
        ConsumerSignUpRequest request = new ConsumerSignUpRequest(
                "user@example.com", "password123", "password123",
                "email-ref", "identity-ref", "닉네임");
        given(consumerAccountRepository.existsByEmail("user@example.com")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> consumerAuthService.signUp(request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AccountErrorCode.EMAIL_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("가입에 성공하면 닉네임을 정규화해서 저장하고 가입 응답을 반환한다")
    void signUpSucceedsAndNormalizesNickname() {
        // given
        ConsumerSignUpRequest request = new ConsumerSignUpRequest(
                "user@example.com", "password123", "password123",
                "email-ref", "identity-ref", "  새 닉네임  ");
        given(passwordEncoder.encode("password123")).willReturn("hashed");
        given(consumerAccountRepository.saveAndFlush(any(ConsumerAccount.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // when
        AccountCreatedResponse response = consumerAuthService.signUp(request);

        // then
        ArgumentCaptor<ConsumerAccount> captor = ArgumentCaptor.forClass(ConsumerAccount.class);
        verify(consumerAccountRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("새 닉네임");
        assertThat(response.accountType()).isEqualTo(AccountType.CONSUMER);
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("비밀번호와 비밀번호 확인이 다르면 검증 오류를 던진다")
    void rejectsSignUpWithMismatchedPasswordConfirm() {
        // given
        ConsumerSignUpRequest request = new ConsumerSignUpRequest(
                "user@example.com", "password123", "different456",
                "email-ref", "identity-ref", "닉네임");

        // when & then
        assertThatThrownBy(() -> consumerAuthService.signUp(request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("존재하지 않는 이메일로 로그인하면 AUTH_005를 던진다")
    void rejectsLoginWithUnknownEmail() {
        // given
        LoginRequest request = new LoginRequest("unknown@example.com", "password123");
        given(consumerAccountRepository.findByEmail("unknown@example.com")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> consumerAuthService.login(request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("비밀번호가 일치하지 않으면 AUTH_005를 던진다")
    void rejectsLoginWithWrongPassword() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "010-1234-5678", "닉네임");
        LoginRequest request = new LoginRequest("user@example.com", "wrong-password");
        given(consumerAccountRepository.findByEmail("user@example.com")).willReturn(Optional.of(account));
        given(passwordEncoder.matches("wrong-password", "hashed")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> consumerAuthService.login(request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("이메일과 비밀번호가 맞으면 로그인에 성공해 Access/Refresh 토큰을 발급한다")
    void loginIssuesTokenPairOnSuccess() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "010-1234-5678", "닉네임");
        LoginRequest request = new LoginRequest("user@example.com", "password123");
        given(consumerAccountRepository.findByEmail("user@example.com")).willReturn(Optional.of(account));
        given(passwordEncoder.matches("password123", "hashed")).willReturn(true);
        given(jwtTokenProvider.generateAccessToken(eq(TokenNamespace.CONSUMER), any()))
                .willReturn("access-token-value");
        given(jwtTokenProvider.generateRefreshToken(eq(TokenNamespace.CONSUMER), any()))
                .willReturn("refresh-token-value");

        // when
        TokenPair tokenPair = consumerAuthService.login(request);

        // then
        assertThat(tokenPair.accessToken()).isEqualTo("access-token-value");
        assertThat(tokenPair.refreshToken()).isEqualTo("refresh-token-value");
    }

    @Test
    @DisplayName("빈 Refresh Token으로 재발급하면 AUTH_007을 던진다")
    void rejectsRefreshWithBlankToken() {
        // when & then
        assertThatThrownBy(() -> consumerAuthService.refresh(" "))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_REQUIRED);
    }
}
