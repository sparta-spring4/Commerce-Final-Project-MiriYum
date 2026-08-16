package com.miriyum.domain.payment.dto;

/** 확정 예약 ID 집합에 연결된 미종결 결제 영향 projection이다. */
public record StorePaymentImpact(long reservationCount, long unsettledCount) {
    public StorePaymentImpact {
        if (reservationCount < 0 || unsettledCount < 0) {
            throw new IllegalArgumentException("payment impact is invalid");
        }
    }
}
