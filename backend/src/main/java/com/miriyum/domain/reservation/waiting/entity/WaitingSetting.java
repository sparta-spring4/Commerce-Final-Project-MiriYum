package com.miriyum.domain.reservation.waiting.entity;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "waiting_settings", uniqueConstraints =
        @UniqueConstraint(name = "uk_waiting_settings_store", columnNames = "store_id"))
public class WaitingSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "waiting_setting_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "reception_mode", nullable = false, length = 16)
    private WaitingReceptionMode receptionMode;

    @Column(name = "advance_open_minutes", nullable = false)
    private int advanceOpenMinutes;

    @Column(name = "version", nullable = false)
    private long version;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WaitingSetting() {}

    public static WaitingSetting create(long storeId, boolean enabled,
            WaitingReceptionMode receptionMode, int advanceOpenMinutes, Instant now) {
        validate(storeId, enabled, receptionMode, advanceOpenMinutes, now);
        WaitingSetting setting = new WaitingSetting();
        setting.storeId = storeId;
        setting.enabled = enabled;
        setting.receptionMode = receptionMode;
        setting.advanceOpenMinutes = advanceOpenMinutes;
        setting.version = 1L;
        setting.createdAt = now;
        setting.updatedAt = now;
        return setting;
    }

    public void replace(long expectedVersion, boolean enabled,
            WaitingReceptionMode receptionMode, int advanceOpenMinutes, Instant now) {
        if (version != expectedVersion) {
            throw new ServiceException(ReservationErrorCode.WAITING_SETTING_VERSION_CONFLICT);
        }
        validate(storeId, enabled, receptionMode, advanceOpenMinutes, now);
        this.enabled = enabled;
        this.receptionMode = receptionMode;
        this.advanceOpenMinutes = advanceOpenMinutes;
        this.version++;
        this.updatedAt = now;
    }

    public boolean canOpenAutomatically(long expectedVersion) {
        return version == expectedVersion && enabled && receptionMode == WaitingReceptionMode.AUTO;
    }

    private static void validate(long storeId, boolean enabled,
            WaitingReceptionMode receptionMode, int advanceOpenMinutes, Instant now) {
        if (storeId <= 0 || receptionMode == null || now == null
                || advanceOpenMinutes < 0 || advanceOpenMinutes > 180
                || (!enabled && receptionMode != WaitingReceptionMode.PAUSED)) {
            throw new IllegalArgumentException("invalid waiting setting");
        }
    }

    public Long getId() { return id; }
    public Long getStoreId() { return storeId; }
    public boolean isEnabled() { return enabled; }
    public WaitingReceptionMode getReceptionMode() { return receptionMode; }
    public int getAdvanceOpenMinutes() { return advanceOpenMinutes; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
