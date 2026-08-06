package com.miriyum.domain.reservation.dto.response;

import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.global.response.PageMetadata;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 매장 운영자 예약 목록과 페이지 메타데이터를 함께 제공하는 응답이다.
 */
public record StoreReservationPageResponse(
        List<StoreReservationSummaryResponse> items,
        PageMetadata page
) {

    public static StoreReservationPageResponse from(Page<Reservation> reservations) {
        List<StoreReservationSummaryResponse> items = reservations.getContent().stream()
                .map(StoreReservationSummaryResponse::from)
                .toList();
        PageMetadata page = new PageMetadata(
                reservations.getNumber(),
                reservations.getSize(),
                reservations.getTotalElements(),
                reservations.getTotalPages(),
                reservations.hasNext()
        );

        return new StoreReservationPageResponse(items, page);
    }
}
