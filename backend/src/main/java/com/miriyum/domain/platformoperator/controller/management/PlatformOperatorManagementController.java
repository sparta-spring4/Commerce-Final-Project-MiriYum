package com.miriyum.domain.platformoperator.controller.management;

import com.miriyum.domain.platformoperator.dto.management.PlatformOperatorAccountCreateRequest;
import com.miriyum.domain.platformoperator.dto.management.PlatformOperatorAuthorityReplaceRequest;
import com.miriyum.domain.platformoperator.dto.management.PlatformOperatorSuspensionRequest;
import com.miriyum.domain.platformoperator.service.PlatformOperatorManagementService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.RequestFingerprint;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.Collection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/platform-operators/accounts")
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorManagementController {

    private final PlatformOperatorManagementService service;

    public PlatformOperatorManagementController(PlatformOperatorManagementService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<JsonNode>> create(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawIdempotencyKey,
            @RequestHeader("X-Admin-Reauthentication") String approval,
            @RequestHeader("X-Admin-Case-Id") String caseId,
            @RequestHeader("X-Admin-Case-Version") long caseVersion,
            @Valid @RequestBody PlatformOperatorAccountCreateRequest request
    ) {
        IdempotencyKey key = IdempotencyKey.parse(rawIdempotencyKey);
        IdempotencyCommand command = command(principal, "OPERATOR_CREATE", key,
                "POST /api/v1/platform-operators/accounts"
                        + "\nprovisioningId=" + request.provisioningId()
                        + "\nemail=" + request.email().strip().toLowerCase(java.util.Locale.ROOT)
                        + "\ndisplayName=" + request.displayName().strip()
                        + "\nroles=" + sorted(request.roles())
                        + "\npermissions=" + sorted(request.directPermissions())
                        + "\nreason=" + request.reason());
        IdempotentOutcome outcome = service.createAccount(
                command, principal, request, caseId, caseVersion, approval, correlation(key));
        return response(outcome, "운영자 계정을 생성했습니다.");
    }

    @PutMapping("/{operatorId}/authority")
    public ResponseEntity<ApiResponse<JsonNode>> replaceAuthority(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String operatorId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawIdempotencyKey,
            @RequestHeader("X-Admin-Reauthentication") String approval,
            @RequestHeader("X-Admin-Case-Id") String caseId,
            @RequestHeader("X-Admin-Case-Version") long caseVersion,
            @Valid @RequestBody PlatformOperatorAuthorityReplaceRequest request
    ) {
        long targetId = numericId(operatorId);
        IdempotencyKey key = IdempotencyKey.parse(rawIdempotencyKey);
        IdempotencyCommand command = command(principal, "OPERATOR_AUTHORITY_REPLACE", key,
                "PUT /api/v1/platform-operators/accounts/" + targetId + "/authority"
                        + "\nroles=" + sorted(request.roles())
                        + "\npermissions=" + sorted(request.directPermissions())
                        + "\nreason=" + request.reason());
        IdempotentOutcome outcome = service.replaceAuthority(
                command, principal, targetId, request, caseId, caseVersion, approval, correlation(key));
        return response(outcome, "운영자 권한을 변경했습니다.");
    }

    @PutMapping("/{operatorId}/suspension")
    public ResponseEntity<ApiResponse<JsonNode>> suspend(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String operatorId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawIdempotencyKey,
            @RequestHeader("X-Admin-Reauthentication") String approval,
            @RequestHeader("X-Admin-Case-Id") String caseId,
            @RequestHeader("X-Admin-Case-Version") long caseVersion,
            @Valid @RequestBody PlatformOperatorSuspensionRequest request
    ) {
        long targetId = numericId(operatorId);
        IdempotencyKey key = IdempotencyKey.parse(rawIdempotencyKey);
        IdempotencyCommand command = command(principal, "OPERATOR_SUSPEND", key,
                "PUT /api/v1/platform-operators/accounts/" + targetId + "/suspension"
                        + "\nreason=" + request.reason());
        IdempotentOutcome outcome = service.suspendAccount(
                command, principal, targetId, request, caseId, caseVersion, approval, correlation(key));
        return response(outcome, "운영자 계정을 중지했습니다.");
    }

    private static IdempotencyCommand command(
            PlatformOperatorPrincipal principal,
            String commandType,
            IdempotencyKey key,
            String canonicalInput
    ) {
        return new IdempotencyCommand("platform-operator", principal.accountId(), commandType,
                key.value(), RequestFingerprint.of(canonicalInput));
    }

    private static String correlation(IdempotencyKey key) {
        return "platform-operator:" + key.value();
    }

    private static String sorted(Collection<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().toList().toString();
    }

    private static long numericId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException exception) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private static ResponseEntity<ApiResponse<JsonNode>> response(IdempotentOutcome outcome, String message) {
        return ResponseEntity.status(outcome.httpStatus()).body(ApiResponse.success(message, outcome.data()));
    }
}
