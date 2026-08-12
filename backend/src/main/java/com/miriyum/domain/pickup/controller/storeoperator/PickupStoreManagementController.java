package com.miriyum.domain.pickup.controller.storeoperator;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.pickup.dto.request.EmptyPickupCommandRequest;
import com.miriyum.domain.pickup.dto.request.PickupStoreSearchRequest;
import com.miriyum.domain.pickup.dto.request.StorePickupCancellationRequest;
import com.miriyum.domain.pickup.dto.response.PickupReservationPageResponse;
import com.miriyum.domain.pickup.dto.response.PickupReservationResponse;
import com.miriyum.domain.pickup.service.PickupCommandResult;
import com.miriyum.domain.pickup.service.PickupCommandFacade;
import com.miriyum.domain.pickup.service.PickupStoreManagementService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
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

@RestController
@RequestMapping("/api/v1/store-operators/stores/{storeId}/pickup-reservations")
public class PickupStoreManagementController {

    private final PickupStoreManagementService service;
    private final PickupCommandFacade commandFacade;

    public PickupStoreManagementController(
            PickupStoreManagementService service,
            PickupCommandFacade commandFacade
    ) {
        this.service = service;
        this.commandFacade = commandFacade;
    }

    @GetMapping
    public ApiResponse<PickupReservationPageResponse> list(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate pickupDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort
    ) {
        PickupStoreSearchRequest request = PickupStoreSearchRequest.from(
                pickupDate, status, page, size, sort);
        return ApiResponse.success("조회했습니다.", service.list(
                principal.accountId(), storeId, request));
    }

    @GetMapping("/{pickupReservationId}")
    public ApiResponse<PickupReservationResponse> getDetail(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId,
            @PathVariable long pickupReservationId
    ) {
        return ApiResponse.success("조회했습니다.", service.getDetail(
                principal.accountId(), storeId, pickupReservationId));
    }

    @PostMapping("/{pickupReservationId}/cancellations")
    public ResponseEntity<ApiResponse<PickupReservationResponse>> cancel(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId,
            @PathVariable long pickupReservationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody StorePickupCancellationRequest request
    ) {
        PickupCommandResult result = commandFacade.cancelByOperator(
                principal.accountId(), storeId, pickupReservationId,
                IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("픽업 예약을 취소했습니다.", result.data()));
    }

    @PostMapping("/{pickupReservationId}/fulfillments")
    public ResponseEntity<ApiResponse<PickupReservationResponse>> fulfill(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long storeId,
            @PathVariable long pickupReservationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @RequestBody EmptyPickupCommandRequest request
    ) {
        PickupCommandResult result = commandFacade.fulfill(
                principal.accountId(), storeId, pickupReservationId,
                IdempotencyKey.parse(rawKey));
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("픽업 수령을 완료했습니다.", result.data()));
    }
}
