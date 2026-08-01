package com.miriyum.domain.reservation.controller;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.reservation.dto.request.StoreReservationSearchRequest;
import com.miriyum.domain.reservation.dto.response.StoreReservationPageResponse;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증된 매장 운영자가 관리 권한을 가진 매장의 예약 목록을 조회하는 HTTP 경계다.
 */
@RestController
@RequestMapping("/api/v1/store-operator/stores/{storeId}/reservations")
@RequiredArgsConstructor
public class StoreReservationController {

    private final ReservationService reservationService;

    /**
     * 대상 매장의 예약 목록을 날짜·상태·페이지·단일 정렬 조건으로 조회한다.
     *
     * @param principal 인증된 매장 운영자 principal
     * @param storeId 대상 매장 식별자
     * @param serviceDate 선택한 서비스 날짜
     * @param status 선택한 예약 상태
     * @param page 0부터 시작하는 페이지 번호
     * @param size 페이지 크기
     * @param sort 허용된 단일 정렬
     * @return 공통 응답 봉투로 감싼 예약 목록 페이지
     */
    @GetMapping
    public ApiResponse<StoreReservationPageResponse> getReservations(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable @Positive long storeId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate serviceDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort
    ) {
        StoreReservationSearchRequest request = StoreReservationSearchRequest.from(
                serviceDate,
                status,
                page,
                size,
                sort
        );
        StoreReservationPageResponse response = reservationService.getStoreReservations(
                principal.accountId(),
                storeId,
                request
        );
        return ApiResponse.success("조회되었습니다.", response);
    }
}
