package com.miriyum.domain.store.controller.storeoperator;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ApplicationData;
import com.miriyum.domain.store.onboarding.service.StoreOnboardingQueryService;
import com.miriyum.domain.store.onboarding.service.StoreOnboardingSubmissionService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/store-operators/onboarding-applications")
@RequiredArgsConstructor
public class StoreOnboardingApplicationController {

    private final StoreOnboardingSubmissionService submissions;
    private final StoreOnboardingQueryService queries;

    @GetMapping("/{applicationId}")
    public ApiResponse<ApplicationData> getOwn(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long applicationId
    ) {
        return ApiResponse.success(
                "입점 신청을 조회했습니다.", queries.getOwn(principal.accountId(), applicationId));
    }

    @PostMapping(value = "/{applicationId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<JsonNode>> supplement(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long applicationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestPart("application") StoreCreateRequest request,
            @RequestPart("businessRegistrationEvidence") MultipartFile evidence
    ) {
        var result = submissions.supplement(
                principal.accountId(), applicationId, IdempotencyKey.parse(rawKey), request, evidence);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("입점 신청 보완본이 접수되었습니다.", result.data()));
    }
}
