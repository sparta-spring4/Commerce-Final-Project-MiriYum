package com.miriyum.domain.platformoperator.service;

import static com.miriyum.domain.platformoperator.enums.AdminCommandPurpose.PAYMENT_RECOVERY;
import static com.miriyum.domain.platformoperator.enums.AdminTargetType.PAYMENT_RECOVERY_CASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.platformoperator.dto.authorization.ReauthenticationApprovalRequest;
import com.miriyum.domain.platformoperator.dto.authorization.ReauthenticationApprovalResult;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorReauthenticationApproval;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPasswordState;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorReauthenticationApprovalRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

class ReauthenticationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private PlatformOperatorAccountRepository accounts;
    private PlatformOperatorReauthenticationApprovalRepository approvals;
    private PasswordEncoder encoder;
    private ReauthenticationService service;
    private PlatformOperatorAccount account;
    private PlatformOperatorPrincipal principal;

    @BeforeEach
    void setUp() {
        accounts = mock(PlatformOperatorAccountRepository.class);
        approvals = mock(PlatformOperatorReauthenticationApprovalRepository.class);
        encoder = mock(PasswordEncoder.class);
        service = new ReauthenticationService(
                accounts,
                approvals,
                encoder,
                Clock.fixed(NOW, ZoneOffset.UTC),
                "test-only-secret-key-must-be-at-least-32-bytes");
        account = mock(PlatformOperatorAccount.class);
        principal = new PlatformOperatorPrincipal(7L, "operator@example.com", "raw-session-id", 3L, 2L, false);
        when(accounts.findById(7L)).thenReturn(Optional.of(account));
        when(account.getPasswordHash()).thenReturn("encoded-password");
        when(account.getStatus()).thenReturn(PlatformOperatorAccountStatus.ACTIVE);
        when(account.getPasswordState()).thenReturn(PlatformOperatorPasswordState.ACTIVE);
        when(account.getAuthorityVersion()).thenReturn(3L);
        when(account.getSessionVersion()).thenReturn(2L);
        when(approvals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("현재 비밀번호 성공은 목적·대상·세션·version에 결속된 5분 승인 digest만 저장한다")
    void issuesFiveMinuteBoundApprovalWithoutPersistingPlaintext() {
        when(encoder.matches("Password1!", "encoded-password")).thenReturn(true);
        ReauthenticationApprovalRequest request = new ReauthenticationApprovalRequest(
                "Password1!", PAYMENT_RECOVERY, PAYMENT_RECOVERY_CASE, "recovery-1");

        ReauthenticationApprovalResult result = service.issue(principal, request);

        assertThat(result.approval()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(request.toString()).doesNotContain("Password1!");
        assertThat(result.toString()).doesNotContain(result.approval());
        assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        ArgumentCaptor<PlatformOperatorReauthenticationApproval> captor =
                ArgumentCaptor.forClass(PlatformOperatorReauthenticationApproval.class);
        verify(approvals).save(captor.capture());
        PlatformOperatorReauthenticationApproval saved = captor.getValue();
        assertThat(saved.getApprovalDigest()).hasSize(64).doesNotContain(result.approval());
        assertThat(saved.getSessionFingerprint()).hasSize(64).doesNotContain("raw-session-id");
        assertThat(saved.getPurpose()).isEqualTo(PAYMENT_RECOVERY);
        assertThat(saved.getTargetType()).isEqualTo(PAYMENT_RECOVERY_CASE);
        assertThat(saved.getTargetId()).isEqualTo("recovery-1");
        assertThat(saved.getAuthorityVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("현재 비밀번호가 다르면 승인 원장을 만들지 않고 재인증 실패로 거부한다")
    void rejectsCurrentPasswordMismatch() {
        when(encoder.matches("Wrong1!", "encoded-password")).thenReturn(false);
        ReauthenticationApprovalRequest request = new ReauthenticationApprovalRequest(
                "Wrong1!", PAYMENT_RECOVERY, PAYMENT_RECOVERY_CASE, "recovery-1");

        assertThatThrownBy(() -> service.issue(principal, request))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(AdminAuthorizationErrorCode.REAUTHENTICATION_FAILED));
    }

    @Test
    void rejectsAStaleAuthorityVersionWithTheSessionInvalidationContract() {
        when(account.getAuthorityVersion()).thenReturn(4L);
        ReauthenticationApprovalRequest request = new ReauthenticationApprovalRequest(
                "Password1!", PAYMENT_RECOVERY, PAYMENT_RECOVERY_CASE, "recovery-1");

        assertThatThrownBy(() -> service.issue(principal, request))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID));
    }
}
