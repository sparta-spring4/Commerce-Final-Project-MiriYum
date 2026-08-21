package com.miriyum.domain.store.controller.storeoperator;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.store.dto.storeoperator.ManagedStoreResponse;
import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.dto.storeoperator.StoreUpdateRequest;
import com.miriyum.domain.store.service.StoreCommandResult;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.domain.store.onboarding.service.StoreOnboardingSubmissionService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/store-operators/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;
    private final StoreOnboardingSubmissionService onboardingSubmissionService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<JsonNode>> create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestPart("application") StoreCreateRequest request,
            @RequestPart("businessRegistrationEvidence") MultipartFile evidence
    ) {
        var result = onboardingSubmissionService.submit(
                principal.accountId(),
                IdempotencyKey.parse(rawKey),
                request,
                evidence);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("입점 신청이 접수되었습니다.", result.data()));
    }

    @GetMapping("/{storeId}")
    public ApiResponse<ManagedStoreResponse> get(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId
    ) {
        return ApiResponse.success(
                "조회되었습니다.",
                storeService.getManagedStore(principal.accountId(), storeId));
    }

    @GetMapping
    public ApiResponse<List<ManagedStoreResponse>> list(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(
                "조회되었습니다.",
                storeService.getManagedStores(principal.accountId()));
    }

    @PatchMapping("/{storeId}")
    public ResponseEntity<ApiResponse<ManagedStoreResponse>> update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody StoreUpdateRequest request
    ) {
        StoreCommandResult result = storeService.update(
                principal.accountId(),
                storeId,
                IdempotencyKey.parse(rawKey),
                request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("수정되었습니다.", result.data()));
    }
}
