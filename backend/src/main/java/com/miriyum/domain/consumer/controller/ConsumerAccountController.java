package com.miriyum.domain.consumer.controller;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.consumer.dto.request.ConsumerAccountUpdateRequest;
import com.miriyum.domain.consumer.dto.response.ConsumerAccountResponse;
import com.miriyum.domain.consumer.service.ConsumerAccountService;
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
 * 일반 사용자 본인 정보 조회·수정(마이페이지) API다. 예약 내역 조회는 예약 도메인의
 * {@code ReservationService} 공개 조회 계약이 아직 없어 이번 구현에서 BLOCKED로 보류한다.
 */
@RestController
@RequestMapping("/api/v1/consumer-accounts")
@RequiredArgsConstructor
public class ConsumerAccountController {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    private final ConsumerAccountService consumerAccountService;

    @GetMapping("/me")
    public ApiResponse<ConsumerAccountResponse> getMe(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.success("조회했습니다.", consumerAccountService.getMe(principal.accountId()));
    }

    @PatchMapping("/me")
    public ApiResponse<ConsumerAccountResponse> updateMe(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ConsumerAccountUpdateRequest request
    ) {
        requireIdempotencyKey(idempotencyKey);
        return ApiResponse.success("수정했습니다.", consumerAccountService.updateName(principal.accountId(), request));
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
