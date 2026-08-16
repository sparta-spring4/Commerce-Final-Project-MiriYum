package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;
import com.miriyum.domain.reservation.dto.response.ReservationRequestResponse;

/** Typed 201/202 success union returned by the reservation creation facade. */
public final class ReservationCreationCommandResult {

    private final int httpStatus;
    private final Payload payload;

    /** Preserves the existing immediate-confirmation construction contract. */
    public ReservationCreationCommandResult(
            int httpStatus,
            ReservationDetailResponse data
    ) {
        if (httpStatus != 201) {
            throw new IllegalArgumentException("confirmed reservation status must be 201");
        }
        this.httpStatus = httpStatus;
        this.payload = new ConfirmedPayload(data);
    }

    private ReservationCreationCommandResult(ReservationRequestResponse data) {
        this.httpStatus = 202;
        this.payload = new DepositRequestPayload(data);
    }

    public static ReservationCreationCommandResult depositRequested(
            ReservationRequestResponse data
    ) {
        if (data == null) {
            throw new IllegalArgumentException("reservation request data is required");
        }
        return new ReservationCreationCommandResult(data);
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** Existing 201-only accessor retained for immediate creation callers. */
    public ReservationDetailResponse data() {
        if (payload instanceof ConfirmedPayload confirmed) {
            return confirmed.data();
        }
        throw new IllegalStateException("deposit request does not contain reservation detail");
    }

    public Object responseData() {
        return switch (payload) {
            case ConfirmedPayload confirmed -> confirmed.data();
            case DepositRequestPayload requested -> requested.data();
        };
    }

    private sealed interface Payload permits ConfirmedPayload, DepositRequestPayload {
    }

    private record ConfirmedPayload(ReservationDetailResponse data) implements Payload {
    }

    private record DepositRequestPayload(ReservationRequestResponse data) implements Payload {
    }
}
