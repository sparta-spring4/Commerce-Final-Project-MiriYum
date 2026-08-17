package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.auth.qrepoch.ConsumerQrEpochService;
import com.miriyum.domain.auth.qrepoch.ConsumerQrEpochSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationCheckInQrGrantCommandFacadeTest {

    private static final Instant NOW = Instant.parse("2026-08-16T01:00:00Z");

    @Mock private ReservationCheckInQrTokenService tokenService;
    @Mock private ConsumerQrEpochService qrEpochService;
    @Mock private ReservationCheckInQrGrantService grantService;

    private ReservationCheckInQrGrantCommandFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ReservationCheckInQrGrantCommandFacade(
                tokenService,
                qrEpochService,
                grantService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("raw token은 생성·epoch 캡처 뒤 service metadata와 결합해 한 번만 반환한다")
    void combinesRawTokenWithStoredGrantMetadata() {
        String rawToken = "rqg_v1_" + "A".repeat(43);
        byte[] digest = new byte[32];
        ConsumerQrEpochSnapshot epoch = new ConsumerQrEpochSnapshot(
                11L, "v1." + "B".repeat(43)
        );
        given(tokenService.generate()).willReturn(
                new ReservationCheckInQrTokenService.GeneratedToken(rawToken, digest)
        );
        given(qrEpochService.captureCurrent(11L)).willReturn(epoch);
        given(grantService.issue(11L, 77L, digest, epoch, NOW)).willReturn(
                new ReservationCheckInQrGrantService.IssuedGrant(
                        77L, 4L, NOW, NOW.plusSeconds(30)
                )
        );

        ReservationCheckInQrGrantResult result = facade.issue(11L, 77L);

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.data().reservationId()).isEqualTo("77");
        assertThat(result.data().qrToken()).isEqualTo(rawToken);
        assertThat(result.data().tokenVersion()).isEqualTo(4L);
        then(tokenService).should().generate();
        then(qrEpochService).should().captureCurrent(11L);
        then(grantService).should().issue(11L, 77L, digest, epoch, NOW);
    }
}
