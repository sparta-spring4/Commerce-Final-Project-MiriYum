package com.miriyum.domain.store.dto.contract;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Store가 소유한 현재 예약금 설정을 다른 도메인에 노출하는 불변 계약이다.
 *
 * @param storeId 설정을 소유한 매장 식별자
 * @param status 매장의 설정 의도이며 실제 예약금 적용 가능을 보장하지 않는 상태
 * @param ratePercent 구성 상태에서만 존재하는 10~30 정수 비율
 * @param policyVersion 구성 상태에서만 존재하는 양수 Store 설정 revision
 */
public record StoreReservationDepositPolicy(
        long storeId,
        Status status,
        OptionalInt ratePercent,
        OptionalLong policyVersion
) {

    /** Store 예약금 설정 행의 존재와 사용 의도를 표현한다. */
    public enum Status {
        UNCONFIGURED,
        DISABLED,
        ENABLED
    }

    public StoreReservationDepositPolicy {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(ratePercent, "ratePercent must not be null");
        Objects.requireNonNull(policyVersion, "policyVersion must not be null");

        if (status == Status.UNCONFIGURED) {
            if (ratePercent.isPresent() || policyVersion.isPresent()) {
                throw new IllegalArgumentException(
                        "unconfigured policy must not contain ratePercent or policyVersion");
            }
        } else {
            if (ratePercent.isEmpty() || policyVersion.isEmpty()) {
                throw new IllegalArgumentException(
                        "configured policy must contain ratePercent and policyVersion");
            }
            if (ratePercent.getAsInt() < 10 || ratePercent.getAsInt() > 30) {
                throw new IllegalArgumentException("ratePercent must be between 10 and 30");
            }
            if (policyVersion.getAsLong() <= 0) {
                throw new IllegalArgumentException("policyVersion must be positive");
            }
        }
    }
}
