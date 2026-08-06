package com.miriyum.domain.reservation.controller;

import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인증된 소비자 본인의 일반 예약 상세를 조회하는 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

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
}
