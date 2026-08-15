package com.miriyum.domain.menu.controller.storeoperator;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.menu.dto.storeoperator.RepresentativeMenuReplaceRequest;
import com.miriyum.domain.menu.dto.storeoperator.RepresentativeMenuSettingResponse;
import com.miriyum.domain.menu.service.RepresentativeMenuCommandResult;
import com.miriyum.domain.menu.service.RepresentativeMenuService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/store-operators/stores/{storeId}/representative-menus")
@RequiredArgsConstructor
public class RepresentativeMenuController {

    private final RepresentativeMenuService service;

    @GetMapping
    public ApiResponse<RepresentativeMenuSettingResponse> get(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId
    ) {
        return ApiResponse.success(
                "대표 메뉴 설정을 조회했습니다.",
                service.get(principal.accountId(), storeId));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<RepresentativeMenuSettingResponse>> replace(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody RepresentativeMenuReplaceRequest request
    ) {
        RepresentativeMenuCommandResult result = service.replace(
                principal.accountId(), storeId, IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(
                        "대표 메뉴 설정을 변경했습니다.", result.data()));
    }
}
