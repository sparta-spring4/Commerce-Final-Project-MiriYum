package com.miriyum.domain.reservation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReservationCheckInQrGrantResponseTest {

    @Test
    void exposesRawCredentialOnlyInMintResponseWithUtcTimes() {
        Instant issuedAt = Instant.parse("2026-08-16T01:00:00Z");

        ReservationCheckInQrGrantResponse response = ReservationCheckInQrGrantResponse.of(
                77L,
                "rqg_v1_" + "A".repeat(43),
                4L,
                issuedAt,
                issuedAt.plusSeconds(30)
        );

        assertThat(response.reservationId()).isEqualTo("77");
        assertThat(response.qrToken()).startsWith("rqg_v1_");
        assertThat(response.tokenVersion()).isEqualTo(4L);
        assertThat(response.issuedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(response.expiresAt()).isEqualTo(issuedAt.plusSeconds(30).atOffset(ZoneOffset.UTC));
    }
}
