package com.miriyum.domain.platformoperator.service;

import static com.miriyum.domain.platformoperator.enums.AdminCommandPurpose.PAYMENT_RECOVERY;
import static com.miriyum.domain.platformoperator.enums.AdminTargetType.PAYMENT_RECOVERY_CASE;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE;
import static com.miriyum.domain.platformoperator.enums.PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.dto.authorization.HighRiskCommandRequest;
import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorReauthenticationApprovalRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class HighRiskCommandGuardTest {
    private PlatformOperatorAccountRepository accounts;
    private OperatorAuthorityReader authorities;
    private AdminCaseAssignmentVerifier assignments;
    private PlatformOperatorReauthenticationApprovalRepository approvals;
    private HighRiskCommandGuard guard;
    private HighRiskCommandRequest request;

    @BeforeEach
    void setUp() {
        accounts = mock(PlatformOperatorAccountRepository.class);
        authorities = mock(OperatorAuthorityReader.class);
        assignments = mock(AdminCaseAssignmentVerifier.class);
        approvals = mock(PlatformOperatorReauthenticationApprovalRepository.class);
        ReauthenticationService reauthentication = mock(ReauthenticationService.class);
        guard = new HighRiskCommandGuard(accounts, authorities, assignments, approvals, reauthentication,
                Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC));
        PlatformOperatorPrincipal principal = new PlatformOperatorPrincipal(
                7L, "operator@example.com", "session-raw", 3L, 2L, false);
        request = new HighRiskCommandRequest(principal, PAYMENT_RECOVERY_EXECUTE,
                com.miriyum.domain.platformoperator.enums.AdminCaseType.PAYMENT_RECOVERY,
                "case-1", 4L, PAYMENT_RECOVERY, PAYMENT_RECOVERY_CASE, "target-1", "approval-raw", "corr-1");
        PlatformOperatorAccount account = mock(PlatformOperatorAccount.class);
        when(account.getId()).thenReturn(7L);
        when(account.getStatus()).thenReturn(PlatformOperatorAccountStatus.ACTIVE);
        when(account.getAuthorityVersion()).thenReturn(3L);
        when(account.getSessionVersion()).thenReturn(2L);
        when(accounts.findByIdForUpdate(7L)).thenReturn(Optional.of(account));
        when(reauthentication.sessionFingerprint("session-raw")).thenReturn("session-fingerprint");
    }

    @Test
    void requiresPermissionAssignmentAndAExactlyBoundUnconsumedApproval() {
        when(authorities.requireCurrentAuthority(7L, 3L)).thenReturn(new OperatorAuthority(
                7L, 3L, Set.of(PAYMENT_RECOVERY_OPERATOR), Set.of(PAYMENT_RECOVERY_EXECUTE)));
        when(approvals.consumeBoundApproval(any(), any(Long.class), any(), any(), any(), any(), any(Long.class), any()))
                .thenReturn(1);

        try (MockedStatic<TransactionSynchronizationManager> tx = mockStatic(TransactionSynchronizationManager.class)) {
            tx.when(TransactionSynchronizationManager::isActualTransactionActive).thenReturn(true);
            AdminAuditContext context = guard.authorize(request);

            assertThat(context.permissions()).contains(PAYMENT_RECOVERY_EXECUTE);
            assertThat(context.approvalFingerprint()).hasSize(64).doesNotContain("approval-raw");
            assertThat(context.toString()).doesNotContain("approval-raw", "session-raw");
        }
    }

    @Test
    void rejectsMissingPermission() {
        when(authorities.requireCurrentAuthority(7L, 3L))
                .thenReturn(new OperatorAuthority(7L, 3L, Set.of(), Set.of()));
        assertDenied();
    }

    @Test
    void rejectsMissingAssignment() {
        when(authorities.requireCurrentAuthority(7L, 3L)).thenReturn(new OperatorAuthority(
                7L, 3L, Set.of(), Set.of(PAYMENT_RECOVERY_EXECUTE)));
        doThrow(new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED))
                .when(assignments).verify(any());
        assertDenied();
    }

    @Test
    void rejectsConsumedExpiredOrDifferentlyBoundApproval() {
        when(authorities.requireCurrentAuthority(7L, 3L)).thenReturn(new OperatorAuthority(
                7L, 3L, Set.of(), Set.of(PAYMENT_RECOVERY_EXECUTE)));
        when(approvals.consumeBoundApproval(any(), any(Long.class), any(), any(), any(), any(), any(Long.class), any()))
                .thenReturn(0);
        assertDenied();
    }

    private void assertDenied() {
        try (MockedStatic<TransactionSynchronizationManager> tx = mockStatic(TransactionSynchronizationManager.class)) {
            tx.when(TransactionSynchronizationManager::isActualTransactionActive).thenReturn(true);
            assertThatThrownBy(() -> guard.authorize(request))
                    .isInstanceOf(ServiceException.class)
                    .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                            .isEqualTo(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED));
        }
    }
}
