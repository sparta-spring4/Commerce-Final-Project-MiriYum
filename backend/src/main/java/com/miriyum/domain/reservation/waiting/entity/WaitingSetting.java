package com.miriyum.domain.reservation.waiting.entity;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * 매장별 현재 웨이팅 운영 설정과 단조 증가하는 공개 설정 version을 소유한다.
 */
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

    /**
     * 저장된 설정이 없는 매장의 첫 version 설정을 만든다.
     *
     * @param storeId 매장 ID
     * @param enabled 신규 웨이팅 접수 기능 사용 여부
     * @param receptionMode 접수 방식
     * @param advanceOpenMinutes AUTO 사전 오픈 분
     * @param now 생성 시각
     * @return version 1의 설정
     */
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

    /**
     * 현재 version이 기대값과 같을 때 설정 전체를 교체하고 version을 1 증가시킨다.
     *
     * @param expectedVersion 요청자가 확인한 현재 version
     * @param enabled 신규 웨이팅 접수 기능 사용 여부
     * @param receptionMode 접수 방식
     * @param advanceOpenMinutes AUTO 사전 오픈 분
     * @param now 변경 시각
     */
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
