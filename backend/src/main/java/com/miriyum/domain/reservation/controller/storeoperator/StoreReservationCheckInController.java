package com.miriyum.domain.reservation.controller.storeoperator;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.reservation.dto.request.ReservationCheckInRequest;
import com.miriyum.domain.reservation.dto.request.ReservationNoShowRequest;
import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;
import com.miriyum.domain.reservation.service.ReservationVisitCommandFacade;
import com.miriyum.domain.reservation.service.ReservationVisitCommandResult;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 매장 운영자의 QR 체크인과 명시적 노쇼 확정 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1/store-operators/stores/{storeId}")
@RequiredArgsConstructor
public class StoreReservationCheckInController {

    private final ReservationVisitCommandFacade reservationVisitCommandFacade;

    /** 스캔한 opaque QR로 예약 방문 완료를 멱등 확정한다. */
    @PostMapping("/reservation-check-ins")
    public ResponseEntity<ApiResponse<ReservationDetailResponse>> checkIn(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody ReservationCheckInRequest request
    ) {
        ReservationVisitCommandResult result = reservationVisitCommandFacade.checkIn(
                principal.accountId(),
                storeId,
                IdempotencyKey.parse(rawKey),
                request
        );
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("예약 체크인이 완료되었습니다.", result.data()));
    }

    /** 공통 +5분 경계 뒤 필수 후보 사유로 예약 노쇼를 멱등 확정한다. */
    @PostMapping("/reservations/{reservationId}/no-shows")
    public ResponseEntity<ApiResponse<ReservationDetailResponse>> markNoShow(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @PathVariable @Positive long reservationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody ReservationNoShowRequest request
    ) {
        ReservationVisitCommandResult result = reservationVisitCommandFacade.markNoShow(
                principal.accountId(),
                storeId,
                reservationId,
                IdempotencyKey.parse(rawKey),
                request
        );
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("예약 노쇼가 확정되었습니다.", result.data()));
    }
}
