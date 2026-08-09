package com.miriyum.domain.pickup.controller;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.pickup.dto.request.PickupReservationCreateRequest;
import com.miriyum.domain.pickup.dto.response.PickupReservationResponse;
import com.miriyum.domain.pickup.service.PickupCommandResult;
import com.miriyum.domain.pickup.service.PickupReservationService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pickup-reservations")
public class PickupReservationController {

    private final PickupReservationService service;

    public PickupReservationController(PickupReservationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PickupReservationResponse>> create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody PickupReservationCreateRequest request
    ) {
        PickupCommandResult result = service.create(
                principal.accountId(), IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("픽업 예약을 생성했습니다.", result.data()));
    }
}
