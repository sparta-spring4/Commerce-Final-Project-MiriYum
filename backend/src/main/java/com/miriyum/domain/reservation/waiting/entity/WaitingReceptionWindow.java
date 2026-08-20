package com.miriyum.domain.reservation.waiting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "waiting_reception_windows")
public class WaitingReceptionWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "waiting_reception_window_id")
    private Long id;

    @Column(name = "opened_by_job_id", nullable = false, updatable = false)
    private Long openedByJobId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(name = "business_interval_key", nullable = false, length = 128, updatable = false)
    private String businessIntervalKey;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(name = "accepting_from", nullable = false, updatable = false)
    private Instant acceptingFrom;

    @Column(name = "accepting_until", nullable = false, updatable = false)
    private Instant acceptingUntil;

    @Column(name = "opened_settings_version", nullable = false, updatable = false)
    private long openedSettingsVersion;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    protected WaitingReceptionWindow() {
    }

    public static WaitingReceptionWindow opened(
            long openedByJobId,
            long storeId,
            String businessIntervalKey,
            LocalDate businessDate,
            Instant acceptingFrom,
            Instant acceptingUntil,
            long openedSettingsVersion,
            Instant openedAt
    ) {
        if (openedByJobId <= 0 || storeId <= 0 || openedSettingsVersion <= 0
                || businessIntervalKey == null || businessIntervalKey.isBlank()
                || businessIntervalKey.length() > 128
                || businessDate == null || acceptingFrom == null || acceptingUntil == null
                || !acceptingFrom.isBefore(acceptingUntil) || openedAt == null) {
            throw new IllegalArgumentException("reception window snapshot is invalid");
        }
        WaitingReceptionWindow window = new WaitingReceptionWindow();
        window.openedByJobId = openedByJobId;
        window.storeId = storeId;
        window.businessIntervalKey = businessIntervalKey;
        window.businessDate = businessDate;
        window.acceptingFrom = acceptingFrom;
        window.acceptingUntil = acceptingUntil;
        window.openedSettingsVersion = openedSettingsVersion;
        window.openedAt = openedAt;
        return window;
    }

    public boolean accepts(Instant now, long settingsVersion) {
        return now != null
                && openedSettingsVersion == settingsVersion
                && !now.isBefore(acceptingFrom)
                && now.isBefore(acceptingUntil);
    }

    public Long getId() { return id; }
    public Long getOpenedByJobId() { return openedByJobId; }
    public Long getStoreId() { return storeId; }
    public String getBusinessIntervalKey() { return businessIntervalKey; }
    public LocalDate getBusinessDate() { return businessDate; }
    public Instant getAcceptingFrom() { return acceptingFrom; }
    public Instant getAcceptingUntil() { return acceptingUntil; }
    public long getOpenedSettingsVersion() { return openedSettingsVersion; }
    public Instant getOpenedAt() { return openedAt; }
}
