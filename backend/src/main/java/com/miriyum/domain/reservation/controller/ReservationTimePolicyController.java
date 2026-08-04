package com.miriyum.domain.reservation.controller;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyDraftRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyPublicationCancellationRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimePolicyPublicationRequest;
import com.miriyum.domain.reservation.dto.response.ReservationTimePolicyResponse;
import com.miriyum.domain.reservation.service.ReservationTimePolicyCommandFacade;
import com.miriyum.domain.reservation.service.ReservationTimePolicyCommandResult;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/store-operator/stores/{storeId}/reservation-time-policies")
@RequiredArgsConstructor
public class ReservationTimePolicyController {

    private final ReservationTimePolicyCommandFacade commandFacade;

    @PutMapping
    public ResponseEntity<ApiResponse<ReservationTimePolicyResponse>> createDraft(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody ReservationTimePolicyDraftRequest request
    ) {
        ReservationTimePolicyCommandResult<ReservationTimePolicyResponse> result =
                commandFacade.createDraft(
                        principal.accountId(),
                        storeId,
                        IdempotencyKey.parse(rawKey),
                        request
                );
        return response(result, "예약 시간 정책 초안이 저장되었습니다.");
    }

    @PostMapping("/{version}/publication")
    public ResponseEntity<ApiResponse<ReservationTimePolicyResponse>> publish(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @PathVariable @Positive long version,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody ReservationTimePolicyPublicationRequest request
    ) {
        ReservationTimePolicyCommandResult<ReservationTimePolicyResponse> result =
                commandFacade.publish(
                        principal.accountId(),
                        storeId,
                        version,
                        IdempotencyKey.parse(rawKey),
                        request
                );
        return response(result, "예약 시간 정책 게시 요청이 처리되었습니다.");
    }

    @PostMapping("/{version}/publication-cancellation")
    public ResponseEntity<ApiResponse<ReservationTimePolicyResponse>> cancelPublication(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @PathVariable @Positive long version,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody ReservationTimePolicyPublicationCancellationRequest request
    ) {
        ReservationTimePolicyCommandResult<ReservationTimePolicyResponse> result =
                commandFacade.cancelPublication(
                        principal.accountId(),
                        storeId,
                        version,
                        IdempotencyKey.parse(rawKey),
                        request
                );
        return response(result, "예약 시간 정책 예약 게시가 철회되었습니다.");
    }

    private ResponseEntity<ApiResponse<ReservationTimePolicyResponse>> response(
            ReservationTimePolicyCommandResult<ReservationTimePolicyResponse> result,
            String message
    ) {
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(message, result.data()));
    }
}
