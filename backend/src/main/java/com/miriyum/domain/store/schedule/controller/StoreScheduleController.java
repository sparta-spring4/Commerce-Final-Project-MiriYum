package com.miriyum.domain.store.schedule.controller;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.store.schedule.dto.OperatingHoursResponse;
import com.miriyum.domain.store.schedule.dto.ReservationTimeSlotsResponse;
import com.miriyum.domain.store.schedule.dto.WeeklyOperatingHoursRequest;
import com.miriyum.domain.store.schedule.dto.WeeklyReservationTimeSlotsRequest;
import com.miriyum.domain.store.schedule.service.ScheduleCommandResult;
import com.miriyum.domain.store.schedule.service.StoreScheduleService;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/store-operator/stores/{storeId}")
@RequiredArgsConstructor
public class StoreScheduleController {

    private final StoreScheduleService storeScheduleService;

    @PutMapping("/operating-hours")
    public ResponseEntity<ApiResponse<OperatingHoursResponse>>
            replaceOperatingHours(
                    @AuthenticationPrincipal AuthenticatedPrincipal principal,
                    @PathVariable @Positive long storeId,
                    @RequestHeader(
                            value = "Idempotency-Key",
                            required = false
                    ) String rawKey,
                    @Valid @RequestBody WeeklyOperatingHoursRequest request
            ) {
        ScheduleCommandResult<OperatingHoursResponse> result =
                storeScheduleService.replaceOperatingHours(
                        principal.accountId(),
                        storeId,
                        IdempotencyKey.parse(rawKey),
                        request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(
                        "영업시간이 게시되었습니다.",
                        result.data()));
    }

    @PutMapping("/reservation-time-slots")
    public ResponseEntity<ApiResponse<ReservationTimeSlotsResponse>>
            replaceReservationTimeSlots(
                    @AuthenticationPrincipal AuthenticatedPrincipal principal,
                    @PathVariable @Positive long storeId,
                    @RequestHeader(
                            value = "Idempotency-Key",
                            required = false
                    ) String rawKey,
                    @Valid
                    @RequestBody WeeklyReservationTimeSlotsRequest request
            ) {
        ScheduleCommandResult<ReservationTimeSlotsResponse> result =
                storeScheduleService.replaceReservationTimeSlots(
                        principal.accountId(),
                        storeId,
                        IdempotencyKey.parse(rawKey),
                        request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(
                        "예약 접수 시간대가 게시되었습니다.",
                        result.data()));
    }
}
