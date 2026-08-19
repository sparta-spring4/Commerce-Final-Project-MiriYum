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
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.CaseStatus;
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
        return new CasePage(result.map(value -> CaseSummary.from(value, null)).getContent(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public CaseDetail detail(PlatformOperatorPrincipal principal, String caseId) {
        requirePermission(principal);
        var recoveryCase = cases.findByPublicId(caseId)
                .orElseThrow(() -> new ServiceException(PaymentRecoveryErrorCode.RECOVERY_CASE_NOT_FOUND));
        assignments.verify(new AdminCaseAssignmentRequest(AdminCaseType.PAYMENT_RECOVERY,
                caseId, recoveryCase.getCaseVersion(), principal.accountId()));
        var proposalData = proposals.findByCasePublicIdOrderByProposalVersionAsc(caseId).stream()
                .map(proposal -> ProposalData.from(proposal,
                        approvals.findByCasePublicIdAndProposalVersion(caseId, proposal.getProposalVersion())
                                .map(value -> value.getApproverPlatformOperatorAccountId()).orElse(null)))
                .toList();
        var executionData = executions.findByCasePublicIdOrderByCreatedAtAsc(caseId).stream()
                .map(ExecutionData::from).toList();
        return new CaseDetail(CaseSummary.from(recoveryCase, principal.accountId()),
                proposalData, executionData);
    }

    private void requirePermission(PlatformOperatorPrincipal principal) {
        var authority = authorities.requireCurrentAuthority(
                principal.accountId(), principal.authorityVersion());
        if (!authority.permissions().contains(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE)) {
            throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
        }
    }
}
