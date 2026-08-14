package com.miriyum.domain.store.entity;

import com.miriyum.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 매장이 구성한 현재 예약금 사용 의도와 비율을 보관하는 1:1 영속 원본이다.
 *
 * <p>이 정책을 생성하거나 변경하는 쓰기 유스케이스는 먼저 같은 매장의 Store 행을 잠가야 한다.</p>
 */
@Entity
@Table(name = "store_reservation_deposit_policies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreReservationDepositPolicy extends BaseEntity {

    private static final int MIN_RATE_PERCENT = 10;
    private static final int MAX_RATE_PERCENT = 30;

    @Id
    @Column(name = "store_id")
    private long storeId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "rate_percent", nullable = false)
    private int ratePercent;

    @Column(name = "policy_version", nullable = false)
    private long policyVersion;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    private StoreReservationDepositPolicy(long storeId, boolean enabled, int ratePercent) {
        if (storeId <= 0) {
            throw new IllegalArgumentException("storeId must be positive");
        }
        validateRatePercent(ratePercent);
        this.storeId = storeId;
        this.enabled = enabled;
        this.ratePercent = ratePercent;
        this.policyVersion = 1L;
    }

    /**
     * 최초 예약금 정책을 생성한다.
     *
     * @param storeId 정책을 소유한 매장 ID
     * @param enabled 매장의 예약금 사용 의도
     * @param ratePercent 10~30 범위의 정수 비율
     * @return 업무 revision 1과 기술 잠금 version 0으로 시작하는 정책
     */
    public static StoreReservationDepositPolicy create(
            long storeId,
            boolean enabled,
            int ratePercent
    ) {
        return new StoreReservationDepositPolicy(storeId, enabled, ratePercent);
    }

    /**
     * Store 행 잠금을 획득한 쓰기 유스케이스에서 현재 설정을 변경한다.
     *
     * @param enabled 새 예약금 사용 의도
     * @param ratePercent 새 10~30 정수 비율
     * @return 설정이 실제로 변경됐으면 {@code true}
     */
    public boolean update(boolean enabled, int ratePercent) {
        validateRatePercent(ratePercent);
        if (this.enabled == enabled && this.ratePercent == ratePercent) {
            return false;
        }
        this.enabled = enabled;
        this.ratePercent = ratePercent;
        this.policyVersion++;
        return true;
    }

    private static void validateRatePercent(int ratePercent) {
        if (ratePercent < MIN_RATE_PERCENT || ratePercent > MAX_RATE_PERCENT) {
            throw new IllegalArgumentException("ratePercent must be between 10 and 30");
        }
    }
}
