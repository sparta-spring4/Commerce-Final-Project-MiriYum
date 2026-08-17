package com.miriyum.domain.reservation.controller.consumer;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.dto.request.ConsumerCancellationRequest;
import com.miriyum.domain.reservation.dto.request.ReservationCreateRequest;
import com.miriyum.domain.reservation.dto.request.ReservationHistorySearchRequest;
import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;
import com.miriyum.domain.reservation.dto.response.ReservationCheckInQrGrantResponse;
import com.miriyum.domain.reservation.dto.response.ReservationHistoryPageResponse;
import com.miriyum.domain.reservation.dto.response.ReservationRequestResponse;
import com.miriyum.domain.reservation.service.ReservationCreationCommandFacade;
import com.miriyum.domain.reservation.service.ReservationCreationCommandResult;
import com.miriyum.domain.reservation.service.ReservationCancellationCommandFacade;
import com.miriyum.domain.reservation.service.ReservationCancellationCommandResult;
import com.miriyum.domain.reservation.service.ReservationCheckInQrGrantCommandFacade;
import com.miriyum.domain.reservation.service.ReservationCheckInQrGrantResult;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.reservation.service.ReservationDepositCommandResult;
import com.miriyum.domain.reservation.service.ReservationDepositProcessCommandFacade;
import com.miriyum.domain.reservation.service.ReservationDepositProcessService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 인증된 소비자의 일반 예약 생성·조회·취소와 예약 이력을 제공하는 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1/consumers/me")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final ReservationCreationCommandFacade reservationCreationCommandFacade;
    private final ReservationCancellationCommandFacade reservationCancellationCommandFacade;
    private final ReservationDepositProcessCommandFacade reservationDepositProcessCommandFacade;
    private final ReservationDepositProcessService reservationDepositProcessService;
    private final ReservationCheckInQrGrantCommandFacade reservationCheckInQrGrantCommandFacade;
    private final ConsumerAccountService consumerAccountService;

    @GetMapping("/reservation-requests/{reservationRequestId}")
    public ApiResponse<ReservationRequestResponse> getReservationRequest(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long reservationRequestId
    ) {
        return ApiResponse.success(
                "조회되었습니다.",
                reservationDepositProcessService.getOwnedRequest(
                        reservationRequestId, principal.accountId()));
    }

    @PostMapping("/reservation-requests/{reservationRequestId}/finalizations")
    public ResponseEntity<ApiResponse<Object>> finalizeReservationRequest(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long reservationRequestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody EmptyCommandRequest ignoredRequest
    ) {
        ReservationDepositCommandResult result =
                reservationDepositProcessCommandFacade.finalizeRequest(
                        principal.accountId(),
                        reservationRequestId,
                        IdempotencyKey.parse(rawKey));
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("예약금 요청을 처리했습니다.", result.responseData()));
    }

    @PostMapping("/reservation-requests/{reservationRequestId}/abandonments")
    public ResponseEntity<ApiResponse<Object>> abandonReservationRequest(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long reservationRequestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody EmptyCommandRequest ignoredRequest
    ) {
        ReservationDepositCommandResult result =
                reservationDepositProcessCommandFacade.abandonRequest(
                        principal.accountId(),
                        reservationRequestId,
                        IdempotencyKey.parse(rawKey));
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("예약금 요청을 포기했습니다.", result.responseData()));
    }

    /** Required empty JSON object for reservation deposit commands. */
    public record EmptyCommandRequest() {
    }

    /**
     * Authenticated consumer reservation creation is delegated unchanged to the command facade.
     *
     * @param principal authenticated consumer principal
     * @param rawKey HTTP idempotency key header
     * @param request reservation creation input
     * @return facade-selected HTTP status with the common success envelope
     */
    @PostMapping("/reservations")
    public ResponseEntity<ApiResponse<Object>> createReservation(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody ReservationCreateRequest request
    ) {
        ReservationCreationCommandResult result = reservationCreationCommandFacade.create(
                principal.accountId(),
                IdempotencyKey.parse(rawKey),
                request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("예약이 생성되었습니다.", result.responseData()));
    }

    /**
     * 인증된 소비자의 계정 ID와 경로의 예약 ID만으로 본인 예약 상세를 조회한다.
     *
     * @param principal 소비자 Access JWT로 구성한 인증 주체
     * @param reservationId 조회할 예약 식별자
     * @return 공통 성공 봉투로 감싼 예약 상세
     */
    @GetMapping("/reservations/{reservationId}")
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

    /** 본인 확정 예약의 current QR grant를 회전하고 raw credential을 한 번 반환한다. */
    @PostMapping("/reservations/{reservationId}/check-in-qr-grants")
    public ResponseEntity<ApiResponse<ReservationCheckInQrGrantResponse>> issueCheckInQrGrant(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long reservationId
    ) {
        ReservationCheckInQrGrantResult result =
                reservationCheckInQrGrantCommandFacade.issue(
                        principal.accountId(), reservationId
                );
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("체크인 QR이 발급되었습니다.", result.data()));
    }

    /** Cancels the authenticated consumer's reservation through the cancellation facade. */
    @PostMapping("/reservations/{reservationId}/cancellations")
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

    @GetMapping("/reservations")
    public ApiResponse<ReservationHistoryPageResponse> getReservationHistory(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort
    ) {
        consumerAccountService.getMe(principal.accountId());
        ReservationHistorySearchRequest request = ReservationHistorySearchRequest.from(
                status, page, size, sort);
        ReservationHistoryPageResponse response =
                reservationService.getConsumerReservationHistory(principal.accountId(), request);
        return ApiResponse.success("조회했습니다.", response);
    }
}
