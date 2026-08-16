package com.miriyum.domain.reservation.waiting.controller.storeoperator;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.reservation.waiting.dto.*;
import com.miriyum.domain.reservation.waiting.service.WaitingSettingService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/store-operators/stores/{storeId}/waiting-settings")
@RequiredArgsConstructor
public class WaitingSettingStoreOperatorController {
    private final WaitingSettingService service;

    @GetMapping
    public ApiResponse<WaitingSettingSnapshot> get(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId) {
        return ApiResponse.success("웨이팅 설정을 조회했습니다.",
                service.get(principal.accountId(), storeId));
    }

    @GetMapping("/deactivation-impact")
    public ApiResponse<WaitingSettingDeactivationImpact> inspectDeactivation(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId) {
        return ApiResponse.success("웨이팅 비활성화 영향을 조회했습니다.",
                service.inspectDeactivation(principal.accountId(), storeId));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<Object>> replace(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody WaitingSettingUpdateRequest request) {
        WaitingSettingCommandResult result = service.replace(
                principal.accountId(), storeId, IdempotencyKey.parse(rawKey), request);
        String message = result.httpStatus() == 202
                ? "웨이팅 설정을 변경하고 종료 작업을 생성했습니다."
                : "웨이팅 설정을 변경했습니다.";
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(message, result.data()));
    }
}
