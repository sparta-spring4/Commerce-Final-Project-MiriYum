package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.miriyum.domain.reservation.waiting.dto.WaitingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamTransitionRequest;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WaitingCommandFacadeTest {

    private static final long OPERATOR_ID = 11L;
    private static final long STORE_ID = 21L;
    private static final long TEAM_ID = 31L;
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-12T03:01:00Z");
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440000");

    @Mock
    private WaitingLedgerService ledgerService;

    private WaitingCommandFacade facade;
    private WaitingCommandResult firstResult;

    @BeforeEach
    void setUp() {
        facade = new WaitingCommandFacade(
                ledgerService,
                Clock.fixed(REQUESTED_AT, ZoneOffset.UTC)
        );
        firstResult = new WaitingCommandResult(200, new WaitingTeamSnapshot(
                Long.toString(TEAM_ID),
                Long.toString(STORE_ID),
                WaitingTeamStatus.CALLED,
                7L,
                2,
                Instant.parse("2026-08-12T03:00:00Z"),
                REQUESTED_AT,
                null,
                null,
                null,
                1L
        ));
    }

    @Test
    @DisplayName("호출 지문은 method route storeId teamId expectedVersion을 정확히 포함한다")
    void callFingerprintContainsOnlyCanonicalRequestFields() {
        given(ledgerService.call(
                any(Long.class), any(Long.class), any(Long.class), any(Long.class), any(), any()))
                .willReturn(firstResult);

        facade.call(
                OPERATOR_ID,
                STORE_ID,
                TEAM_ID,
                KEY,
                new WaitingTeamTransitionRequest(4L)
        );

        ArgumentCaptor<IdempotencyCommand> command =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        then(ledgerService).should().call(
                org.mockito.ArgumentMatchers.eq(OPERATOR_ID),
                org.mockito.ArgumentMatchers.eq(STORE_ID),
                org.mockito.ArgumentMatchers.eq(TEAM_ID),
                org.mockito.ArgumentMatchers.eq(4L),
                command.capture(),
                org.mockito.ArgumentMatchers.eq(REQUESTED_AT)
        );
        assertThat(command.getValue().commandType()).isEqualTo("WAITING_TEAM_CALL");
        assertThat(command.getValue().requestFingerprint()).isEqualTo(RequestFingerprint.of(
                "method=4:POST|"
                        + "route=75:/api/v1/store-operators/stores/{storeId}/waiting-teams/"
                        + "{waitingTeamId}/call|"
                        + "storeId=2:21|"
                        + "waitingTeamId=2:31|"
                        + "expectedVersion=1:4|"
        ));
    }

    @Test
    @DisplayName("도착 지문은 arrive route와 expectedVersion을 포함한다")
    void arriveFingerprintUsesCanonicalRouteAndVersion() {
        given(ledgerService.arrive(
                any(Long.class), any(Long.class), any(Long.class), any(Long.class), any(), any()))
                .willReturn(firstResult);

        facade.arrive(
                OPERATOR_ID, STORE_ID, TEAM_ID, KEY, new WaitingTeamTransitionRequest(1L));

        ArgumentCaptor<IdempotencyCommand> command =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        then(ledgerService).should().arrive(
                org.mockito.ArgumentMatchers.eq(OPERATOR_ID),
                org.mockito.ArgumentMatchers.eq(STORE_ID),
                org.mockito.ArgumentMatchers.eq(TEAM_ID),
                org.mockito.ArgumentMatchers.eq(1L),
                command.capture(),
                org.mockito.ArgumentMatchers.eq(REQUESTED_AT));
        assertThat(command.getValue().commandType()).isEqualTo("WAITING_TEAM_ARRIVE");
        assertThat(command.getValue().requestFingerprint()).isEqualTo(RequestFingerprint.of(
                canonical("arrive", 77, 1L)));
    }

    @Test
    @DisplayName("입장 지문은 check-in route와 expectedVersion을 포함한다")
    void checkInFingerprintUsesCanonicalRouteAndVersion() {
        given(ledgerService.checkIn(
                any(Long.class), any(Long.class), any(Long.class), any(Long.class), any(), any()))
                .willReturn(firstResult);

        facade.checkIn(
                OPERATOR_ID, STORE_ID, TEAM_ID, KEY, new WaitingTeamTransitionRequest(2L));

        ArgumentCaptor<IdempotencyCommand> command =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        then(ledgerService).should().checkIn(
                org.mockito.ArgumentMatchers.eq(OPERATOR_ID),
                org.mockito.ArgumentMatchers.eq(STORE_ID),
                org.mockito.ArgumentMatchers.eq(TEAM_ID),
                org.mockito.ArgumentMatchers.eq(2L),
                command.capture(),
                org.mockito.ArgumentMatchers.eq(REQUESTED_AT));
        assertThat(command.getValue().commandType()).isEqualTo("WAITING_TEAM_CHECK_IN");
        assertThat(command.getValue().requestFingerprint()).isEqualTo(RequestFingerprint.of(
                canonical("check-in", 79, 2L)));
    }

    @Test
    @DisplayName("취소 지문은 cancel route와 expectedVersion을 포함한다")
    void cancelFingerprintUsesCanonicalRouteAndVersion() {
        given(ledgerService.cancel(
                any(Long.class), any(Long.class), any(Long.class), any(Long.class), any(), any()))
                .willReturn(firstResult);

        facade.cancel(
                OPERATOR_ID, STORE_ID, TEAM_ID, KEY, new WaitingTeamTransitionRequest(2L));

        ArgumentCaptor<IdempotencyCommand> command =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        then(ledgerService).should().cancel(
                org.mockito.ArgumentMatchers.eq(OPERATOR_ID),
                org.mockito.ArgumentMatchers.eq(STORE_ID),
                org.mockito.ArgumentMatchers.eq(TEAM_ID),
                org.mockito.ArgumentMatchers.eq(2L),
                command.capture(),
                org.mockito.ArgumentMatchers.eq(REQUESTED_AT));
        assertThat(command.getValue().commandType()).isEqualTo("WAITING_TEAM_CANCEL");
        assertThat(command.getValue().requestFingerprint()).isEqualTo(RequestFingerprint.of(
                canonical("cancel", 77, 2L)));
    }

    @Test
    @DisplayName("동일 키와 동일 expectedVersion 재요청은 최초 응답을 재생한다")
    void sameKeySameExpectedVersionReplaysFirstResponse() {
        AtomicReference<String> firstFingerprint = new AtomicReference<>();
        given(ledgerService.call(
                any(Long.class), any(Long.class), any(Long.class), any(Long.class), any(), any()))
                .willAnswer(invocation -> {
                    IdempotencyCommand command = invocation.getArgument(4);
                    String known = firstFingerprint.get();
                    if (known == null) {
                        firstFingerprint.set(command.requestFingerprint());
                    } else if (!known.equals(command.requestFingerprint())) {
                        throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
                    }
                    return firstResult;
                });
        WaitingTeamTransitionRequest request = new WaitingTeamTransitionRequest(0L);

        WaitingCommandResult first = facade.call(
                OPERATOR_ID, STORE_ID, TEAM_ID, KEY, request);
        WaitingCommandResult replay = facade.call(
                OPERATOR_ID, STORE_ID, TEAM_ID, KEY, request);

        assertThat(replay).isSameAs(first);
        then(ledgerService).should(times(2)).call(
                org.mockito.ArgumentMatchers.eq(OPERATOR_ID),
                org.mockito.ArgumentMatchers.eq(STORE_ID),
                org.mockito.ArgumentMatchers.eq(TEAM_ID),
                org.mockito.ArgumentMatchers.eq(0L),
                any(),
                org.mockito.ArgumentMatchers.eq(REQUESTED_AT)
        );
    }

    @Test
    @DisplayName("동일 키를 다른 expectedVersion으로 재사용하면 COMMON_007이다")
    void sameKeyDifferentExpectedVersionConflicts() {
        AtomicReference<String> firstFingerprint = new AtomicReference<>();
        given(ledgerService.call(
                any(Long.class), any(Long.class), any(Long.class), any(Long.class), any(), any()))
                .willAnswer(invocation -> {
                    IdempotencyCommand command = invocation.getArgument(4);
                    String known = firstFingerprint.get();
                    if (known == null) {
                        firstFingerprint.set(command.requestFingerprint());
                        return firstResult;
                    }
                    if (!known.equals(command.requestFingerprint())) {
                        throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
                    }
                    return firstResult;
                });
        facade.call(
                OPERATOR_ID,
                STORE_ID,
                TEAM_ID,
                KEY,
                new WaitingTeamTransitionRequest(0L)
        );

        assertThatThrownBy(() -> facade.call(
                OPERATOR_ID,
                STORE_ID,
                TEAM_ID,
                KEY,
                new WaitingTeamTransitionRequest(1L)
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    private static String canonical(String action, int routeLength, long expectedVersion) {
        return "method=4:POST|"
                + "route=" + routeLength
                + ":/api/v1/store-operators/stores/{storeId}/waiting-teams/"
                + "{waitingTeamId}/" + action + "|"
                + "storeId=2:21|"
                + "waitingTeamId=2:31|"
                + "expectedVersion=1:" + expectedVersion + "|";
    }
}
