package com.miriyum.domain.reservation.dto.response;

import com.miriyum.domain.menuhold.dto.MenuHoldItemResult;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationActorType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 소비자와 운영자 예약 상세가 공유하는 거래 스냅샷 응답이다. */
public record ReservationDetailResponse(
        String reservationId,
        String storeId,
        String storeName,
        LocalDate serviceDate,
        CustomerReservationTimeStatus timeStatus,
        OffsetDateTime startAt,
        OffsetDateTime serviceEndAt,
        String timeZoneId,
        ReservationPartyResponse party,
        String status,
        List<ReservationMenuSelectionResponse> menuSelections,
        OffsetDateTime createdAt,
        String cancelledBy,
        String cancellationReason
) {

    public ReservationDetailResponse {
        menuSelections = List.copyOf(menuSelections);
    }

    public static ReservationDetailResponse from(
            Reservation reservation,
            List<MenuHoldItemResult> menuSnapshots
    ) {
        return from(reservation, menuSnapshots, null, null);
    }

    public static ReservationDetailResponse from(
            Reservation reservation,
            List<MenuHoldItemResult> menuSnapshots,
            ReservationCancellationActorType cancelledBy,
            String cancellationReason
    ) {
        if (reservation == null || menuSnapshots == null) {
            throw new IllegalArgumentException("reservation and menu snapshots are required");
        }
        CustomerReservationTimeResponse time =
                CustomerReservationTimeResponse.from(reservation.getTimeSnapshot());
        List<ReservationMenuSelectionResponse> menuSelections = List.copyOf(
                menuSnapshots.stream()
                        .map(ReservationMenuSelectionResponse::from)
                        .toList()
        );
        return new ReservationDetailResponse(
                String.valueOf(reservation.getId()),
                String.valueOf(reservation.getStoreId()),
                reservation.getStoreNameSnapshot(),
                time.serviceDate(),
                time.timeStatus(),
                time.startAt(),
                time.serviceEndAt(),
                time.timeZoneId(),
                ReservationPartyResponse.from(reservation.getParty()),
                reservation.getStatus().name(),
                menuSelections,
                reservation.getCreatedAt().atOffset(ZoneOffset.UTC),
                cancelledBy == null ? null : cancelledBy.name(),
                cancellationReason
        );
    }

    public ReservationDetailResponse(
            String reservationId,
            String storeId,
            String storeName,
            LocalDate serviceDate,
            CustomerReservationTimeStatus timeStatus,
            OffsetDateTime startAt,
            OffsetDateTime serviceEndAt,
            String timeZoneId,
            ReservationPartyResponse party,
            String status,
            List<ReservationMenuSelectionResponse> menuSelections,
            OffsetDateTime createdAt
    ) {
        this(
                reservationId,
                storeId,
                storeName,
                serviceDate,
                timeStatus,
                startAt,
                serviceEndAt,
                timeZoneId,
                party,
                status,
                menuSelections,
                createdAt,
                null,
                null
        );
    }
}
