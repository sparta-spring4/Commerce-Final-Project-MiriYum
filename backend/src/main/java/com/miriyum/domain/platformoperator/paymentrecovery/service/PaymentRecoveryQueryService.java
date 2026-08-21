package com.miriyum.domain.platformoperator.paymentrecovery.service;

import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryResponses.CaseDetail;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryResponses.CasePage;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryResponses.CaseSummary;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryResponses.ExecutionData;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryResponses.ProposalData;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryResponses.PendingApprovalPage;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.CaseStatus;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.paymentrecovery.exception.PaymentRecoveryErrorCode;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryApprovalRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryCaseRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryExecutionRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryProposalRepository;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentVerifier;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PaymentRecoveryQueryService {
    private final PaymentRecoveryCaseRepository cases;
    private final PaymentRecoveryProposalRepository proposals;
    private final PaymentRecoveryApprovalRepository approvals;
    private final PaymentRecoveryExecutionRepository executions;
    private final OperatorAuthorityReader authorities;
    private final AdminCaseAssignmentVerifier assignments;

    public PaymentRecoveryQueryService(
            PaymentRecoveryCaseRepository cases, PaymentRecoveryProposalRepository proposals,
            PaymentRecoveryApprovalRepository approvals, PaymentRecoveryExecutionRepository executions,
            OperatorAuthorityReader authorities, AdminCaseAssignmentVerifier assignments) {
        this.cases = cases;
        this.proposals = proposals;
        this.approvals = approvals;
        this.executions = executions;
        this.authorities = authorities;
        this.assignments = assignments;
    }

    @Transactional(readOnly = true)
    public CasePage list(PlatformOperatorPrincipal principal, CaseStatus status, int page, int size) {
        requirePermission(principal);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase> result =
                status == null ? cases.findAll(pageable) : cases.findByStatus(status, pageable);
        return new CasePage(result.map(value -> CaseSummary.from(value,
                        assignments.findActiveOperator(AdminCaseType.PAYMENT_RECOVERY,
                                value.getPublicId(), value.getCaseVersion()).orElse(null)))
                        .getContent(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public CaseDetail detail(PlatformOperatorPrincipal principal, String caseId) {
        var authority = authorities.requireCurrentAuthority(
                principal.accountId(), principal.authorityVersion());
        var recoveryCase = cases.findByPublicId(caseId)
                .orElseThrow(() -> new ServiceException(PaymentRecoveryErrorCode.RECOVERY_CASE_NOT_FOUND));
        if (!isEligibleAdditionalApprover(principal, authority, recoveryCase)) {
            if (!authority.permissions().contains(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE)) {
                throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
            }
            assignments.verify(new AdminCaseAssignmentRequest(
                    AdminCaseType.PAYMENT_RECOVERY, caseId,
                    recoveryCase.getCaseVersion(), principal.accountId()));
        }
        return detail(recoveryCase);
    }

    @Transactional(readOnly = true)
    public PendingApprovalPage pendingAdditionalApprovals(
            PlatformOperatorPrincipal principal, int page, int size) {
        var authority = authorities.requireCurrentAuthority(
                principal.accountId(), principal.authorityVersion());
        requireAdditionalApprovalAuthority(authority);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "updatedAt"));
        var result = cases.findPendingAdditionalApprovals(
                CaseStatus.ADDITIONAL_APPROVAL_PENDING, principal.accountId(), pageable);
        return new PendingApprovalPage(result.map(this::detail).getContent(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    private CaseDetail detail(
            com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase recoveryCase) {
        String caseId = recoveryCase.getPublicId();
        var proposalData = proposals.findByCasePublicIdOrderByProposalVersionAsc(caseId).stream()
                .map(proposal -> ProposalData.from(proposal,
                        approvals.findByCasePublicIdAndProposalVersion(caseId, proposal.getProposalVersion())
                                .map(value -> value.getApproverPlatformOperatorAccountId()).orElse(null)))
                .toList();
        var executionData = executions.findByCasePublicIdOrderByCreatedAtAsc(caseId).stream()
                .map(ExecutionData::from).toList();
        Long assignedOperatorId = assignments.findActiveOperator(AdminCaseType.PAYMENT_RECOVERY,
                caseId, recoveryCase.getCaseVersion()).orElse(null);
        return new CaseDetail(CaseSummary.from(recoveryCase, assignedOperatorId),
                proposalData, executionData);
    }

    private boolean isEligibleAdditionalApprover(
            PlatformOperatorPrincipal principal,
            com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority authority,
            com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase recoveryCase) {
        if (!hasAdditionalApprovalAuthority(authority)
                || recoveryCase.getStatus() != CaseStatus.ADDITIONAL_APPROVAL_PENDING) {
            return false;
        }
        return proposals.findByCasePublicIdAndProposalVersion(
                        recoveryCase.getPublicId(), recoveryCase.getCurrentProposalVersion())
                .filter(proposal -> proposal.getRequesterPlatformOperatorAccountId() != principal.accountId())
                .isPresent();
    }

    private static void requireAdditionalApprovalAuthority(
            com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority authority) {
        if (!hasAdditionalApprovalAuthority(authority)) {
            throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
        }
    }

    private static boolean hasAdditionalApprovalAuthority(
            com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority authority) {
        return authority.roles().contains(PlatformOperatorRole.SUPER_ADMIN)
                && authority.permissions().contains(
                        PlatformOperatorPermission.PAYMENT_RECOVERY_HIGH_VALUE_APPROVE);
    }

    private void requirePermission(PlatformOperatorPrincipal principal) {
        var authority = authorities.requireCurrentAuthority(
                principal.accountId(), principal.authorityVersion());
        if (!authority.permissions().contains(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE)) {
            throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
        }
    }
}
