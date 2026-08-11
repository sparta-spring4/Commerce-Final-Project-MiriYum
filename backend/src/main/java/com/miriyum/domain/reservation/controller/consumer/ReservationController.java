package com.miriyum.domain.reservation.controller.consumer;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.reservation.dto.request.ConsumerCancellationRequest;
import com.miriyum.domain.reservation.dto.request.ReservationCreateRequest;
import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;
import com.miriyum.domain.reservation.service.ReservationCreationCommandFacade;
import com.miriyum.domain.reservation.service.ReservationCreationCommandResult;
import com.miriyum.domain.reservation.service.ReservationCancellationCommandFacade;
import com.miriyum.domain.reservation.service.ReservationCancellationCommandResult;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인증된 소비자 본인의 일반 예약 상세를 조회하는 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final ReservationCreationCommandFacade reservationCreationCommandFacade;
    private final ReservationCancellationCommandFacade reservationCancellationCommandFacade;

    /**
     * Authenticated consumer reservation creation is delegated unchanged to the command facade.
     *
     * @param principal authenticated consumer principal
     * @param rawKey HTTP idempotency key header
     * @param request reservation creation input
     * @return facade-selected HTTP status with the common success envelope
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ReservationDetailResponse>> createReservation(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody ReservationCreateRequest request
    ) {
        ReservationCreationCommandResult result = reservationCreationCommandFacade.create(
                principal.accountId(),
                IdempotencyKey.parse(rawKey),
                request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("예약이 생성되었습니다.", result.data()));
    }

    /**
     * 인증된 소비자의 계정 ID와 경로의 예약 ID만으로 본인 예약 상세를 조회한다.
     *
     * @param principal 소비자 Access JWT로 구성한 인증 주체
     * @param reservationId 조회할 예약 식별자
     * @return 공통 성공 봉투로 감싼 예약 상세
     */
    @GetMapping("/{reservationId}")
    public ApiResponse<ReservationDetailResponse> getReservation(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long reservationId
    ) {
        ReservationDetailResponse response = reservationService.getConsumerReservation(
                principal.accountId(),
                reservationId
        );
        return ApiResponse.success("조회되었습니다.", response);
    }

    /** Cancels the authenticated consumer's reservation through the cancellation facade. */
    @PostMapping("/{reservationId}/cancellations")
    public ResponseEntity<ApiResponse<ReservationDetailResponse>> cancelReservation(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long reservationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody ConsumerCancellationRequest request
    ) {
        ReservationCancellationCommandResult result =
                reservationCancellationCommandFacade.cancelByConsumer(
                        principal.accountId(), reservationId, IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("예약이 취소되었습니다.", result.data()));
    }
}
