package com.miriyum.domain.pickup.service;

import com.miriyum.domain.pickup.dto.response.PickupReservationResponse;

public record PickupCommandResult(int httpStatus, PickupReservationResponse data) {
}
