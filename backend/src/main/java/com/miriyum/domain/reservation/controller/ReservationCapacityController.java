package com.miriyum.domain.reservation.controller;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.reservation.dto.request.ReservationCapacitiesRequest;
import com.miriyum.domain.reservation.dto.response.ReservationCapacitiesResponse;
import com.miriyum.domain.reservation.service.ReservationCapacityCommandFacade;
import com.miriyum.domain.reservation.service.ReservationCapacityCommandResult;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
        "/api/v1/store-operator/stores/{storeId}/reservation-capacities/{serviceDate}"
)
@RequiredArgsConstructor
public class ReservationCapacityController {

    private final ReservationCapacityCommandFacade commandFacade;

    @PutMapping
    public ResponseEntity<ApiResponse<ReservationCapacitiesResponse>> replace(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @PathVariable
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate serviceDate,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody ReservationCapacitiesRequest request
    ) {
        ReservationCapacityCommandResult result = commandFacade.replace(
                principal.accountId(),
                storeId,
                serviceDate,
                IdempotencyKey.parse(rawKey),
                request
        );
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(
                        "예약 수용량 정책이 게시되었습니다.",
                        result.data()
                ));
    }
}
