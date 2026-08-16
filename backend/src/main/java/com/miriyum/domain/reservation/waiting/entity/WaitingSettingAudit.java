package com.miriyum.domain.reservation.waiting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;

/**
 * 설정 교체 시점의 actor와 전체 설정 snapshot을 version별로 보존한다.
 */
@Entity
@Table(name = "waiting_setting_audits", uniqueConstraints =
        @UniqueConstraint(name = "uk_waiting_setting_audits_store_version",
                columnNames = {"store_id", "settings_version"}))
public class WaitingSettingAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "waiting_setting_audit_id")
    private Long id;
    @Column(name = "store_id", nullable = false) private Long storeId;
    @Column(name = "settings_version", nullable = false) private long settingsVersion;
    @Column(name = "operator_account_id", nullable = false) private Long operatorAccountId;
    @Column(name = "enabled", nullable = false) private boolean enabled;
    @Enumerated(EnumType.STRING)
    @Column(name = "reception_mode", nullable = false, length = 16)
    private WaitingReceptionMode receptionMode;
    @Column(name = "advance_open_minutes", nullable = false) private int advanceOpenMinutes;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected WaitingSettingAudit() {}

    /**
     * 확정된 설정 version의 불변 감사 snapshot을 기록한다.
     *
     * @param setting 저장이 확정된 현재 설정
     * @param operatorAccountId 변경을 수행한 운영자 계정 ID
     * @param now 감사 기록 시각
     * @return 설정과 동일 version을 가진 감사 snapshot
     */
    public static WaitingSettingAudit record(WaitingSetting setting, long operatorAccountId, Instant now) {
        Objects.requireNonNull(setting);
        if (operatorAccountId <= 0 || now == null) throw new IllegalArgumentException("invalid audit actor");
        WaitingSettingAudit audit = new WaitingSettingAudit();
        audit.storeId = setting.getStoreId();
        audit.settingsVersion = setting.getVersion();
        audit.operatorAccountId = operatorAccountId;
        audit.enabled = setting.isEnabled();
        audit.receptionMode = setting.getReceptionMode();
        audit.advanceOpenMinutes = setting.getAdvanceOpenMinutes();
        audit.createdAt = now;
        return audit;
    }

    public Long getId() { return id; }
    public Long getStoreId() { return storeId; }
    public long getSettingsVersion() { return settingsVersion; }
    public Long getOperatorAccountId() { return operatorAccountId; }
    public boolean isEnabled() { return enabled; }
    public WaitingReceptionMode getReceptionMode() { return receptionMode; }
    public int getAdvanceOpenMinutes() { return advanceOpenMinutes; }
    public Instant getCreatedAt() { return createdAt; }
}
