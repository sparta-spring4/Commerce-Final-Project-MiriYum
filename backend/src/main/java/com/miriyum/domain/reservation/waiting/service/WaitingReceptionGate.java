package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.entity.WaitingReceptionMode;
import com.miriyum.domain.reservation.waiting.entity.WaitingSetting;
import com.miriyum.domain.reservation.waiting.repository.WaitingReceptionWindowRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingSettingRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 현재 Store 영업 구간과 Waiting 설정/오픈 원장을 함께 확인하는 접수 게이트다. */
@Service
public class WaitingReceptionGate {

    private final WaitingOperatingIntervalPort intervalPort;
    private final WaitingSettingRepository settingRepository;
    private final WaitingReceptionWindowRepository windowRepository;

    public WaitingReceptionGate(
            WaitingOperatingIntervalPort intervalPort,
            WaitingSettingRepository settingRepository,
            WaitingReceptionWindowRepository windowRepository
    ) {
        this.intervalPort = Objects.requireNonNull(intervalPort);
        this.settingRepository = Objects.requireNonNull(settingRepository);
        this.windowRepository = Objects.requireNonNull(windowRepository);
    }

    public void requireOpen(long storeId, LocalDate businessDate, Instant now) {
        if (storeId <= 0 || businessDate == null || now == null) {
            throw closed();
        }
        List<WaitingOperatingInterval> intervals =
                intervalPort.lockCurrent(storeId, businessDate, now);
        if (intervals.isEmpty()) {
            throw closed();
        }
        WaitingSetting setting = settingRepository.findByStoreIdForUpdate(storeId)
                .orElseThrow(WaitingReceptionGate::closed);
        if (!setting.isEnabled() || setting.getReceptionMode() == WaitingReceptionMode.PAUSED) {
            throw closed();
        }
        if (setting.getReceptionMode() == WaitingReceptionMode.AUTO) {
            boolean accepting = intervals.stream().anyMatch(interval ->
                    interval.storeId() == storeId
                            && interval.businessDate().equals(businessDate)
                            && windowRepository.existsAccepting(
                                    storeId,
                                    interval.businessIntervalKey(),
                                    businessDate,
                                    setting.getVersion(),
                                    now));
            if (!accepting) {
                throw closed();
            }
            return;
        }
        if (setting.getReceptionMode() != WaitingReceptionMode.MANUAL
                || !manualWindowAccepts(intervals, businessDate, now)) {
            throw closed();
        }
    }

    private static boolean manualWindowAccepts(
            List<WaitingOperatingInterval> intervals,
            LocalDate businessDate,
            Instant now
    ) {
        String timeZoneId = intervals.getFirst().timeZoneId();
        if (timeZoneId == null || intervals.stream().anyMatch(interval ->
                !timeZoneId.equals(interval.timeZoneId())
                        || !businessDate.equals(interval.businessDate()))) {
            return false;
        }
        Instant midnight = strictInstant(businessDate.atStartOfDay(), timeZoneId);
        Instant acceptingUntil = intervals.stream()
                .map(WaitingOperatingInterval::endsAt)
                .max(Instant::compareTo)
                .orElse(null);
        return midnight != null
                && acceptingUntil != null
                && !now.isBefore(midnight)
                && now.isBefore(acceptingUntil);
    }

    private static Instant strictInstant(LocalDateTime value, String timeZoneId) {
        try {
            ZoneId zone = ZoneId.of(timeZoneId);
            List<ZoneOffset> offsets = zone.getRules().getValidOffsets(value);
            return offsets.size() == 1 ? value.toInstant(offsets.getFirst()) : null;
        } catch (DateTimeException failure) {
            return null;
        }
    }

    private static ServiceException closed() {
        return new ServiceException(ReservationErrorCode.WAITING_RECEPTION_CLOSED);
    }
}
