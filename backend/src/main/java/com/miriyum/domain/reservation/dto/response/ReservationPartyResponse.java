package com.miriyum.domain.reservation.dto.response;

import com.miriyum.domain.reservation.entity.PartyComposition;

/** 예약 확정 당시의 연령대별 인원 구성을 고객에게 공개한다. */
public record ReservationPartyResponse(
        int adultCount,
        int childCount,
        int infantCount,
        int totalCount
) {

    public static ReservationPartyResponse from(PartyComposition party) {
        if (party == null) {
            throw new IllegalArgumentException("party is required");
        }
        return new ReservationPartyResponse(
                party.getAdultCount(),
                party.getChildCount(),
                party.getInfantCount(),
                party.totalCount()
        );
    }
}
