package com.miriyum.domain.schedule.controller.storeoperator;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.schedule.closure.dto.storeoperator.*;
import com.miriyum.domain.schedule.closure.service.StoreClosureCommandFacade;
import com.miriyum.domain.schedule.dto.storeoperator.SchedulePublicationCancellationRequest;
import com.miriyum.domain.schedule.dto.storeoperator.SchedulePublicationRequest;
import com.miriyum.domain.schedule.service.ScheduleCommandResult;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/store-operator/stores/{storeId}")
@RequiredArgsConstructor
public class StoreClosureController {
    private final StoreClosureCommandFacade service;

    @PutMapping("/regular-closures")
    public ResponseEntity<ApiResponse<RegularClosureResponse>> createRegular(
            @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable @Positive long storeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody RegularClosureDraftRequest request) {
        return response(service.createRegularDraft(principal.accountId(), storeId, IdempotencyKey.parse(rawKey), request),
                "정기 휴무 초안이 저장되었습니다.");
    }

    @PostMapping("/regular-closures/{version}/publication")
    public ResponseEntity<ApiResponse<RegularClosureResponse>> publishRegular(
            @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable @Positive long storeId,
            @PathVariable @Positive long version,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody SchedulePublicationRequest request) {
        return response(service.publishRegular(principal.accountId(), storeId, version, IdempotencyKey.parse(rawKey), request),
                "정기 휴무 게시 요청이 처리되었습니다.");
    }

    @PostMapping("/regular-closures/{version}/publication-cancellation")
    public ResponseEntity<ApiResponse<RegularClosureResponse>> cancelRegular(
            @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable @Positive long storeId,
            @PathVariable @Positive long version,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody SchedulePublicationCancellationRequest request) {
        return response(service.cancelRegular(principal.accountId(), storeId, version, IdempotencyKey.parse(rawKey), request),
                "정기 휴무 예약 게시가 취소되었습니다.");
    }

    @PostMapping("/temporary-closures")
    public ResponseEntity<ApiResponse<TemporaryClosureResponse>> createTemporary(
            @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable @Positive long storeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody TemporaryClosureCreateRequest request) {
        return response(service.createTemporary(principal.accountId(), storeId, IdempotencyKey.parse(rawKey), request),
                "임시 휴무가 등록되었습니다.");
    }

    @PutMapping("/temporary-closures/{closureId}/end-at")
    public ResponseEntity<ApiResponse<TemporaryClosureResponse>> changeTemporaryEnd(
            @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable @Positive long storeId,
            @PathVariable @Positive long closureId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody TemporaryClosureEndAtRequest request) {
        return response(service.changeTemporaryEnd(principal.accountId(), storeId, closureId,
                IdempotencyKey.parse(rawKey), request), "임시 휴무 종료 시각이 변경되었습니다.");
    }

    @PostMapping("/temporary-closures/{closureId}/cancellation")
    public ResponseEntity<ApiResponse<TemporaryClosureResponse>> cancelTemporary(
            @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable @Positive long storeId,
            @PathVariable @Positive long closureId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody TemporaryClosureCancellationRequest request) {
        return response(service.cancelTemporary(principal.accountId(), storeId, closureId,
                IdempotencyKey.parse(rawKey), request), "임시 휴무가 취소되었습니다.");
    }

    private <T> ResponseEntity<ApiResponse<T>> response(ScheduleCommandResult<T> result, String message) {
        return ResponseEntity.status(result.httpStatus()).body(ApiResponse.success(message, result.data()));
    }
}
