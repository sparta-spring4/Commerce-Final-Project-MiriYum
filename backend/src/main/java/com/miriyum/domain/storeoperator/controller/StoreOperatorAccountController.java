package com.miriyum.domain.storeoperator.controller;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.storeoperator.dto.request.StoreOperatorAccountUpdateRequest;
import com.miriyum.domain.storeoperator.dto.response.StoreOperatorAccountResponse;
import com.miriyum.domain.storeoperator.service.StoreOperatorAccountService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매장 운영자 본인 정보 조회·수정(마이페이지) API다.
 */
@RestController
@RequestMapping("/api/v1/store-operator-accounts")
@RequiredArgsConstructor
public class StoreOperatorAccountController {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    private final StoreOperatorAccountService storeOperatorAccountService;

    @GetMapping("/me")
    public ApiResponse<StoreOperatorAccountResponse> getMe(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.success("조회했습니다.", storeOperatorAccountService.getMe(principal.accountId()));
    }

    @PatchMapping("/me")
    public ApiResponse<StoreOperatorAccountResponse> updateMe(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody StoreOperatorAccountUpdateRequest request
    ) {
        requireIdempotencyKey(idempotencyKey);
        return ApiResponse.success(
                "수정했습니다.", storeOperatorAccountService.updateDisplayName(principal.accountId(), request));
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (!IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new ServiceException(CommonErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
    }
}
