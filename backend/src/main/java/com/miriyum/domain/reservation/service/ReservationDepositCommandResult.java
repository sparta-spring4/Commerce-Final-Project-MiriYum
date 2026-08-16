package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;
import com.miriyum.domain.reservation.dto.response.ReservationRequestResponse;

/** Typed 200/202 success union for Reservation deposit commands. */
public final class ReservationDepositCommandResult {

    private final int httpStatus;
    private final Object payload;

    private ReservationDepositCommandResult(int httpStatus, Object payload) {
        if ((httpStatus != 200 && httpStatus != 202) || payload == null) {
            throw new IllegalArgumentException("deposit command result must be 200 or 202");
        }
        if (httpStatus == 200 && !(payload instanceof ReservationDetailResponse)
                || httpStatus == 202 && !(payload instanceof ReservationRequestResponse)) {
            throw new IllegalArgumentException("payload must match deposit command status");
        }
        this.httpStatus = httpStatus;
        this.payload = payload;
    }

    public static ReservationDepositCommandResult completed(
            ReservationDetailResponse reservation
    ) {
        return new ReservationDepositCommandResult(200, reservation);
    }

    public static ReservationDepositCommandResult pending(
            ReservationRequestResponse request
    ) {
        return new ReservationDepositCommandResult(202, request);
    }

    public int httpStatus() {
        return httpStatus;
    }

    public ReservationDetailResponse reservation() {
        if (payload instanceof ReservationDetailResponse reservation) {
            return reservation;
        }
        throw new IllegalStateException("pending result does not contain a reservation");
    }

    public ReservationRequestResponse reservationRequest() {
        if (payload instanceof ReservationRequestResponse request) {
            return request;
        }
        throw new IllegalStateException("completed result does not contain a request");
    }

    public Object responseData() {
        return payload;
    }
}
