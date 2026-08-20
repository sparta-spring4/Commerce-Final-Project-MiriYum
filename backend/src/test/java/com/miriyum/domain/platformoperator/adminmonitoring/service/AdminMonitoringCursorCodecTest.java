package com.miriyum.domain.platformoperator.adminmonitoring.service;

import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CaseType.RESERVATION;
import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CaseType.WAITING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringRequests.ListQuery;
import com.miriyum.domain.platformoperator.adminmonitoring.exception.AdminMonitoringErrorCode;
import com.miriyum.domain.platformoperator.adminmonitoring.service.AdminMonitoringCursorCodec.CursorState;
import com.miriyum.domain.platformoperator.adminmonitoring.service.AdminMonitoringCursorCodec.GlobalSeek;
import com.miriyum.domain.platformoperator.adminmonitoring.service.AdminMonitoringCursorCodec.SourceSeek;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AdminMonitoringCursorCodecTest {

    private static final String SECRET = "monitoring-cursor-secret-at-least-32-characters";
    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    @Test
    void roundTripsGlobalAndPerSourceSeekState() {
        AdminMonitoringCursorCodec codec = codec("key-1", NOW);
        CursorState state = new CursorState(
                NOW,
                new GlobalSeek(NOW.minusSeconds(5), RESERVATION, "reservation:12"),
                new SourceSeek(NOW.minusSeconds(5), "reservation:12"),
                new SourceSeek(NOW.minusSeconds(7), "waiting:9"),
                new SourceSeek(NOW.minusSeconds(6), "reservation-hold:12"),
                new SourceSeek(NOW.minusSeconds(8), "waiting:9"),
                Set.of("reservation:12", "waiting:9"));

        assertThat(codec.decode(codec.encode(state, query(20)), query(20)))
                .isEqualTo(state);
    }

    @Test
    void rejectsTamperingAndChangedFilterOrPageSizeAsInvalidCursor() {
        AdminMonitoringCursorCodec codec = codec("key-1", NOW);
        String token = codec.encode(state(), query(20));
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        assertError(() -> codec.decode(tampered, query(20)), AdminMonitoringErrorCode.INVALID_CURSOR);
        assertError(() -> codec.decode(token, query(40)), AdminMonitoringErrorCode.INVALID_CURSOR);
        assertError(() -> codec.decode(token, waitingQuery()), AdminMonitoringErrorCode.INVALID_CURSOR);
    }

    @Test
    void reportsRetiredKeyAndExpiredTtlSeparately() {
        String token = codec("key-1", NOW).encode(state(), query(20));

        assertError(
                () -> codec("key-2", NOW).decode(token, query(20)),
                AdminMonitoringErrorCode.EXPIRED_CURSOR);
        assertError(
                () -> codec("key-1", NOW.plus(Duration.ofMinutes(30)).plusNanos(1))
                        .decode(token, query(20)),
                AdminMonitoringErrorCode.EXPIRED_CURSOR);
    }

    @Test
    void rejectsCursorIssuedInTheFuture() {
        String token = codec("key-1", NOW.plusSeconds(1)).encode(
                new CursorState(
                        NOW,
                        state().lastEvaluated(),
                        state().reservationAfter(),
                        state().waitingAfter(),
                        state().menuHoldAfter(),
                        state().paymentAfter(),
                        state().emittedCaseIds()),
                query(20));

        assertError(() -> codec("key-1", NOW).decode(token, query(20)),
                AdminMonitoringErrorCode.INVALID_CURSOR);
    }

    private static CursorState state() {
        return new CursorState(
                NOW,
                new GlobalSeek(NOW.minusSeconds(5), WAITING, "waiting:9"),
                new SourceSeek(NOW.minusSeconds(6), "reservation:12"),
                new SourceSeek(NOW.minusSeconds(5), "waiting:9"),
                new SourceSeek(NOW.minusSeconds(7), "reservation-hold:12"),
                new SourceSeek(NOW.minusSeconds(8), "waiting:9"),
                Set.of("waiting:9"));
    }

    private static ListQuery query(int size) {
        return new ListQuery(
                "7", Set.of(RESERVATION, WAITING), Set.of(), Set.of(), Set.of(),
                NOW.minus(Duration.ofDays(1)), NOW, size, null);
    }

    private static ListQuery waitingQuery() {
        return new ListQuery(
                "7", Set.of(WAITING), Set.of(), Set.of(), Set.of(),
                NOW.minus(Duration.ofDays(1)), NOW, 20, null);
    }

    private static AdminMonitoringCursorCodec codec(String keyId, Instant now) {
        return new AdminMonitoringCursorCodec(
                keyId,
                SECRET,
                Duration.ofMinutes(30),
                new ObjectMapper(),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static void assertError(Runnable action, AdminMonitoringErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
