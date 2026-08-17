package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.miriyum.domain.reservation.dto.request.ReservationCheckInRequest;
import com.miriyum.domain.reservation.dto.request.ReservationNoShowRequest;
import com.miriyum.domain.reservation.entity.ReservationNoShowReason;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ReservationVisitCommandFacadeTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-16T01:00:00Z");
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440000"
    );
    private static final String RAW_TOKEN = "rqg_v1_" + "A".repeat(43);
    private static final byte[] DIGEST = new byte[32];

    @Mock private ReservationVisitService visitService;
    @Mock private ReservationCheckInQrTokenService tokenService;

    private ReservationVisitCommandFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ReservationVisitCommandFacade(
                visitService,
                tokenService,
                Clock.fixed(REQUESTED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    void qrFingerprintContainsDigestAndNeverRawCredential() {
        given(tokenService.digest(RAW_TOKEN)).willReturn(DIGEST);
        given(visitService.checkIn(eq(33L), eq(22L), eq(DIGEST), any(),
                eq(REQUESTED_AT), any())).willReturn(new ReservationVisitCommandResult(200, null));

        facade.checkIn(33L, 22L, KEY, new ReservationCheckInRequest(RAW_TOKEN));

        ArgumentCaptor<IdempotencyCommand> command =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        then(visitService).should().checkIn(
                eq(33L), eq(22L), eq(DIGEST), command.capture(), eq(REQUESTED_AT),
                eq("reservation-qr-check-in:store-operator:33:" + KEY.value())
        );
        String canonical = "method=4:POST|"
                + "route=62:/api/v1/store-operators/stores/{storeId}/reservation-check-ins|"
                + "storeId=2:22|tokenDigest=64:" + HexFormat.of().formatHex(DIGEST) + "|";
        assertThat(command.getValue().commandType()).isEqualTo("RESERVATION_QR_CHECK_IN");
        assertThat(command.getValue().requestFingerprint())
                .isEqualTo(RequestFingerprint.of(canonical));
        assertThat(canonical).doesNotContain(RAW_TOKEN);
    }

    @Test
    void noShowFingerprintIncludesReservationAndRequiredReason() {
        given(visitService.markNoShow(eq(33L), eq(22L), eq(77L),
                eq(ReservationNoShowReason.UNCLEAR), any(), eq(REQUESTED_AT), any()))
                .willReturn(new ReservationVisitCommandResult(200, null));

        facade.markNoShow(33L, 22L, 77L, KEY,
                new ReservationNoShowRequest(ReservationNoShowReason.UNCLEAR));

        ArgumentCaptor<IdempotencyCommand> command =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        then(visitService).should().markNoShow(
                eq(33L), eq(22L), eq(77L), eq(ReservationNoShowReason.UNCLEAR),
                command.capture(), eq(REQUESTED_AT),
                eq("reservation-no-show:store-operator:33:" + KEY.value())
        );
        String canonical = "method=4:POST|"
                + "route=78:/api/v1/store-operators/stores/{storeId}/reservations/"
                + "{reservationId}/no-shows|storeId=2:22|reservationId=2:77|"
                + "reason=7:UNCLEAR|";
        assertThat(command.getValue().commandType()).isEqualTo("RESERVATION_NO_SHOW");
        assertThat(command.getValue().requestFingerprint())
                .isEqualTo(RequestFingerprint.of(canonical));
    }

    @Test
    void retriesOnlyApprovedMysqlLockFailuresOutsideTransaction() {
        given(tokenService.digest(RAW_TOKEN)).willReturn(DIGEST);
        CannotAcquireLockException deadlock = new CannotAcquireLockException(
                "deadlock", new SQLException("deadlock", "40001", 1213)
        );
        ReservationVisitCommandResult result = new ReservationVisitCommandResult(200, null);
        given(visitService.checkIn(
                anyLong(), anyLong(), any(), any(), any(), anyString()
        )).willThrow(deadlock).willReturn(result);
        ReservationVisitCommandFacade retrying = new ReservationVisitCommandFacade(
                visitService,
                tokenService,
                Clock.fixed(REQUESTED_AT, ZoneOffset.UTC),
                ignored -> 0L,
                ignored -> {
                }
        );

        assertThat(retrying.checkIn(
                33L, 22L, KEY, new ReservationCheckInRequest(RAW_TOKEN)
        )).isSameAs(result);
        then(visitService).should(times(2)).checkIn(
                anyLong(), anyLong(), any(), any(), any(), anyString()
        );
    }

    @Test
    void doesNotRetryDataIntegrityFailure() {
        given(tokenService.digest(RAW_TOKEN)).willReturn(DIGEST);
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "constraint"
        );
        given(visitService.checkIn(
                anyLong(), anyLong(), any(), any(), any(), anyString()
        )).willThrow(failure);

        assertThatThrownBy(() -> facade.checkIn(
                33L, 22L, KEY, new ReservationCheckInRequest(RAW_TOKEN)
        )).isSameAs(failure);
        then(visitService).should().checkIn(
                anyLong(), anyLong(), any(), any(), any(), anyString()
        );
    }
}
