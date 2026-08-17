package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.entity.WaitingReceptionMode;
import com.miriyum.domain.reservation.waiting.entity.WaitingSetting;
import com.miriyum.domain.reservation.waiting.repository.WaitingReceptionWindowRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingSettingRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WaitingReceptionGateTest {

    private static final long STORE_ID = 7L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 17);
    private static final Instant NOW = Instant.parse("2026-08-17T00:30:00Z");

    @Mock WaitingOperatingIntervalPort intervalPort;
    @Mock WaitingSettingRepository settingRepository;
    @Mock WaitingReceptionWindowRepository windowRepository;

    private WaitingReceptionGate gate;

    @BeforeEach
    void setUp() {
        gate = new WaitingReceptionGate(intervalPort, settingRepository, windowRepository);
    }

    @Test
    void autoAcceptsOnlyCurrentIntervalWindowAtExactSettingsVersion() {
        WaitingOperatingInterval interval = interval(
                Instant.parse("2026-08-17T01:00:00Z"),
                Instant.parse("2026-08-17T09:00:00Z"));
        given(intervalPort.lockCurrent(STORE_ID, BUSINESS_DATE, NOW))
                .willReturn(List.of(interval));
        given(settingRepository.findByStoreIdForUpdate(STORE_ID))
                .willReturn(Optional.of(setting(WaitingReceptionMode.AUTO)));
        given(windowRepository.existsAccepting(
                STORE_ID, "interval-key", BUSINESS_DATE, 1L, NOW)).willReturn(true);

        gate.requireOpen(STORE_ID, BUSINESS_DATE, NOW);

        InOrder order = inOrder(intervalPort, settingRepository, windowRepository);
        order.verify(intervalPort).lockCurrent(STORE_ID, BUSINESS_DATE, NOW);
        order.verify(settingRepository).findByStoreIdForUpdate(STORE_ID);
        order.verify(windowRepository).existsAccepting(
                STORE_ID, "interval-key", BUSINESS_DATE, 1L, NOW);
    }

    @Test
    void autoRejectsWindowFromOldSettingsVersion() {
        given(intervalPort.lockCurrent(STORE_ID, BUSINESS_DATE, NOW)).willReturn(List.of(interval(
                Instant.parse("2026-08-17T01:00:00Z"),
                Instant.parse("2026-08-17T09:00:00Z"))));
        given(settingRepository.findByStoreIdForUpdate(STORE_ID))
                .willReturn(Optional.of(setting(WaitingReceptionMode.AUTO)));
        given(windowRepository.existsAccepting(
                STORE_ID, "interval-key", BUSINESS_DATE, 1L, NOW)).willReturn(false);

        assertClosed(() -> gate.requireOpen(STORE_ID, BUSINESS_DATE, NOW));
    }

    @Test
    void manualAcceptsFromStrictStoreLocalMidnightUntilLastIntervalEnd() {
        given(intervalPort.lockCurrent(
                STORE_ID,
                BUSINESS_DATE,
                Instant.parse("2026-08-16T15:00:00Z"))).willReturn(List.of(interval(
                Instant.parse("2026-08-17T01:00:00Z"),
                Instant.parse("2026-08-17T09:00:00Z"))));
        given(settingRepository.findByStoreIdForUpdate(STORE_ID))
                .willReturn(Optional.of(setting(WaitingReceptionMode.MANUAL)));

        gate.requireOpen(
                STORE_ID,
                BUSINESS_DATE,
                Instant.parse("2026-08-16T15:00:00Z"));

        verifyNoInteractions(windowRepository);
    }

    @Test
    void disabledPausedMissingIntervalAndEndBoundaryFailClosed() {
        given(intervalPort.lockCurrent(STORE_ID, BUSINESS_DATE, NOW)).willReturn(List.of());

        assertClosed(() -> gate.requireOpen(STORE_ID, BUSINESS_DATE, NOW));
        verifyNoInteractions(settingRepository, windowRepository);

        given(intervalPort.lockCurrent(
                STORE_ID,
                BUSINESS_DATE,
                Instant.parse("2026-08-17T09:00:00Z"))).willReturn(List.of(interval(
                Instant.parse("2026-08-17T01:00:00Z"),
                Instant.parse("2026-08-17T09:00:00Z"))));
        given(settingRepository.findByStoreIdForUpdate(STORE_ID))
                .willReturn(Optional.of(WaitingSetting.create(
                        STORE_ID, false, WaitingReceptionMode.PAUSED, 60, NOW)));
        assertClosed(() -> gate.requireOpen(
                STORE_ID,
                BUSINESS_DATE,
                Instant.parse("2026-08-17T09:00:00Z")));
    }

    private static WaitingSetting setting(WaitingReceptionMode mode) {
        return WaitingSetting.create(STORE_ID, true, mode, 60, NOW.minusSeconds(60));
    }

    private static WaitingOperatingInterval interval(Instant startsAt, Instant endsAt) {
        return new WaitingOperatingInterval(
                STORE_ID, "interval-key", 3L, BUSINESS_DATE,
                startsAt, endsAt, "Asia/Seoul");
    }

    private static void assertClosed(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_RECEPTION_CLOSED));
    }
}
