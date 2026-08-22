package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.dto.authorization.HighRiskCommandRequest;
import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorReauthenticationApprovalRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.exception.CommonErrorCode;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class HighRiskCommandGuard {
    private final PlatformOperatorAccountRepository accounts;
    private final OperatorAuthorityReader authorityReader;
    private final AdminCaseAssignmentVerifier assignmentVerifier;
    private final PlatformOperatorReauthenticationApprovalRepository approvals;
    private final ReauthenticationService reauthentication;
    private final Clock clock;

    public HighRiskCommandGuard(
            PlatformOperatorAccountRepository accounts,
            OperatorAuthorityReader authorityReader,
            AdminCaseAssignmentVerifier assignmentVerifier,
            PlatformOperatorReauthenticationApprovalRepository approvals,
            ReauthenticationService reauthentication,
            Clock clock
    ) {
        this.accounts = accounts;
        this.authorityReader = authorityReader;
        this.assignmentVerifier = assignmentVerifier;
        this.approvals = approvals;
        this.reauthentication = reauthentication;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AdminAuditContext authorize(HighRiskCommandRequest request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("high-risk authorization requires an active command transaction");
        }
        try {
            return authorizeAgainstStores(request, true);
        } catch (DataAccessException exception) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AdminAuditContext authorizeInitialPaymentRecoveryAssignment(
            HighRiskCommandRequest request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("high-risk authorization requires an active command transaction");
        }
        if (request.caseType() != AdminCaseType.PAYMENT_RECOVERY
                || request.purpose() != AdminCommandPurpose.PAYMENT_RECOVERY
                || request.targetType() != AdminTargetType.PAYMENT_RECOVERY_CASE
                || request.requiredPermission() != PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE
                || !request.caseId().equals(request.targetId())) {
            deny();
        }
        try {
            return authorizeAgainstStores(request, false);
        } catch (DataAccessException exception) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AdminAuditContext authorizeInitialOnboardingAssignment(
            HighRiskCommandRequest request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("high-risk authorization requires an active command transaction");
        }
        if (request.caseType() != AdminCaseType.ONBOARDING_REVIEW
                || request.purpose() != AdminCommandPurpose.ONBOARDING_ASSIGNMENT
                || request.targetType() != AdminTargetType.ONBOARDING_APPLICATION
                || request.requiredPermission() != PlatformOperatorPermission.ONBOARDING_REVIEW) {
            deny();
        }
        try {
            return authorizeAgainstStores(request, false);
        } catch (DataAccessException exception) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AdminAuditContext authorizeSecondaryPaymentRecoveryCommand(
            HighRiskCommandRequest request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("high-risk authorization requires an active command transaction");
        }
        if (request.caseType() != AdminCaseType.PAYMENT_RECOVERY
                || request.purpose() != AdminCommandPurpose.PAYMENT_RECOVERY
                || request.targetType() != AdminTargetType.PAYMENT_RECOVERY_CASE
                || request.requiredPermission()
                != PlatformOperatorPermission.PAYMENT_RECOVERY_HIGH_VALUE_APPROVE
                || !isSecondaryPaymentRecoveryTarget(request.caseId(), request.targetId())) {
            deny();
        }
        try {
            return authorizeAgainstStores(request, false);
        } catch (DataAccessException exception) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static boolean isSecondaryPaymentRecoveryTarget(String caseId, String targetId) {
        if (targetId.equals(caseId + ":closure")) return true;
        String proposalPrefix = caseId + ":proposal:";
        if (!targetId.startsWith(proposalPrefix)) return false;
        String proposalVersion = targetId.substring(proposalPrefix.length());
        return proposalVersion.matches("[1-9][0-9]*");
    }

    private AdminAuditContext authorizeAgainstStores(
            HighRiskCommandRequest request, boolean verifyAssignment) {
        PlatformOperatorAccount account = accounts.findByIdForUpdate(request.principal().accountId())
                .filter(candidate -> candidate.getStatus() == PlatformOperatorAccountStatus.ACTIVE)
                .filter(candidate -> candidate.getAuthorityVersion() == request.principal().authorityVersion())
                .filter(candidate -> candidate.getSessionVersion() == request.principal().sessionVersion())
                .orElseThrow(() -> new ServiceException(AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID));
        OperatorAuthority authority = authorityReader.requireCurrentAuthority(
                account.getId(), request.principal().authorityVersion());
        if (!authority.permissions().contains(request.requiredPermission())) deny();

        if (verifyAssignment) {
            assignmentVerifier.verify(new AdminCaseAssignmentRequest(
                    request.caseType(), request.caseId(), request.caseVersion(), account.getId()));
        }

        String digest = ReauthenticationService.sha256(request.approval());
        int consumed = approvals.consumeBoundApproval(
                digest,
                account.getId(),
                request.purpose().name(),
                request.targetType().name(),
                request.targetId(),
                reauthentication.sessionFingerprint(request.principal().sessionId()),
                authority.authorityVersion(),
                clock.instant());
        if (consumed != 1) deny();

        return new AdminAuditContext(
                account.getId(), authority.roles(), authority.permissions(), authority.authorityVersion(),
                request.caseType(), request.caseId(), request.caseVersion(), request.purpose(),
                request.targetType(), request.targetId(), digest, request.correlationId());
    }

    private static void deny() {
        throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
    }
}
