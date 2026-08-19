package com.miriyum.domain.platformoperator.controller.membersupport;

import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSearchCriteria;
import com.miriyum.domain.auth.membersupport.MemberStatus;
import com.miriyum.domain.platformoperator.dto.membersupport.MemberSupportResponses.MemberPageResponse;
import com.miriyum.domain.platformoperator.dto.membersupport.MemberSupportResponses.MemberResponse;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportAssignmentService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportQueryService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportDecisionService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSanctionCommandService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSanctionService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportCaseQueryService;
import com.miriyum.domain.platformoperator.service.membersupport.PendingMemberSanctionQueryService;
import com.miriyum.domain.platformoperator.dto.membersupport.MemberSupportResponses.CaseResponse;
import com.miriyum.domain.platformoperator.dto.membersupport.MemberSupportResponses.CasePageResponse;
import com.miriyum.domain.platformoperator.dto.membersupport.MemberSupportResponses.PendingSanctionApprovalPageResponse;
import com.miriyum.domain.platformoperator.dto.membersupport.PlatformMemberSupportRequests.CaseDecisionRequest;
import com.miriyum.domain.platformoperator.dto.membersupport.PlatformMemberSupportRequests.SanctionRequest;
import com.miriyum.domain.platformoperator.dto.membersupport.PlatformMemberSupportRequests.AdditionalApprovalRequest;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.response.ApiResponse;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform-operators")
@ConditionalOnExpression("'${miriyum.platform-operator.enabled:false}' == 'true' and "
        + "'${miriyum.member-support.enabled:false}' == 'true'")
public class PlatformOperatorMemberSupportController {
    private final MemberSupportQueryService queries;
    private final MemberSupportAssignmentService assignments;
    private final MemberSupportDecisionService decisions;
    private final MemberSanctionCommandService sanctionCommands;
    private final MemberSanctionService sanctions;
    private final MemberSupportCaseQueryService caseQueries;
    private final PendingMemberSanctionQueryService pendingSanctions;

    public PlatformOperatorMemberSupportController(MemberSupportQueryService queries,
                                                   MemberSupportAssignmentService assignments,
                                                   MemberSupportDecisionService decisions,
                                                   MemberSanctionCommandService sanctionCommands,
                                                   MemberSanctionService sanctions,
                                                   MemberSupportCaseQueryService caseQueries,
                                                   PendingMemberSanctionQueryService pendingSanctions) {
        this.queries = queries;
        this.assignments = assignments;
        this.decisions = decisions;
        this.sanctionCommands = sanctionCommands;
        this.sanctions = sanctions;
        this.caseQueries = caseQueries;
        this.pendingSanctions = pendingSanctions;
    }

    @GetMapping("/members")
    public ApiResponse<MemberPageResponse> listMembers(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @RequestParam(required = false) MemberAccountType accountType,
            @RequestParam(required = false) MemberStatus status,
            @RequestParam(required = false) Instant joinedFrom,
            @RequestParam(required = false) Instant joinedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("회원을 조회했습니다.", queries.list(
                principal, accountType, status, new MemberSearchCriteria(joinedFrom, joinedTo), page, size));
    }

    @GetMapping("/members/{accountType}/{accountId}")
    public ApiResponse<MemberResponse> getMember(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable MemberAccountType accountType,
            @PathVariable long accountId) {
        return ApiResponse.success("회원을 조회했습니다.", queries.get(principal, accountType, accountId));
    }

    @GetMapping("/member-support-cases")
    public ApiResponse<CasePageResponse> listCases(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("사건을 조회했습니다.", caseQueries.list(principal, page, size));
    }

    @GetMapping("/member-support-cases/{caseId}")
    public ApiResponse<CaseResponse> getCase(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String caseId) {
        return ApiResponse.success("사건을 조회했습니다.", caseQueries.get(principal, caseId));
    }

    @PostMapping("/member-support-cases/{caseId}/assignments")
    public ApiResponse<Void> assign(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String caseId) {
        assignments.assign(principal, caseId);
        return ApiResponse.success("사건을 배정했습니다.", null);
    }

    @PostMapping("/member-support-cases/{caseId}/decisions")
    public ApiResponse<Void> decide(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String caseId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("X-Admin-Reauthentication") String approval,
            @RequestHeader("Idempotency-Key") String correlationId,
            @Valid @RequestBody CaseDecisionRequest request) {
        decisions.decide(principal, caseId, version(ifMatch), approval, correlationId, request);
        return ApiResponse.success("사건을 결정했습니다.", null);
    }

    @PostMapping("/members/{accountType}/{accountId}/sanctions")
    public ApiResponse<Void> sanction(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable MemberAccountType accountType,
            @PathVariable long accountId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("X-Admin-Reauthentication") String approval,
            @RequestHeader("Idempotency-Key") String correlationId,
            @Valid @RequestBody SanctionRequest request) {
        sanctionCommands.createAndApply(principal, accountType, accountId, version(ifMatch),
                request.level(), request.restrictedFeatures(), request.reasonCode(), request.policyVersion(),
                approval, correlationId);
        return ApiResponse.success("제재를 처리했습니다.", null);
    }

    @PostMapping("/member-sanctions/{sanctionId}/additional-approvals")
    public ApiResponse<Void> approvePermanent(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String sanctionId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("X-Admin-Reauthentication") String approval,
            @RequestHeader("Idempotency-Key") String correlationId,
            @Valid @RequestBody AdditionalApprovalRequest request) {
        sanctions.approvePermanent(principal, sanctionId, version(ifMatch),
                approval, correlationId, request.reasonCode());
        return ApiResponse.success("영구 정지를 승인했습니다.", null);
    }

    @GetMapping("/member-sanctions/pending-additional-approvals")
    public ApiResponse<PendingSanctionApprovalPageResponse> pendingAdditionalApprovals(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("추가 승인 대기 제재를 조회했습니다.",
                pendingSanctions.list(principal, page, size));
    }

    private long version(String header) {
        try {
            return Long.parseLong(header.replace("\"", ""));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid If-Match");
        }
    }
}
