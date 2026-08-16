package com.miriyum.domain.auth.qrepoch;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Consumer QR 발급 시점 캡처와 trusted ledger 기준 검증만 제공하는 공개 계약이다. */
@Service
public class ConsumerQrEpochService {

    private final ConsumerQrEpochStore store;

    public ConsumerQrEpochService(ConsumerQrEpochStore store) {
        this.store = store;
    }

    /** 현재 계정의 opaque QR 세대 snapshot을 원자적으로 캡처한다. */
    public ConsumerQrEpochSnapshot captureCurrent(Long accountId) {
        return store.captureCurrent(accountId);
    }

    /**
     * Reservation ledger에서 얻은 trusted 계정과 snapshot을 검증한다. 불일치하거나 오래된 snapshot은
     * 현재 값을 노출하지 않고 AUTH_017로 거부하며, 저장소 장애·손상은 COMMON_012로 실패 폐쇄한다.
     */
    public void requireCurrent(Long expectedAccountId, ConsumerQrEpochSnapshot snapshot) {
        if (snapshot == null || !Objects.equals(expectedAccountId, snapshot.accountId())) {
            throw stale();
        }
        if (!store.isCurrent(expectedAccountId, snapshot.opaqueVersion())) {
            throw stale();
        }
    }

    private ServiceException stale() {
        return new ServiceException(AuthErrorCode.QR_EPOCH_STALE);
    }
}
