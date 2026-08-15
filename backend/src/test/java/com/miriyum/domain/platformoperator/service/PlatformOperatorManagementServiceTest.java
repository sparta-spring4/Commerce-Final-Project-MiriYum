package com.miriyum.domain.platformoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.platformoperator.config.PlatformOperatorAuthProperties;
import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.dto.management.PlatformOperatorAccountCreateRequest;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorPermissionGrantRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorReauthenticationApprovalRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PlatformOperatorManagementServiceTest {

    private PlatformOperatorAccountRepository accounts;
    private LastSuperAdminPolicy singletonPolicy;
    private HighRiskCommandGuard highRiskGuard;
    private IdempotencyExecutor idempotency;
    private PlatformOperatorManagementService service;

    @BeforeEach
    void setUp() {
        accounts = mock(PlatformOperatorAccountRepository.class);
        singletonPolicy = mock(LastSuperAdminPolicy.class);
        highRiskGuard = mock(HighRiskCommandGuard.class);
        idempotency = mock(IdempotencyExecutor.class);
        PlatformOperatorAuthProperties properties = new PlatformOperatorAuthProperties();
        properties.getTemporaryPassword().setValidity(Duration.ofMinutes(10));
        properties.getTemporaryPassword().setMaxFailures(3);
        service = new PlatformOperatorManagementService(
                accounts,
                mock(PlatformOperatorRoleGrantRepository.class),
                mock(PlatformOperatorPermissionGrantRepository.class),
                mock(PlatformOperatorReauthenticationApprovalRepository.class),
                highRiskGuard,
                singletonPolicy,
                mock(OperatorAuthorityService.class),
                mock(PlatformOperatorSessionRevocationAfterCommit.class),
                mock(PlatformOperatorAuditWriter.class),
                idempotency,
                mock(PasswordEncoder.class),
                mock(PasswordPolicy.class),
                properties,
                Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("일반 운영자는 운영자 생성 권한·배정·재인증을 갖춰도 singleton 정책에서 거부된다")
    void createAccount_ordinaryOperatorDeniedBeforeAccountPersistence() {
        IdempotencyCommand command = new IdempotencyCommand(
                "platform-operator", 9L, "OPERATOR_CREATE",
                "123e4567-e89b-12d3-a456-426614174000", "a".repeat(64));
        PlatformOperatorPrincipal principal = new PlatformOperatorPrincipal(
                9L, "ordinary@example.com", "session-9", 2L, 2L, false);
        PlatformOperatorAccountCreateRequest request = new PlatformOperatorAccountCreateRequest(
                UUID.fromString("d9428888-122b-4ef8-bf85-b6258f3652be"),
                "new-operator@example.com",
                "new operator",
                "Password1!",
                Set.of(PlatformOperatorRole.AUDIT_READER),
                Set.of(PlatformOperatorPermission.AUDIT_READ),
                PlatformOperatorAuditReason.ACCOUNT_PROVISIONING);
        when(highRiskGuard.authorize(any())).thenReturn(new AdminAuditContext(
                9L, Set.of(PlatformOperatorRole.AUDIT_READER),
                Set.of(PlatformOperatorPermission.OPERATOR_CREATE), 2L,
                AdminCaseType.OPERATOR_MANAGEMENT, "operator-management-9", 1L,
                AdminCommandPurpose.OPERATOR_CREATION, AdminTargetType.PLATFORM_OPERATOR_ACCOUNT,
                request.provisioningId().toString(), "digest", "correlation-9"));
        org.mockito.Mockito.doThrow(new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED))
                .when(singletonPolicy).requireSingletonActor(9L);
        when(idempotency.execute(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<com.miriyum.global.idempotency.BusinessResult<Object>> work = invocation.getArgument(1);
            work.get();
            return new IdempotentOutcome(false, 201, "SUCCESS", null, null, null);
        });

        assertThatThrownBy(() -> service.createAccount(
                command, principal, request, "operator-management-9", 1L, "approval", "correlation-9"))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED));
        verify(accounts, never()).saveAndFlush(any());
    }
}
