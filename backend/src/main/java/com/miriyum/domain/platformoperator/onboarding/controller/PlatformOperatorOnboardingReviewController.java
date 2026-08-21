package com.miriyum.domain.platformoperator.onboarding.controller;

import com.miriyum.domain.platformoperator.onboarding.dto.OnboardingReviewRequests.AssignmentRequest;
import com.miriyum.domain.platformoperator.onboarding.dto.OnboardingReviewRequests.DecisionRequest;
import com.miriyum.domain.platformoperator.onboarding.dto.OnboardingReviewRequests.ReassignmentRequest;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingReviewCommandService;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingEvidenceAccessService;
import com.miriyum.domain.platformoperator.onboarding.service.OnboardingReviewQueryService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ApplicationData;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewCaseDetail;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewCasePage;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewStatus;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewType;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
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

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/platform-operators/onboarding-review-cases")
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorOnboardingReviewController {

    private final OnboardingReviewQueryService queries;
    private final OnboardingReviewCommandService commands;
    private final OnboardingEvidenceAccessService evidence;

    @GetMapping
    public ApiResponse<ReviewCasePage> list(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @RequestParam(required = false) ReviewStatus status,
            @RequestParam(required = false) ReviewType type,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(
                "입점 심사 사건을 조회했습니다.", queries.list(principal, status, type, page, size));
    }

    @GetMapping("/{caseId}")
    public ApiResponse<ReviewCaseDetail> detail(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String caseId) {
        return ApiResponse.success(
                "입점 심사 사건을 조회했습니다.", queries.detail(principal, uuid(caseId)));
    }

    @PostMapping("/{caseId}/assignments")
    public ApiResponse<ReviewCaseDetail> assign(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String caseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @RequestHeader("X-Admin-Reauthentication") String approval,
            @RequestHeader("X-Correlation-Id") String correlationId,
            @Valid @RequestBody AssignmentRequest request) {
        return ApiResponse.success("입점 심사 사건을 배정했습니다.", commands.assign(
                principal, uuid(caseId), request, approval, correlationId, IdempotencyKey.parse(rawKey)));
    }

    @PostMapping("/{caseId}/reassignments")
    public ApiResponse<ReviewCaseDetail> reassign(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String caseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @RequestHeader("X-Admin-Reauthentication") String approval,
            @RequestHeader("X-Correlation-Id") String correlationId,
            @Valid @RequestBody ReassignmentRequest request) {
        return ApiResponse.success("입점 심사 사건을 재배정했습니다.", commands.reassign(
                principal, uuid(caseId), request, approval, correlationId, IdempotencyKey.parse(rawKey)));
    }

    @PostMapping("/{caseId}/decisions")
    public ApiResponse<ApplicationData> decide(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String caseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @RequestHeader("X-Admin-Reauthentication") String approval,
            @RequestHeader("X-Correlation-Id") String correlationId,
            @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.success("입점 심사 결정을 기록했습니다.", commands.decide(
                principal, uuid(caseId), request, approval, correlationId, IdempotencyKey.parse(rawKey)));
    }

    @GetMapping("/{caseId}/evidence")
    public ResponseEntity<byte[]> readEvidence(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @PathVariable String caseId,
            @RequestParam @Min(1) long expectedCaseVersion,
            @RequestHeader("X-Admin-Reauthentication") String approval,
            @RequestHeader("X-Correlation-Id") String correlationId) {
        var content = evidence.read(
                principal, uuid(caseId), expectedCaseVersion, approval, correlationId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(content.contentType()))
                .body(content.bytes());
    }

    private static String uuid(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (RuntimeException exception) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }
}
