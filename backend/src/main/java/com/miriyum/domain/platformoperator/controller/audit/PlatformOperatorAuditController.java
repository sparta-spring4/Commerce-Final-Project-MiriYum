package com.miriyum.domain.platformoperator.controller.audit;

import com.miriyum.domain.platformoperator.dto.audit.PlatformOperatorAuditCorrectionRequest;
import com.miriyum.domain.platformoperator.dto.audit.PlatformOperatorAuditDetailData;
import com.miriyum.domain.platformoperator.dto.audit.PlatformOperatorAuditSearchData;
import com.miriyum.domain.platformoperator.dto.audit.PlatformOperatorAuditSearchRequest;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.RequestFingerprint;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@RequestMapping("/api/v1/platform-operators/audit-events")
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorAuditController {

    private final PlatformOperatorAuditService service;

    public PlatformOperatorAuditController(PlatformOperatorAuditService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PlatformOperatorAuditSearchData> search(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @RequestHeader("X-Admin-Case-Id") String caseId,
            @RequestHeader("X-Admin-Case-Version") long caseVersion,
            @RequestHeader("X-Admin-Reason-Code") PlatformOperatorAuditReason reason,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) PlatformOperatorAuditAction action,
            @RequestParam(required = false) PlatformOperatorAuditOutcome outcome,
            @RequestParam(required = false) String actorOperatorId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) Instant occurredFrom,
            @RequestParam(required = false) Instant occurredTo,
            @RequestParam(required = false) String originalEventKey
    ) {
        PlatformOperatorAuditSearchRequest request = new PlatformOperatorAuditSearchRequest(
                page, size, source, action, outcome, actorOperatorId, targetType, targetId,
                occurredFrom, occurredTo, originalEventKey);
        return ApiResponse.success("감사 사건을 조회했습니다.", service.search(
                principal, request, caseId, caseVersion, reason, correlation("search", principal.accountId())));
    }

    @GetMapping("/{eventKey}")
    public ApiResponse<PlatformOperatorAuditDetailData> detail(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String eventKey,
            @RequestHeader("X-Admin-Case-Id") String caseId,
            @RequestHeader("X-Admin-Case-Version") long caseVersion,
            @RequestHeader("X-Admin-Reason-Code") PlatformOperatorAuditReason reason
    ) {
        return ApiResponse.success("감사 사건 상세를 조회했습니다.", service.detail(
                principal, eventKey, caseId, caseVersion, reason,
                correlation("detail", principal.accountId())));
    }

    @PostMapping("/{eventKey}/corrections")
    public ResponseEntity<ApiResponse<JsonNode>> correct(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String eventKey,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawIdempotencyKey,
            @RequestHeader("X-Admin-Reauthentication") String approval,
            @RequestHeader("X-Admin-Case-Id") String caseId,
            @RequestHeader("X-Admin-Case-Version") long caseVersion,
            @Valid @RequestBody PlatformOperatorAuditCorrectionRequest request
    ) {
        IdempotencyKey key = IdempotencyKey.parse(rawIdempotencyKey);
        String canonical = "POST /api/v1/platform-operators/audit-events/" + eventKey + "/corrections"
                + "\nreason=" + request.reason()
                + "\naction=" + request.correctedAction()
                + "\noutcome=" + request.correctedOutcome()
                + "\ntargetType=" + request.correctedTargetType()
                + "\ntargetId=" + request.correctedTargetId()
                + "\ncorrectedReason=" + request.correctedReason();
        IdempotencyCommand command = new IdempotencyCommand(
                "platform-operator", principal.accountId(), "AUDIT_CORRECTION",
                key.value(), RequestFingerprint.of(canonical));
        IdempotentOutcome outcome = service.correct(
                command, principal, eventKey, request, caseId, caseVersion, approval,
                "platform-operator:" + key.value());
        return ResponseEntity.status(outcome.httpStatus())
                .body(ApiResponse.success("감사 보정 사건을 생성했습니다.", outcome.data()));
    }

    private static String correlation(String operation, long operatorId) {
        return "audit-" + operation + ":" + operatorId + ":" + UUID.randomUUID();
    }
}
