package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.platformoperator.config.PlatformOperatorAuthProperties;
import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.dto.authorization.HighRiskCommandRequest;
import com.miriyum.domain.platformoperator.dto.management.PlatformOperatorAccountCreateRequest;
import com.miriyum.domain.platformoperator.dto.management.PlatformOperatorAccountData;
import com.miriyum.domain.platformoperator.dto.management.PlatformOperatorAuthorityReplaceRequest;
import com.miriyum.domain.platformoperator.dto.management.PlatformOperatorSuspensionRequest;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorPermissionGrant;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorPermissionGrantRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorReauthenticationApprovalRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.platformoperator.session.PlatformOperatorSessionManager;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorManagementService {

    private final PlatformOperatorAccountRepository accounts;
    private final PlatformOperatorRoleGrantRepository roles;
    private final PlatformOperatorPermissionGrantRepository permissions;
    private final PlatformOperatorReauthenticationApprovalRepository approvals;
    private final HighRiskCommandGuard highRiskGuard;
    private final LastSuperAdminPolicy singletonPolicy;
    private final OperatorAuthorityService authorityService;
    private final PlatformOperatorSessionManager sessions;
    private final PlatformOperatorAuditWriter auditWriter;
    private final IdempotencyExecutor idempotency;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final PlatformOperatorAuthProperties properties;
    private final Clock clock;

    public PlatformOperatorManagementService(
            PlatformOperatorAccountRepository accounts,
            PlatformOperatorRoleGrantRepository roles,
            PlatformOperatorPermissionGrantRepository permissions,
            PlatformOperatorReauthenticationApprovalRepository approvals,
            HighRiskCommandGuard highRiskGuard,
            LastSuperAdminPolicy singletonPolicy,
            OperatorAuthorityService authorityService,
            PlatformOperatorSessionManager sessions,
            PlatformOperatorAuditWriter auditWriter,
            IdempotencyExecutor idempotency,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            PlatformOperatorAuthProperties properties,
            Clock clock
    ) {
        this.accounts = accounts;
        this.roles = roles;
        this.permissions = permissions;
        this.approvals = approvals;
        this.highRiskGuard = highRiskGuard;
        this.singletonPolicy = singletonPolicy;
        this.authorityService = authorityService;
        this.sessions = sessions;
        this.auditWriter = auditWriter;
        this.idempotency = idempotency;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public IdempotentOutcome createAccount(
            IdempotencyCommand command,
            PlatformOperatorPrincipal principal,
            PlatformOperatorAccountCreateRequest request,
            String caseId,
            long caseVersion,
            String approval,
            String correlationId
    ) {
        singletonPolicy.requireSingletonActor(principal.accountId());
        return idempotency.execute(command, () -> {
            OperatorAuthorityService.validateAssignable(request.roles(), request.directPermissions());
            AdminAuditContext context = authorize(principal, PlatformOperatorPermission.OPERATOR_CREATE,
                    caseId, caseVersion, AdminCommandPurpose.OPERATOR_CREATION,
                    request.provisioningId().toString(), approval, correlationId);
            String email = request.email().strip().toLowerCase(Locale.ROOT);
            if (accounts.existsByEmail(email)) {
                throw new ServiceException(AdminAuthorizationErrorCode.DUPLICATE_OPERATOR_EMAIL);
            }
            String normalizedPassword = passwordPolicy.normalize(request.temporaryPassword());
            PlatformOperatorAccount account = PlatformOperatorAccount.createTemporary(
                    email, passwordEncoder.encode(normalizedPassword), request.displayName().strip(),
                    clock.instant().plus(properties.getTemporaryPassword().getValidity()));
            try {
                accounts.saveAndFlush(account);
                request.roles().forEach(role -> roles.save(PlatformOperatorRoleGrant.create(
                        account.getId(), role, clock.instant())));
                request.directPermissions().forEach(permission -> permissions.save(
                        PlatformOperatorPermissionGrant.create(account.getId(), permission, clock.instant())));
                auditWriter.appendManagement(new PlatformOperatorAuditWriter.ManagementEvent(
                        context, PlatformOperatorAuditAction.ACCOUNT_CREATED,
                        PlatformOperatorAuditOutcome.SUCCESS, request.reason(), command.idempotencyKey(),
                        null, PlatformOperatorAccountStatus.ACTIVE, Set.of(), request.roles(),
                        Set.of(), request.directPermissions()));
            } catch (DataIntegrityViolationException exception) {
                throw mapIntegrity(exception);
            }
            PlatformOperatorAccountData data = PlatformOperatorAccountData.from(
                    account, request.roles(), request.directPermissions());
            return success(HttpStatus.CREATED, account.getId(), data);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public IdempotentOutcome replaceAuthority(
            IdempotencyCommand command,
            PlatformOperatorPrincipal principal,
            long targetOperatorId,
            PlatformOperatorAuthorityReplaceRequest request,
            String caseId,
            long caseVersion,
            String approval,
            String correlationId
    ) {
        singletonPolicy.requireSingletonActor(principal.accountId());
        return idempotency.execute(command, () -> {
            AdminAuditContext context = authorize(principal, PlatformOperatorPermission.OPERATOR_AUTHORITY_MANAGE,
                    caseId, caseVersion, AdminCommandPurpose.OPERATOR_AUTHORITY_CHANGE,
                    Long.toString(targetOperatorId), approval, correlationId);
            PlatformOperatorAccount target = lockActive(targetOperatorId);
            OperatorAuthorityService.AuthorityReplacement replacement = authorityService.replaceAuthority(
                    targetOperatorId, target.getAuthorityVersion(), request.roles(), request.directPermissions());
            if (replacement.changed()) {
                revokeApprovalsAndSessions(targetOperatorId);
            }
            auditWriter.appendManagement(new PlatformOperatorAuditWriter.ManagementEvent(
                    context, PlatformOperatorAuditAction.AUTHORITY_REPLACED,
                    PlatformOperatorAuditOutcome.SUCCESS, request.reason(), command.idempotencyKey(),
                    target.getStatus(), target.getStatus(), replacement.beforeRoles(), replacement.afterRoles(),
                    replacement.beforePermissions(), replacement.afterPermissions()));
            return success(HttpStatus.OK, target.getId(), PlatformOperatorAccountData.from(
                    target, replacement.afterRoles(), replacement.afterPermissions()));
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public IdempotentOutcome suspendAccount(
            IdempotencyCommand command,
            PlatformOperatorPrincipal principal,
            long targetOperatorId,
            PlatformOperatorSuspensionRequest request,
            String caseId,
            long caseVersion,
            String approval,
            String correlationId
    ) {
        singletonPolicy.requireSingletonActor(principal.accountId());
        return idempotency.execute(command, () -> {
            AdminAuditContext context = authorize(principal, PlatformOperatorPermission.OPERATOR_SUSPEND,
                    caseId, caseVersion, AdminCommandPurpose.OPERATOR_SUSPENSION,
                    Long.toString(targetOperatorId), approval, correlationId);
            PlatformOperatorAccount target = lockActive(targetOperatorId);
            singletonPolicy.assertRemovable(targetOperatorId);
            Set<PlatformOperatorRole> currentRoles = currentRoles(targetOperatorId);
            Set<PlatformOperatorPermission> currentPermissions = currentPermissions(targetOperatorId);
            PlatformOperatorAccountStatus before = target.getStatus();
            target.suspend();
            revokeApprovalsAndSessions(targetOperatorId);
            auditWriter.appendManagement(new PlatformOperatorAuditWriter.ManagementEvent(
                    context, PlatformOperatorAuditAction.ACCOUNT_SUSPENDED,
                    PlatformOperatorAuditOutcome.SUCCESS, request.reason(), command.idempotencyKey(),
                    before, target.getStatus(), currentRoles, currentRoles, currentPermissions, currentPermissions));
            return success(HttpStatus.OK, target.getId(), PlatformOperatorAccountData.from(
                    target, currentRoles, currentPermissions));
        });
    }

    private AdminAuditContext authorize(
            PlatformOperatorPrincipal principal,
            PlatformOperatorPermission permission,
            String caseId,
            long caseVersion,
            AdminCommandPurpose purpose,
            String targetId,
            String approval,
            String correlationId
    ) {
        return highRiskGuard.authorize(new HighRiskCommandRequest(
                principal, permission, AdminCaseType.OPERATOR_MANAGEMENT, caseId, caseVersion,
                purpose, AdminTargetType.PLATFORM_OPERATOR_ACCOUNT, targetId, approval, correlationId));
    }

    private PlatformOperatorAccount lockActive(long operatorId) {
        return accounts.findByIdForUpdate(operatorId)
                .filter(account -> account.getStatus() == PlatformOperatorAccountStatus.ACTIVE)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID));
    }

    private Set<PlatformOperatorRole> currentRoles(long operatorId) {
        Set<PlatformOperatorRole> result = EnumSet.noneOf(PlatformOperatorRole.class);
        roles.findAllByPlatformOperatorAccountId(operatorId).forEach(grant -> result.add(grant.getRole()));
        return result;
    }

    private Set<PlatformOperatorPermission> currentPermissions(long operatorId) {
        Set<PlatformOperatorPermission> result = EnumSet.noneOf(PlatformOperatorPermission.class);
        permissions.findAllByPlatformOperatorAccountId(operatorId)
                .forEach(grant -> result.add(grant.getPermission()));
        return result;
    }

    private void revokeApprovalsAndSessions(long operatorId) {
        approvals.revokeUnconsumedByOperatorId(operatorId, clock.instant());
        try {
            sessions.revokeAll(operatorId);
        } catch (RuntimeException exception) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private ServiceException mapIntegrity(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        if (message != null && message.contains("uk_platform_operator_accounts_email")) {
            return new ServiceException(AdminAuthorizationErrorCode.DUPLICATE_OPERATOR_EMAIL);
        }
        if (message != null && message.contains("single_super_admin")) {
            return new ServiceException(AdminAuthorizationErrorCode.LAST_SUPER_ADMIN_REQUIRED);
        }
        return new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
    }

    private static <T> BusinessResult<T> success(HttpStatus status, Long id, T data) {
        return new BusinessResult<>(status.value(), "SUCCESS", "PLATFORM_OPERATOR_ACCOUNT",
                String.valueOf(id), data);
    }
}
