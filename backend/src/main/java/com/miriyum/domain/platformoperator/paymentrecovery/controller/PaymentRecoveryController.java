package com.miriyum.domain.platformoperator.paymentrecovery.controller;

import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.ApprovalRequest;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.AssignmentRequest;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.ClosureRequest;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.ProposalRequest;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.RequeryRequest;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryResponses.CaseDetail;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryResponses.CasePage;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.CaseStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.service.PaymentRecoveryCommandService;
import com.miriyum.domain.platformoperator.paymentrecovery.service.PaymentRecoveryQueryService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.RequestFingerprint;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@Validated
@RequestMapping("/api/v1/platform-operators/payment-recovery-cases")
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PaymentRecoveryController {
    private final PaymentRecoveryQueryService queries;
    private final PaymentRecoveryCommandService commands;

    public PaymentRecoveryController(
            PaymentRecoveryQueryService queries, PaymentRecoveryCommandService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @GetMapping
    public ApiResponse<CasePage> list(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @RequestParam(required = false) CaseStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success("결제 복구 사건을 조회했습니다.",
                queries.list(principal, status, page, size));
    }

    @GetMapping("/{caseId}")
    public ApiResponse<CaseDetail> detail(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String caseId) {
        requireUuid(caseId);
        return ApiResponse.success("결제 복구 사건을 조회했습니다.",
                queries.detail(principal, caseId));
    }

    @PostMapping("/{caseId}/assignments")
    public ResponseEntity<ApiResponse<JsonNode>> assign(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String caseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @RequestHeader("X-Admin-Reauthentication") String approval,
            @RequestHeader("X-Correlation-Id") String correlationId,
            @Valid @RequestBody AssignmentRequest request) {
        IdempotencyKey key = key(rawKey);
        return response(commands.assign(command(principal, "PAYMENT_RECOVERY_ASSIGN", key,
                        "POST /api/v1/platform-operators/payment-recovery-cases/" + requireUuid(caseId)
                                + "/assignments\nexpectedCaseVersion=" + request.expectedCaseVersion()),
                principal, caseId, request, approval, correlationId), "결제 복구 사건을 배정했습니다.");
    }

    @PostMapping("/{caseId}/requeries")
    public ResponseEntity<ApiResponse<JsonNode>> requery(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal, @PathVariable String caseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @RequestHeader("X-Admin-Reauthentication") String approval,
            @RequestHeader("X-Correlation-Id") String correlationId,
            @Valid @RequestBody RequeryRequest request) {
        IdempotencyKey key = key(rawKey);
        String canonical = versions("POST /api/v1/platform-operators/payment-recovery-cases/"
                + requireUuid(caseId) + "/requeries", request.expectedCaseVersion(),
                request.expectedHandoffVersion(), request.expectedPaymentVersion(),
                request.expectedRecoveryVersion());
        return response(commands.requery(command(principal, "PAYMENT_RECOVERY_REQUERY", key, canonical),
                principal, caseId, request, approval, correlationId), "결제 결과 재조회를 예약했습니다.");
    }

    @PostMapping("/{caseId}/proposals")
    public ResponseEntity<ApiResponse<JsonNode>> propose(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal, @PathVariable String caseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @RequestHeader("X-Admin-Reauthentication") String approval,
            @RequestHeader("X-Correlation-Id") String correlationId,
            @Valid @RequestBody ProposalRequest request) {
        IdempotencyKey key = key(rawKey);
        String canonical = versions("POST /api/v1/platform-operators/payment-recovery-cases/"
                + requireUuid(caseId) + "/proposals\naction=" + request.action(),
                request.expectedCaseVersion(), request.expectedHandoffVersion(),
                request.expectedPaymentVersion(), request.expectedRecoveryVersion());
        return response(commands.propose(command(principal, "PAYMENT_RECOVERY_PROPOSE", key, canonical),
                principal, caseId, request, approval, correlationId), "결제 복구 제안을 생성했습니다.");
    }

    @PostMapping("/{caseId}/proposals/{proposalVersion}/approvals")
    public ResponseEntity<ApiResponse<JsonNode>> approve(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String caseId, @PathVariable @Min(1) long proposalVersion,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @RequestHeader("X-Admin-Reauthentication") String approval,
            @RequestHeader("X-Correlation-Id") String correlationId,
            @Valid @RequestBody ApprovalRequest request) {
        IdempotencyKey key = key(rawKey);
        String canonical = "POST /api/v1/platform-operators/payment-recovery-cases/"
                + requireUuid(caseId) + "/proposals/" + proposalVersion + "/approvals"
                + "\nexpectedCaseVersion=" + request.expectedCaseVersion()
                + "\nexpectedProposalVersion=" + request.expectedProposalVersion();
        return response(commands.approve(command(principal, "PAYMENT_RECOVERY_APPROVE", key, canonical),
                principal, caseId, proposalVersion, request, approval, correlationId),
                "결제 복구 제안을 승인했습니다.");
    }

    @PostMapping("/{caseId}/failed-unresolved-closures")
    public ResponseEntity<ApiResponse<JsonNode>> close(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal, @PathVariable String caseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @RequestHeader("X-Admin-Reauthentication") String approval,
            @RequestHeader("X-Correlation-Id") String correlationId,
            @Valid @RequestBody ClosureRequest request) {
        IdempotencyKey key = key(rawKey);
        String canonical = "POST /api/v1/platform-operators/payment-recovery-cases/"
                + requireUuid(caseId) + "/failed-unresolved-closures\nexpectedCaseVersion="
                + request.expectedCaseVersion();
        return response(commands.closeUnresolved(
                command(principal, "PAYMENT_RECOVERY_CLOSE_UNRESOLVED", key, canonical),
                principal, caseId, request, approval, correlationId), "결제 복구 사건을 미해결 종결했습니다.");
    }

    private static IdempotencyCommand command(
            PlatformOperatorPrincipal principal, String type, IdempotencyKey key, String canonical) {
        return new IdempotencyCommand("platform-operator", principal.accountId(), type,
                key.value(), RequestFingerprint.of(canonical));
    }

    private static String versions(String prefix, long caseVersion, long handoffVersion,
                                   long paymentVersion, long recoveryVersion) {
        return prefix + "\nexpectedCaseVersion=" + caseVersion
                + "\nexpectedHandoffVersion=" + handoffVersion
                + "\nexpectedPaymentVersion=" + paymentVersion
                + "\nexpectedRecoveryVersion=" + recoveryVersion;
    }

    private static IdempotencyKey key(String raw) { return IdempotencyKey.parse(raw); }

    private static String requireUuid(String value) {
        try { return UUID.fromString(value).toString(); }
        catch (RuntimeException invalid) {
            throw new com.miriyum.global.exception.ServiceException(
                    com.miriyum.global.exception.CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private static ResponseEntity<ApiResponse<JsonNode>> response(
            IdempotentOutcome outcome, String message) {
        return ResponseEntity.status(outcome.httpStatus())
                .body(ApiResponse.success(message, outcome.data()));
    }
}
