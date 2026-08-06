package com.miriyum.domain.reservation.dto.response;

import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.global.response.PageMetadata;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 소비자 예약 내역과 페이지 메타데이터를 함께 제공하는 응답이다.
 */
public record ReservationHistoryPageResponse(
        List<ReservationHistoryItemResponse> items,
        PageMetadata page
) {

    public static ReservationHistoryPageResponse from(Page<Reservation> reservations) {
        List<ReservationHistoryItemResponse> items = reservations.getContent().stream()
                .map(ReservationHistoryItemResponse::from)
                .toList();
        PageMetadata page = new PageMetadata(
                reservations.getNumber(),
                reservations.getSize(),
                reservations.getTotalElements(),
                reservations.getTotalPages(),
                reservations.hasNext()
        );

        return new ReservationHistoryPageResponse(items, page);
    }
}
