package com.miriyum.domain.schedule.controller.storeoperator;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.schedule.dto.storeoperator.OperatingHoursResponse;
import com.miriyum.domain.schedule.dto.storeoperator.ReservationTimeSlotsResponse;
import com.miriyum.domain.schedule.dto.storeoperator.WeeklyOperatingHoursRequest;
import com.miriyum.domain.schedule.dto.storeoperator.WeeklyReservationTimeSlotsRequest;
import com.miriyum.domain.schedule.dto.storeoperator.SchedulePublicationRequest;
import com.miriyum.domain.schedule.dto.storeoperator.SchedulePublicationCancellationRequest;
import com.miriyum.domain.schedule.service.ScheduleCommandResult;
import com.miriyum.domain.schedule.service.StoreScheduleCommandFacade;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/store-operator/stores/{storeId}")
@RequiredArgsConstructor
public class StoreScheduleController {

    private final StoreScheduleCommandFacade storeScheduleService;

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
                storeScheduleService.createOperatingDraft(
                        principal.accountId(),
                        storeId,
                        IdempotencyKey.parse(rawKey),
                        request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(
                        "영업시간 초안이 저장되었습니다.",
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
                storeScheduleService.createReservationDraft(
                        principal.accountId(),
                        storeId,
                        IdempotencyKey.parse(rawKey),
                        request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(
                        "예약 접수 시간대 초안이 저장되었습니다.",
                        result.data()));
    }

    @PostMapping("/operating-hours/{version}/publication")
    public ResponseEntity<ApiResponse<OperatingHoursResponse>> publishOperating(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @PathVariable @Positive long version,
            @RequestHeader(value = "Idempotency-Key", required = false)
                    String rawKey,
            @Valid @RequestBody SchedulePublicationRequest request
    ) {
        ScheduleCommandResult<OperatingHoursResponse> result =
                storeScheduleService.publishOperating(
                        principal.accountId(), storeId, version,
                        IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(
                        "영업시간 게시 요청이 처리되었습니다.",
                        result.data()));
    }

    @PostMapping("/operating-hours/{version}/publication-cancellation")
    public ResponseEntity<ApiResponse<OperatingHoursResponse>>
            cancelOperatingPublication(
                    @AuthenticationPrincipal AuthenticatedPrincipal principal,
                    @PathVariable @Positive long storeId,
                    @PathVariable @Positive long version,
                    @RequestHeader(value = "Idempotency-Key", required = false)
                            String rawKey,
                    @Valid @RequestBody
                            SchedulePublicationCancellationRequest request
            ) {
        ScheduleCommandResult<OperatingHoursResponse> result =
                storeScheduleService.cancelOperatingPublication(
                        principal.accountId(), storeId, version,
                        IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(
                        "영업시간 예약 게시가 취소되었습니다.",
                        result.data()));
    }

    @PostMapping("/reservation-time-slots/{version}/publication")
    public ResponseEntity<ApiResponse<ReservationTimeSlotsResponse>>
            publishReservation(
                    @AuthenticationPrincipal AuthenticatedPrincipal principal,
                    @PathVariable @Positive long storeId,
                    @PathVariable @Positive long version,
                    @RequestHeader(value = "Idempotency-Key", required = false)
                            String rawKey,
                    @Valid @RequestBody SchedulePublicationRequest request
            ) {
        ScheduleCommandResult<ReservationTimeSlotsResponse> result =
                storeScheduleService.publishReservation(
                        principal.accountId(), storeId, version,
                        IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(
                        "예약 접수 시간대 게시 요청이 처리되었습니다.",
                        result.data()));
    }

    @PostMapping(
            "/reservation-time-slots/{version}/publication-cancellation")
    public ResponseEntity<ApiResponse<ReservationTimeSlotsResponse>>
            cancelReservationPublication(
                    @AuthenticationPrincipal AuthenticatedPrincipal principal,
                    @PathVariable @Positive long storeId,
                    @PathVariable @Positive long version,
                    @RequestHeader(value = "Idempotency-Key", required = false)
                            String rawKey,
                    @Valid @RequestBody
                            SchedulePublicationCancellationRequest request
            ) {
        ScheduleCommandResult<ReservationTimeSlotsResponse> result =
                storeScheduleService.cancelReservationPublication(
                        principal.accountId(), storeId, version,
                        IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success(
                        "예약 접수 시간대 예약 게시가 취소되었습니다.",
                        result.data()));
    }
}
