package com.miriyum.domain.reservation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReservationFulfillmentRequestTest {

    @Test
    void fulfillmentRequestHasNoBindableFields() {
        assertThat(ReservationFulfillmentRequest.class.isRecord()).isTrue();
        assertThat(ReservationFulfillmentRequest.class.getRecordComponents()).isEmpty();
    }
}
